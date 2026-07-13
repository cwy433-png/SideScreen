package com.sidescreen.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Wire protocol v1 for Mac → tablet ambient media push over a dedicated socket.
 * Separate from the realtime StreamingServer frame types (0–12).
 *
 * Request:  magic(4) + version(1) + manifestLen(u32 BE) + manifest UTF-8 JSON
 *           + raw file bodies in manifest order (each of declared size).
 * Response: magic(4) + version(1) + ok(u8) + msgLen(u16 BE) + message UTF-8.
 */
object AmbientSyncProtocol {
    const val MAGIC_0: Byte = 0x53 // 'S'
    const val MAGIC_1: Byte = 0x53 // 'S'
    const val MAGIC_2: Byte = 0x41 // 'A'
    const val MAGIC_3: Byte = 0x4D // 'M'
    const val VERSION: Byte = 1

    /** Device-side fixed port; Mac uses adb forward to reach it. */
    const val DEVICE_PORT = 57322

    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_ENTRIES = 201 // up to 200 photos + 1 video
    const val MAX_PHOTO_BYTES = 50L * 1024L * 1024L
    const val MAX_VIDEO_BYTES = 500L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
    const val MAX_ACK_MESSAGE_BYTES = 512
    const val IO_CHUNK_BYTES = 64 * 1024

    val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov")

    enum class Kind {
        PHOTO,
        VIDEO,
        ;

        val wire: String
            get() = name.lowercase(Locale.US)

        companion object {
            fun fromWire(value: String): Kind? =
                when (value.lowercase(Locale.US)) {
                    "photo" -> PHOTO
                    "video" -> VIDEO
                    else -> null
                }
        }
    }

    data class ManifestEntry(
        val id: String,
        val kind: Kind,
        val name: String,
        val size: Long,
        val sha256: String,
    )

    /**
     * Optional mode the tablet should apply only after a successful commit.
     * Wire values: `"photos"` / `"video"`. Omitted by legacy senders and on clear.
     */
    enum class PreferredMode {
        PHOTOS,
        VIDEO,
        ;

        val wire: String
            get() = name.lowercase(Locale.US)

        fun toAmbientMode(): AmbientMode =
            when (this) {
                PHOTOS -> AmbientMode.PHOTOS
                VIDEO -> AmbientMode.VIDEO
            }

        companion object {
            fun fromWire(value: String): PreferredMode? =
                when (value.lowercase(Locale.US)) {
                    "photos" -> PHOTOS
                    "video" -> VIDEO
                    else -> null
                }
        }
    }

    data class Manifest(
        val entries: List<ManifestEntry>,
        /** Null means keep current ambient mode (legacy or clear). */
        val preferredMode: PreferredMode? = null,
    )

    sealed class ProtocolError(message: String) : Exception(message) {
        class InvalidMagic : ProtocolError("invalid magic")
        class UnsupportedVersion(v: Int) : ProtocolError("unsupported version $v")
        class ManifestTooLarge(n: Int) : ProtocolError("manifest too large ($n)")
        class Truncated : ProtocolError("truncated input")
        class MalformedManifest(detail: String) : ProtocolError("malformed manifest: $detail")
        class LimitExceeded(detail: String) : ProtocolError("limit exceeded: $detail")
        class UnsupportedEntry(detail: String) : ProtocolError("unsupported entry: $detail")
    }

    fun isValidMagic(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Boolean =
        b0 == MAGIC_0 && b1 == MAGIC_1 && b2 == MAGIC_2 && b3 == MAGIC_3

    /**
     * Sanitize a display name to a single path segment: strip directories,
     * control chars, and unsafe characters. Never used as a caller-controlled path.
     */
    fun sanitizeDisplayName(raw: String?): String {
        if (raw.isNullOrBlank()) return "file"
        var name = raw.replace('\\', '/').substringAfterLast('/')
        name = name.trim().trimStart('.')
        if (name.isEmpty()) return "file"
        val cleaned =
            buildString(name.length.coerceAtMost(180)) {
                for (ch in name) {
                    if (length >= 180) break
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' || ch == ' ' ->
                            append(ch)
                        else -> append('_')
                    }
                }
            }.trim().trimStart('.')
        return if (cleaned.isEmpty()) "file" else cleaned
    }

