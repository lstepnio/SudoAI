package com.majjix.sudoai

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadPrivacyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root
        get() = temporary.root

    private val marker
        get() = File(root, ".upload-hold-v1.complete")

    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun session(name: String, closed: Boolean = true, uploaded: Boolean = false): File {
        val dir = File(root, name).apply { mkdirs() }
        write(
            File(dir, "session.json"),
            JSONObject()
                .put("owner", "fixture-owner")
                .put("server", "https://fixture.test")
                .put("closed", closed)
                .put("uploaded", uploaded)
                .toString(),
        )
        File(dir, "pending.bin").writeBytes(byteArrayOf(1, 4, 9, 16))
        return dir
    }

    private fun manifest(dir: File) = JSONObject(File(dir, "session.json").readText())

    private fun policy(writer: (File, String) -> Unit = ::write) = UploadPrivacy(root, writer)

    private fun uploadable(p: UploadPrivacy) =
        p.uploadableSessions("fixture-owner", "https://fixture.test")

    @Test
    fun partialManifestFailureCannotSendAndRestartHoldsEverySession() {
        val a = session("a")
        val b = session("b")
        var writes = 0
        val failing = policy { file, text ->
            if (++writes == 2) error("Synthetic disk failure")
            write(file, text)
        }
        val sent = mutableListOf<File>()
        assertThrows(IllegalStateException::class.java) { uploadable(failing).forEach(sent::add) }
        assertTrue(sent.isEmpty())
        assertFalse(marker.exists())
        assertTrue(manifest(a).getBoolean("upload_held"))
        assertFalse(manifest(b).optBoolean("upload_held"))
        assertTrue(uploadable(policy()).isEmpty())
        assertTrue(marker.exists())
        assertTrue(manifest(b).getBoolean("upload_held"))
        for (dir in listOf(a, b)) assertArrayEquals(
            byteArrayOf(1, 4, 9, 16),
            File(dir, "pending.bin").readBytes(),
        )
    }

    @Test
    fun markerFailureRemainsFailClosedAndRetryIsIdempotent() {
        val dir = session("old")
        val failing = policy { file, text ->
            if (file == marker) error("Synthetic marker fsync failure")
            write(file, text)
        }
        assertThrows(IllegalStateException::class.java) { uploadable(failing) }
        assertFalse(marker.exists())
        assertTrue(manifest(dir).getBoolean("upload_held"))
        assertTrue(uploadable(policy()).isEmpty())
    }

    @Test
    fun onlyExplicitlyResumedClosedBoundSessionBecomesUploadable() {
        val intended = session("intended")
        val other = session("other")
        val open = session("open", closed = false)
        val p = policy()
        p.migrate()
        assertThrows(IllegalArgumentException::class.java) {
            p.resume(open, "fixture-owner", "https://fixture.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.resume(intended, "another-owner", "https://fixture.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.resume(intended, "fixture-owner", "https://other.test")
        }
        p.resume(intended, "fixture-owner", "https://fixture.test")
        assertEquals(listOf(intended), uploadable(p))
        assertTrue(manifest(other).getBoolean("upload_held"))
        assertTrue(File(other, "pending.bin").exists())
        // A new process does not hold this session again after the durable marker exists.
        assertEquals(listOf(intended), uploadable(policy()))
    }

    @Test
    fun newSessionsKeepNormalUploadsAfterUpgradeWithoutReleasingOldOnes() {
        val old = session("old")
        val uploaded = session("uploaded", uploaded = true)
        val p = policy()
        p.migrate()
        val fresh = session("new")
        assertEquals(listOf(fresh), uploadable(p))
        assertTrue(manifest(old).getBoolean("upload_held"))
        assertFalse(manifest(uploaded).optBoolean("upload_held"))
    }

    @Test
    fun explicitHoldOnNewClosedSessionSurvivesRestartAndPreservesExcludedBytes() {
        val excluded = session("excluded")
        val p = policy()
        p.migrate()
        val fresh = session("fresh")
        val other = session("another-new")
        val excludedManifest = File(excluded, "session.json").readBytes()
        val freshBytes = File(fresh, "pending.bin").readBytes()
        assertEquals(listOf(other, fresh), uploadable(p))
        p.hold(fresh, "fixture-owner", "https://fixture.test")
        assertEquals(listOf(other), uploadable(policy()))
        assertTrue(manifest(fresh).getBoolean("upload_held"))
        assertArrayEquals(freshBytes, File(fresh, "pending.bin").readBytes())
        assertArrayEquals(excludedManifest, File(excluded, "session.json").readBytes())
        assertArrayEquals(byteArrayOf(1, 4, 9, 16), File(excluded, "pending.bin").readBytes())
        p.resume(fresh, "fixture-owner", "https://fixture.test")
        assertEquals(listOf(other, fresh), uploadable(p))
        assertTrue(manifest(excluded).getBoolean("upload_held"))
    }

    @Test
    fun holdRejectsOpenUploadedDeletedAndForeignSessions() {
        val p = policy()
        p.migrate()
        val open = session("open", closed = false)
        val uploaded = session("uploaded", uploaded = true)
        val deleted = session("deleted")
        write(File(deleted, "session.json"), manifest(deleted).put("deleted", true).toString())
        val pending = session("pending")
        for (dir in listOf(open, uploaded, deleted)) {
            assertThrows(IllegalArgumentException::class.java) {
                p.hold(dir, "fixture-owner", "https://fixture.test")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.hold(pending, "another-owner", "https://fixture.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.hold(pending, "fixture-owner", "https://other.test")
        }
        assertFalse(manifest(pending).optBoolean("upload_held"))
    }

    @Test
    fun malformedQueueMetadataOrMarkerNeverPermitsSending() {
        val bad = session("bad")
        File(bad, "session.json").writeText("invalid-json")
        assertThrows(Exception::class.java) { uploadable(policy()) }
        assertFalse(marker.exists())
        assertTrue(File(bad, "pending.bin").exists())
        marker.writeText("incomplete")
        assertThrows(IllegalStateException::class.java) { uploadable(policy()) }
    }
}
