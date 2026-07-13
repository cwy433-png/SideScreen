package com.sidescreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class AmbientSyncProtocolTest {
    @Test
    fun sanitizeDisplayName_stripsPathsAndUnsafe() {
        assertEquals("photo.jpg", AmbientSyncProtocol.sanitizeDisplayName("photo.jpg"))
        assertEquals("photo.jpg", AmbientSyncProtocol.sanitizeDisplayName("/tmp/evil/../photo.jpg"))
        assertEquals("pic.png", AmbientSyncProtocol.sanitizeDisplayName("C:\\Users\\x\\pic.png"))
        assertEquals("passwd", AmbientSyncProtocol.sanitizeDisplayName("../../etc/passwd"))
        assertEquals("file", AmbientSyncProtocol.sanitizeDisplayName(""))
        assertEquals("file", AmbientSyncProtocol.sanitizeDisplayName(null))
        assertEquals("c.webp", AmbientSyncProtocol.sanitizeDisplayName("a/b/c.webp"))
        val weird = AmbientSyncProtocol.sanitizeDisplayName("my photo (1).jpg")
        assertTrue(weird.endsWith(".jpg"))
        assertFalse(weird.contains("("))
    }

    @Test
    fun parseManifest_acceptsValidPhotoAndVideo() {
        val hash = "a".repeat(64)
        val hash2 = "b".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":100,"sha256":"$hash"},
                {"id":"$hash2","kind":"video","name":"v.mp4","size":200,"sha256":"$hash2"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals(2, m.entries.size)
        assertEquals(AmbientSyncProtocol.Kind.PHOTO, m.entries[0].kind)
        assertEquals(AmbientSyncProtocol.Kind.VIDEO, m.entries[1].kind)
    }

    @Test
    fun parseManifest_rejectsDuplicatePhotoHashes() {
        val hash = "c".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"id1","kind":"photo","name":"a.jpg","size":10,"sha256":"$hash"},
                {"id":"id2","kind":"photo","name":"b.jpg","size":10,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected duplicate photo sha256 to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun parseManifest_rejectsPathTraversalNamesAsSanitizedOnly() {
        // Name is sanitized; path never used as filesystem path.
        val hash = "d".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"$hash","kind":"photo","name":"../../evil.jpg","size":1,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals("evil.jpg", m.entries[0].name)
    }

    @Test
    fun parseManifest_rejectsBadKind() {
        val hash = "e".repeat(64)
        val json =
            """
            {"entries":[{"id":"$hash","kind":"exe","name":"a.bin","size":1,"sha256":"$hash"}]}
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected error")
        } catch (e: AmbientSyncProtocol.ProtocolError) {
            assertTrue(e.message!!.contains("unsupported") || e.message!!.contains("kind"))
        }
    }

    @Test
    fun parseManifest_rejectsTooManyVideos() {
        val h1 = "1".repeat(64)
        val h2 = "2".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"v1","kind":"video","name":"a.mp4","size":1,"sha256":"$h1"},
                {"id":"v2","kind":"video","name":"b.mp4","size":1,"sha256":"$h2"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected error")
        } catch (e: AmbientSyncProtocol.ProtocolError.LimitExceeded) {
            // expected
        }
    }

    @Test
    fun parseManifest_emptyEntriesAllowed() {
        val m = AmbientSyncProtocol.parseManifestJson("""{"entries":[]}""")
        assertTrue(m.entries.isEmpty())
        assertEquals(null, m.preferredMode)
    }

    @Test
    fun parseManifest_legacyWithoutPreferredMode_keepsNull() {
        // Old Mac senders omit preferredMode → Android keeps current mode.
        val hash = "a".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":100,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals(null, m.preferredMode)
        assertEquals(1, m.entries.size)
    }

    @Test
    fun parseManifest_preferredModePhotos() {
        val hash = "a".repeat(64)
        val json =
            """
            {
              "preferredMode":"photos",
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":100,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals(AmbientSyncProtocol.PreferredMode.PHOTOS, m.preferredMode)
        assertEquals(AmbientMode.PHOTOS, m.preferredMode!!.toAmbientMode())
    }

    @Test
    fun parseManifest_preferredModeVideo() {
        val hash = "b".repeat(64)
        val json =
            """
            {
              "preferredMode":"video",
              "entries": [
                {"id":"$hash","kind":"video","name":"v.mp4","size":200,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals(AmbientSyncProtocol.PreferredMode.VIDEO, m.preferredMode)
        assertEquals(AmbientMode.VIDEO, m.preferredMode!!.toAmbientMode())
    }

    @Test
    fun parseManifest_rejectsClearWithPreferredMode() {
        // Clear must not change ambient mode; preferredMode forbidden on empty entries.
        try {
            AmbientSyncProtocol.parseManifestJson(
                """{"preferredMode":"photos","entries":[]}""",
            )
            fail("expected preferredMode on clear to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("preferredMode") || e.message!!.contains("clear"))
        }
    }

    @Test
    fun parseManifest_rejectsPhotosModeWithoutPhoto() {
        val hash = "c".repeat(64)
        val json =
            """
            {
              "preferredMode":"photos",
              "entries": [
                {"id":"$hash","kind":"video","name":"v.mp4","size":10,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected photos mode without photo to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("photos"))
        }
    }

    @Test
    fun parseManifest_rejectsVideoModeWithoutVideo() {
        val hash = "d".repeat(64)
        val json =
            """
            {
              "preferredMode":"video",
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":10,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected video mode without video to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("video"))
        }
    }

    @Test
    fun parseManifest_rejectsInvalidPreferredMode() {
        val hash = "e".repeat(64)
        val json =
            """
            {
              "preferredMode":"slideshow",
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":10,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected invalid preferredMode to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("preferredMode"))
        }
    }

    @Test
    fun parseManifest_rejectsEmptyStringPreferredMode() {
        // Explicit empty string is invalid; only key-absent/null is legacy omission.
        val hash = "f".repeat(64)
        val json =
            """
            {
              "preferredMode":"",
              "entries": [
                {"id":"$hash","kind":"photo","name":"a.jpg","size":10,"sha256":"$hash"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected empty-string preferredMode to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(
                e.message!!.contains("preferredMode") || e.message!!.contains("empty"),
            )
        }
    }

    @Test
    fun parseManifest_rejectsMixedEntriesWithPreferredModePhotos() {
        val h1 = "a".repeat(64)
        val h2 = "b".repeat(64)
        val json =
            """
            {
              "preferredMode":"photos",
              "entries": [
                {"id":"$h1","kind":"photo","name":"a.jpg","size":10,"sha256":"$h1"},
                {"id":"$h2","kind":"video","name":"v.mp4","size":20,"sha256":"$h2"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected mixed entries with preferredMode photos to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("photos") || e.message!!.contains("zero videos"))
        }
    }

    @Test
    fun parseManifest_rejectsMixedEntriesWithPreferredModeVideo() {
        val h1 = "c".repeat(64)
        val h2 = "d".repeat(64)
        val json =
            """
            {
              "preferredMode":"video",
              "entries": [
                {"id":"$h1","kind":"photo","name":"a.jpg","size":10,"sha256":"$h1"},
                {"id":"$h2","kind":"video","name":"v.mp4","size":20,"sha256":"$h2"}
              ]
            }
            """.trimIndent()
        try {
            AmbientSyncProtocol.parseManifestJson(json)
            fail("expected mixed entries with preferredMode video to be rejected")
        } catch (e: AmbientSyncProtocol.ProtocolError.MalformedManifest) {
            assertTrue(e.message!!.contains("video") || e.message!!.contains("zero photos"))
        }
    }

    @Test
    fun parseManifest_legacyMixedBatchWithoutPreferredMode_stillAllowed() {
        // Old mixed photo+video manifests remain valid when preferredMode is omitted.
        val h1 = "1".repeat(64)
        val h2 = "2".repeat(64)
        val json =
            """
            {
              "entries": [
                {"id":"$h1","kind":"photo","name":"a.jpg","size":10,"sha256":"$h1"},
                {"id":"$h2","kind":"video","name":"v.mp4","size":20,"sha256":"$h2"}
              ]
            }
            """.trimIndent()
        val m = AmbientSyncProtocol.parseManifestJson(json)
        assertEquals(null, m.preferredMode)
        assertEquals(2, m.entries.size)
    }

    @Test
    fun encodeAck_roundTripShape() {
        val bytes = AmbientSyncProtocol.encodeAck(true, "ok")
        assertTrue(AmbientSyncProtocol.isValidMagic(bytes[0], bytes[1], bytes[2], bytes[3]))
        assertEquals(AmbientSyncProtocol.VERSION, bytes[4])
        assertEquals(1.toByte(), bytes[5])
        val len = AmbientSyncProtocol.readUInt16BE(bytes[6], bytes[7])
        assertEquals(2, len)
        assertEquals("ok", String(bytes, 8, len, Charsets.UTF_8))
    }

    @Test
    fun storageFileName_safe() {
        assertEquals("abc.jpg", AmbientMediaStore.storageFileName("abc", "jpg"))
        assertEquals("abc", AmbientMediaStore.storageFileName("abc", ""))
        assertEquals("id123.png", AmbientMediaStore.storageFileName("id123", "PNG"))
    }

    @Test
    fun mediaStore_transactionalCommitAndClear() {
        val tmp = Files.createTempDirectory("ambient-test-").toFile()
        try {
            val store = AmbientMediaStore(tmp)
            val hash = sha256Of(byteArrayOf(1, 2, 3, 4))
            val entry =
                AmbientSyncProtocol.ManifestEntry(
                    id = hash,
                    kind = AmbientSyncProtocol.Kind.PHOTO,
                    name = "t.jpg",
                    size = 4,
                    sha256 = hash,
                )
            val manifest = AmbientSyncProtocol.Manifest(listOf(entry))
            val session = store.beginStaging(manifest)
            val payload = byteArrayOf(1, 2, 3, 4)
            var offset = 0
            session.receiveEntry(entry) { buffer, max ->
                val n = minOf(max, payload.size - offset)
                if (n <= 0) return@receiveEntry -1
                System.arraycopy(payload, offset, buffer, 0, n)
                offset += n
                n
            }
            val summary = session.commit()
            assertEquals(1, summary.photoCount)
            assertTrue(store.hasSyncedMedia())
            // Prefer hasSyncedMedia over Uri-based loadBatch (framework-light check).
            assertTrue(java.io.File(tmp, "ambient/current/manifest.json").isFile)

            // Failed staging must not wipe current
            val badHash = "f".repeat(64)
            val badEntry =
                AmbientSyncProtocol.ManifestEntry(
                    id = badHash,
                    kind = AmbientSyncProtocol.Kind.PHOTO,
                    name = "bad.jpg",
                    size = 2,
                    sha256 = badHash,
                )
            val badSession = store.beginStaging(AmbientSyncProtocol.Manifest(listOf(badEntry)))
            try {
                var sent = 0
                badSession.receiveEntry(badEntry) { buffer, max ->
                    // send wrong bytes so hash fails
                    val data = byteArrayOf(9, 9)
                    val n = minOf(max, data.size - sent)
                    if (n <= 0) return@receiveEntry -1
                    System.arraycopy(data, sent, buffer, 0, n)
                    sent += n
                    n
                }
                fail("expected hash mismatch")
            } catch (_: AmbientSyncProtocol.ProtocolError) {
                // expected
            }
            assertTrue(store.hasSyncedMedia())
            assertTrue(java.io.File(tmp, "ambient/current/manifest.json").isFile)

            store.clearCurrent()
            assertFalse(store.hasSyncedMedia())
            // Empty clear commits empty current (transactional), not a bare delete.
            assertTrue(java.io.File(tmp, "ambient/current/manifest.json").isFile)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun mediaStore_recoversPreviousWhenCurrentMissing() {
        val tmp = Files.createTempDirectory("ambient-recover-").toFile()
        try {
            val store = AmbientMediaStore(tmp)
            val payload = byteArrayOf(5, 6, 7, 8)
            val hash = sha256Of(payload)
            val entry =
                AmbientSyncProtocol.ManifestEntry(
                    id = hash,
                    kind = AmbientSyncProtocol.Kind.PHOTO,
                    name = "r.jpg",
                    size = payload.size.toLong(),
                    sha256 = hash,
                )
            val session = store.beginStaging(AmbientSyncProtocol.Manifest(listOf(entry)))
            var offset = 0
            session.receiveEntry(entry) { buffer, max ->
                val n = minOf(max, payload.size - offset)
                if (n <= 0) return@receiveEntry -1
                System.arraycopy(payload, offset, buffer, 0, n)
                offset += n
                n
            }
            session.commit()
            assertTrue(store.hasSyncedMedia())

            // Simulate crash after current → previous-* rename, before staging promotion.
            val root = java.io.File(tmp, "ambient")
            val current = java.io.File(root, "current")
            val previous = java.io.File(root, "previous-crash-sim")
            assertTrue(current.renameTo(previous))
            assertFalse(java.io.File(root, "current").exists())
            assertTrue(java.io.File(previous, "manifest.json").isFile)

            // New store instance must restore previous as current, then clean leftovers.
            val recovered = AmbientMediaStore(tmp)
            assertTrue(recovered.hasSyncedMedia())
            assertTrue(java.io.File(root, "current/manifest.json").isFile)
            val leftoverPrevious =
                root.listFiles()?.filter {
                    it.isDirectory && it.name.startsWith("previous")
                }.orEmpty()
            assertTrue(
                "expected no leftover previous dirs after recovery, got $leftoverPrevious",
                leftoverPrevious.isEmpty(),
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun sha256Of(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return AmbientSyncProtocol.bytesToHex(md.digest(bytes))
    }
}