    fun extensionOf(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx < 0 || idx == name.lastIndex) return ""
        return name.substring(idx + 1).lowercase(Locale.US)
    }

    fun isHexSha256(value: String): Boolean {
        if (value.length != 64) return false
        return value.all { ch ->
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
        }
    }

    fun parseManifestJson(json: String): Manifest {
        val root =
            try {
                JSONObject(json)
            } catch (e: Exception) {
                throw ProtocolError.MalformedManifest("json parse failed")
            }
        val arr: JSONArray =
            when {
                root.has("entries") -> root.optJSONArray("entries")
                    ?: throw ProtocolError.MalformedManifest("entries not array")
                else -> throw ProtocolError.MalformedManifest("missing entries")
            }
        if (arr.length() > MAX_ENTRIES) {
            throw ProtocolError.LimitExceeded("entry count ${arr.length()}")
        }

        // Optional preferredMode: key absent or JSON null → keep current mode (legacy).
        // Explicit empty string is invalid (not equivalent to omission).
        val preferredMode: PreferredMode? =
            if (!root.has("preferredMode") || root.isNull("preferredMode")) {
                null
            } else {
                val raw = root.optString("preferredMode", "").trim()
                if (raw.isEmpty()) {
                    throw ProtocolError.MalformedManifest("empty preferredMode")
                }
                PreferredMode.fromWire(raw)
                    ?: throw ProtocolError.MalformedManifest("bad preferredMode $raw")
            }

        val entries = ArrayList<ManifestEntry>(arr.length())
        var total = 0L
        var videoCount = 0
        var photoCount = 0
        val seenIds = HashSet<String>()
        val seenPhotoHashes = HashSet<String>()

        for (i in 0 until arr.length()) {
            val obj =
                arr.optJSONObject(i)
                    ?: throw ProtocolError.MalformedManifest("entry $i not object")
            val id = obj.optString("id", "").trim()
            val kindWire = obj.optString("kind", "").trim()
            val nameRaw = obj.optString("name", "")
            val size =
                when {
                    obj.has("size") -> obj.optLong("size", -1L)
                    else -> -1L
                }
            val sha256 = obj.optString("sha256", "").trim().lowercase(Locale.US)

            if (id.isEmpty() || id.length > 128 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                throw ProtocolError.MalformedManifest("bad id at $i")
            }
            if (!seenIds.add(id)) {
                throw ProtocolError.MalformedManifest("duplicate id $id")
            }
            val kind =
                Kind.fromWire(kindWire)
                    ?: throw ProtocolError.UnsupportedEntry("kind $kindWire at $i")
            if (!isHexSha256(sha256)) {
                throw ProtocolError.MalformedManifest("bad sha256 at $i")
            }
            if (size < 0L) {
                throw ProtocolError.MalformedManifest("bad size at $i")
            }

            val name = sanitizeDisplayName(nameRaw)
            val ext = extensionOf(name)
            when (kind) {
                Kind.PHOTO -> {
                    photoCount++
                    if (ext !in PHOTO_EXTENSIONS) {
                        throw ProtocolError.UnsupportedEntry("photo ext .$ext")
                    }
                    if (size > MAX_PHOTO_BYTES) {
                        throw ProtocolError.LimitExceeded("photo size $size")
                    }
                    // Duplicate photo hashes misalign raw body order vs filtered entries.
                    if (!seenPhotoHashes.add(sha256)) {
                        throw ProtocolError.MalformedManifest("duplicate photo sha256 at $i")
                    }
                }
                Kind.VIDEO -> {
                    videoCount++
                    if (videoCount > 1) {
                        throw ProtocolError.LimitExceeded("more than one video")
                    }
                    if (ext !in VIDEO_EXTENSIONS) {
                        throw ProtocolError.UnsupportedEntry("video ext .$ext")
                    }
                    if (size > MAX_VIDEO_BYTES) {
                        throw ProtocolError.LimitExceeded("video size $size")
                    }
                }
            }

            total += size
            if (total > MAX_TOTAL_BYTES) {
                throw ProtocolError.LimitExceeded("total size $total")
            }

            entries.add(
                ManifestEntry(
                    id = id,
                    kind = kind,
                    name = name,
                    size = size,
                    sha256 = sha256,
                ),
            )
        }

        // Empty entries = clear tablet media; must not carry preferredMode.
        if (entries.isEmpty() && preferredMode != null) {
            throw ProtocolError.MalformedManifest("preferredMode not allowed on clear")
        }
        // When preferredMode is set, entries must be exclusive to that type.
        // Legacy manifests (preferredMode absent) may still mix photos + video.
        when (preferredMode) {
            PreferredMode.PHOTOS -> {
                if (photoCount < 1 || videoCount != 0) {
                    throw ProtocolError.MalformedManifest(
                        "preferredMode photos requires one or more photos and zero videos",
                    )
                }
            }
            PreferredMode.VIDEO -> {
                if (videoCount != 1 || photoCount != 0) {
                    throw ProtocolError.MalformedManifest(
                        "preferredMode video requires exactly one video and zero photos",
                    )
                }
            }
            null -> Unit
        }

        return Manifest(entries = entries, preferredMode = preferredMode)
    }

    fun encodeAck(ok: Boolean, message: String): ByteArray {
        val msgBytes =
            message
                .take(MAX_ACK_MESSAGE_BYTES)
                .toByteArray(Charsets.UTF_8)
                .let { if (it.size > MAX_ACK_MESSAGE_BYTES) it.copyOf(MAX_ACK_MESSAGE_BYTES) else it }
        val out = ByteArray(4 + 1 + 1 + 2 + msgBytes.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = MAGIC_2
        out[3] = MAGIC_3
        out[4] = VERSION
        out[5] = if (ok) 1 else 0
        val len = msgBytes.size
        out[6] = ((len ushr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()
        System.arraycopy(msgBytes, 0, out, 8, msgBytes.size)
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun readUInt32BE(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Long {
        return ((b0.toLong() and 0xFF) shl 24) or
            ((b1.toLong() and 0xFF) shl 16) or
            ((b2.toLong() and 0xFF) shl 8) or
            (b3.toLong() and 0xFF)
    }

    fun readUInt16BE(b0: Byte, b1: Byte): Int {
        return ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
    }
}
