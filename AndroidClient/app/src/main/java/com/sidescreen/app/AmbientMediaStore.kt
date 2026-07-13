package com.sidescreen.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * App-private ambient media storage under [filesDir]/ambient.
 *
 * Layout:
 *   ambient/current/manifest.json
 *   ambient/current/files/<id>.<ext>
 *   ambient/staging-… (ephemeral during receive)
 *   ambient/previous-… (brief retire of current during commit; recovered on crash)
 *
 * Receive stages into a unique staging dir, verifies sizes + SHA-256, then
 * atomically replaces `current`. On any failure, previous `current` is kept
 * (or restored). Empty clear commits an empty current manifest via the same path.
 */
class AmbientMediaStore(filesDir: File) {
    private val root = File(filesDir, "ambient")
    private val currentDir = File(root, "current")
    private val filesSubdirName = "files"
    private val manifestName = "manifest.json"

    data class Summary(
        val photoCount: Int,
        val hasVideo: Boolean,
        val totalBytes: Long,
    ) {
        fun compact(): String {
            val videoPart = if (hasVideo) "1 video" else "no video"
            val sizePart =
                when {
                    totalBytes >= 1024L * 1024L ->
                        String.format(Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
                    totalBytes >= 1024L ->
                        String.format(Locale.US, "%.0f KB", totalBytes / 1024.0)
                    else -> "$totalBytes B"
                }
            return "$photoCount photos, $videoPart ($sizePart)"
        }
    }

    data class BatchInfo(
        val photoUris: List<Uri>,
        val videoUri: Uri?,
        val summary: Summary,
    )

    init {
        root.mkdirs()
        // Restore a valid previous batch when current is missing (crash mid-promotion),
        // then only clean obsolete previous/staging work dirs.
        recoverInterruptedPromotion()
        cleanStaleStaging()
        cleanObsoletePreviousBatches()
    }

    /**
     * If the process died after `current` was renamed to `previous-*` but before
     * staging was promoted, restore the newest valid previous batch as `current`.
     */
    private fun recoverInterruptedPromotion() {
        if (isValidBatchDir(currentDir)) return

        val candidates =
            root.listFiles()
                ?.filter { child ->
                    child.isDirectory &&
                        child.name.startsWith("previous-") &&
                        isValidBatchDir(child)
                }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()

        val best = candidates.firstOrNull() ?: return

        if (currentDir.exists()) {
            // Partial/broken current — remove so rename can succeed.
            currentDir.deleteRecursively()
        }
        if (!best.renameTo(currentDir)) {
            // Leave previous in place if restore fails; do not delete it.
            // (No DiagLog here: keep store usable from JVM unit tests.)
        }
    }

    private fun isValidBatchDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, manifestName).isFile
    }

    /** Remove incomplete receives without touching a possible recovery batch. */
    fun cleanStaleStaging() {
        if (!root.isDirectory) return
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("staging")) {
                child.deleteRecursively()
            }
        }
    }

    /**
     * Previous batches are obsolete only after a valid current batch exists.
     * If recovery rename failed, preserve every previous candidate for the next start.
     */
    private fun cleanObsoletePreviousBatches() {
        if (!isValidBatchDir(currentDir) || !root.isDirectory) return
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("previous-")) {
                child.deleteRecursively()
            }
        }
    }

    /**
     * True when current holds a non-empty set of synced assets.
     * An empty committed manifest (clear) returns false so UI/SAF treat it as no sync.
     * Framework-light: does not construct Uri objects.
     */
    fun hasSyncedMedia(): Boolean {
        val manifestFile = File(currentDir, manifestName)
        if (!manifestFile.isFile) return false
        return try {
            val rootObj = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val arr = rootObj.optJSONArray("entries") ?: return false
            arr.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Load the committed batch. Empty-manifest current yields zero photo/video
     * assets (SAF fallback still applies in the controller).
     */
    fun loadBatch(): BatchInfo? {
        val manifestFile = File(currentDir, manifestName)
        if (!manifestFile.isFile) return null
        return try {
            val json = manifestFile.readText(Charsets.UTF_8)
            val rootObj = JSONObject(json)
            val arr = rootObj.optJSONArray("entries") ?: return null
            val photos = ArrayList<Uri>()
            var video: Uri? = null
            var total = 0L
            val filesDir = File(currentDir, filesSubdirName)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val kind = obj.optString("kind", "")
                val name = obj.optString("name", "file")
                val size = obj.optLong("size", 0L)
                if (id.isEmpty()) continue
                val ext = AmbientSyncProtocol.extensionOf(name)
                val file = File(filesDir, storageFileName(id, ext))
                if (!file.isFile) continue
                total += size
                val uri = Uri.fromFile(file)
                when (kind.lowercase(Locale.US)) {
                    "photo" -> photos.add(uri)
                    "video" -> if (video == null) video = uri
                }
            }
            BatchInfo(
                photoUris = photos,
                videoUri = video,
                summary =
                    Summary(
                        photoCount = photos.size,
                        hasVideo = video != null,
                        totalBytes = total,
                    ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun summaryOrEmpty(): String {
        val batch = loadBatch() ?: return "none"
        if (batch.photoUris.isEmpty() && batch.videoUri == null) return "none"
        return batch.summary.compact()
    }

    /**
     * Begin a receive into a fresh staging directory. Caller streams file
     * bodies into [StagingSession.receiveEntry] then [StagingSession.commit].
     * Empty manifests are valid (clear → empty current).
     */
    fun beginStaging(manifest: AmbientSyncProtocol.Manifest): StagingSession {
        // Only purge leftover staging dirs; never touch previous-* mid-session
        // (a concurrent failed promote may still need its previous batch).
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("staging")) {
                child.deleteRecursively()
            }
        }
        val staging =
            File(root, "staging-${UUID.randomUUID()}").also {
                if (!it.mkdirs()) {
                    throw IllegalStateException("cannot create staging dir")
                }
            }
        val filesDir = File(staging, filesSubdirName).also { it.mkdirs() }
        return StagingSession(staging, filesDir, manifest)
    }

    /**
     * Clear synced media via the same staging/commit transaction as a normal
     * receive (commits an empty current manifest). Prefer the server empty-
     * manifest path; this remains for tests/direct callers.
     */
    fun clearCurrent(): Summary {
        val session = beginStaging(AmbientSyncProtocol.Manifest(emptyList()))
        return session.commit()
    }

    inner class StagingSession(
        private val stagingDir: File,
        private val filesDir: File,
        private val manifest: AmbientSyncProtocol.Manifest,
    ) {
        private val written = HashMap<String, File>()
        private var aborted = false

        /**
         * Stream the next entry body (in manifest order) into a file under staging.
         * Verifies size and SHA-256 as bytes arrive. Never loads the whole file into RAM.
         */
        fun receiveEntry(
            entry: AmbientSyncProtocol.ManifestEntry,
            readFully: (buffer: ByteArray, max: Int) -> Int,
        ) {
            check(!aborted)
            val ext = AmbientSyncProtocol.extensionOf(entry.name)
            val dest = File(filesDir, storageFileName(entry.id, ext))
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(AmbientSyncProtocol.IO_CHUNK_BYTES)
            var remaining = entry.size
            FileOutputStream(dest).use { out ->
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = readFully(buf, toRead)
                    if (n <= 0) {
                        abort()
                        throw AmbientSyncProtocol.ProtocolError.Truncated()
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    remaining -= n
                }
            }
            val actualHex = AmbientSyncProtocol.bytesToHex(digest.digest())
            if (actualHex != entry.sha256.lowercase(Locale.US)) {
                abort()
                throw AmbientSyncProtocol.ProtocolError.MalformedManifest(
                    "sha256 mismatch for ${entry.id}",
                )
            }
            // Double-check on-disk size
            if (dest.length() != entry.size) {
                abort()
                throw AmbientSyncProtocol.ProtocolError.MalformedManifest(
                    "size mismatch for ${entry.id}",
                )
            }
            written[entry.id] = dest
        }

        /**
         * Write manifest and transactionally replace `current`.
         * On failure leaves previous `current` intact (or restored).
         * Never deletes the only valid previous batch if promotion fails.
         */
        fun commit(): Summary {
            check(!aborted)
            if (written.size != manifest.entries.size) {
                abort()
                throw IllegalStateException("not all entries received")
            }
            // Re-verify each file before promoting
            for (entry in manifest.entries) {
                val file = written[entry.id] ?: run {
                    abort()
                    throw IllegalStateException("missing ${entry.id}")
                }
                if (file.length() != entry.size || !verifyFileSha256(file, entry.sha256)) {
                    abort()
                    throw AmbientSyncProtocol.ProtocolError.MalformedManifest(
                        "post-check failed ${entry.id}",
                    )
                }
            }

            writeManifestJson(File(stagingDir, manifestName), manifest)

            val previous = File(root, "previous-${UUID.randomUUID()}")
            var retiredCurrent = false
            try {
                if (currentDir.exists()) {
                    if (!currentDir.renameTo(previous)) {
                        abort()
                        throw IllegalStateException("cannot retire current batch")
                    }
                    retiredCurrent = true
                }
                if (!stagingDir.renameTo(currentDir)) {
                    // Try restore previous — never delete it if restore fails.
                    if (retiredCurrent && previous.exists()) {
                        val restored = previous.renameTo(currentDir)
                        if (!restored) {
                            // Sole valid batch remains at previous-*; leave it.
                            if (stagingDir.exists()) {
                                stagingDir.deleteRecursively()
                            }
                            aborted = true
                            throw IllegalStateException(
                                "cannot promote staging; previous batch preserved at ${previous.name}",
                            )
                        }
                    }
                    abort()
                    throw IllegalStateException("cannot promote staging")
                }
                // Promotion succeeded — safe to drop retired previous.
                if (previous.exists()) {
                    previous.deleteRecursively()
                }
            } catch (e: Exception) {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
                throw e
            }

            var photos = 0
            var hasVideo = false
            var total = 0L
            for (e in manifest.entries) {
                total += e.size
                when (e.kind) {
                    AmbientSyncProtocol.Kind.PHOTO -> photos++
                    AmbientSyncProtocol.Kind.VIDEO -> hasVideo = true
                }
            }
            return Summary(photoCount = photos, hasVideo = hasVideo, totalBytes = total)
        }

        fun abort() {
            aborted = true
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    companion object {
        fun storageFileName(id: String, ext: String): String {
            val safeExt =
                ext.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)
            return if (safeExt.isEmpty()) id else "$id.$safeExt"
        }

        fun writeManifestJson(file: File, manifest: AmbientSyncProtocol.Manifest) {
            val arr = JSONArray()
            for (e in manifest.entries) {
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("kind", e.kind.wire)
                        .put("name", e.name)
                        .put("size", e.size)
                        .put("sha256", e.sha256),
                )
            }
            val root = JSONObject().put("version", 1).put("entries", arr)
            file.writeText(root.toString(), Charsets.UTF_8)
        }

        fun verifyFileSha256(file: File, expectedHex: String): Boolean {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { fis ->
                    DigestInputStream(fis, digest).use { dis ->
                        val buf = ByteArray(AmbientSyncProtocol.IO_CHUNK_BYTES)
                        while (dis.read(buf) != -1) {
                            // digest updates via stream
                        }
                    }
                }
                AmbientSyncProtocol.bytesToHex(digest.digest()) ==
                    expectedHex.lowercase(Locale.US)
            } catch (_: Exception) {
                false
            }
        }
    }
}
