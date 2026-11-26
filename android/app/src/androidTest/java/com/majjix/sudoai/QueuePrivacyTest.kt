package com.majjix.sudoai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueuePrivacyTest {
    private fun fixture(test: (Store, String, MutableList<File>) -> Unit) {
        val store = Store.get(InstrumentationRegistry.getInstrumentation().targetContext)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        val owner = uuid()
        store.vault.put("owner", owner)
        store.vault.put("token", "synthetic-offline-token")
        val dirs = mutableListOf<File>()
        try {
            test(store, owner, dirs)
        } finally {
            dirs.forEach { it.deleteRecursively() }
            store.vault.clear()
        }
    }

    @Test
    fun acknowledgedPayloadsArePurgedAndHistoryIsAccountScoped() = fixture { store, owner, dirs ->
        val dir = store.create("Private synthetic recording").also(dirs::add)
        store.chunk(dir, "mic", 0, 100, AudioBytes.wav(ByteArray(3200)))
        store.moment(dir, 9000, "Synthetic screen fact", true)
        store.close(dir, 100)
        assertEquals(9000, store.manifest(dir).getLong("duration_ms"))
        store.sync { _, _, _, _, _ -> "{}" }
        assertTrue(store.manifest(dir).getBoolean("uploaded"))
        assertFalse(dir.listFiles()!!.any { it.extension in listOf("bin", "job") })
        val ack = dir.listFiles()!!.first { it.extension == "ack" }
        val leftover = File(dir, JSONObject(ack.readText()).getString("body"))
        leftover.writeBytes(byteArrayOf(1, 2, 3))
        val pending = store.create("Pending synthetic recording").also(dirs::add)
        val pendingBody = pending.listFiles()!!.single { it.extension == "bin" }
        store.sweepAcknowledged()
        assertFalse("Acknowledged leftovers removed", leftover.exists())
        assertTrue("Pending bytes retained", pendingBody.exists())
        assertTrue(dir in store.visibleSessions())
        store.vault.put("owner", "other-owner")
        assertFalse(dir in store.visibleSessions())
        assertNull(store.meetingUrl(dir))
        store.vault.clear()
        assertTrue(store.visibleSessions().isEmpty())
        store.base = "https://another.invalid"
        store.vault.put("owner", owner)
        assertFalse(dir in store.visibleSessions())
    }

    @Test
    fun interruptedRecoveryIncludesAcknowledgedScreenAndPendingAudioBounds() =
        fixture { store, _, dirs ->
            val dir = store.create("Synthetic interrupted capture").also(dirs::add)
            store.moment(dir, 8500, "Screen-only invented detail", true)
            store.gap(dir, "screen", 9000, 10000, "Synthetic lock gap")
            store.sync { _, _, _, _, _ -> "{}" }
            assertFalse(dir.listFiles()!!.any { it.extension == "bin" })
            File(dir, "partial-mic.pcm").writeBytes(ByteArray(3200))
            atomic(
                File(dir, "partial-mic.json"),
                JSONObject().put("start_ms", 1000).put("seq", 0).put("track", "mic").toString(),
            )
            store.recoverInterrupted(dir)
            val m = store.manifest(dir)
            assertTrue(m.getBoolean("closed"))
            assertEquals(10000, m.getLong("duration_ms"))
            assertEquals(1, m.getInt("count_mic"))
            assertFalse(File(dir, "partial-mic.pcm").exists())
            var finalizedDuration = 0L
            store.sync { _, path, bytes, _, _ ->
                if (path.endsWith("/finalize"))
                    finalizedDuration = JSONObject(String(bytes!!)).getLong("duration_ms")
                "{}"
            }
            assertEquals(10000, finalizedDuration)
        }

    @Test
    fun serverDeletionDoesNotBlockAnotherSessionAndFailuresRetainPendingBytes() =
        fixture { store, _, dirs ->
            val deleted = store.create("Deleted synthetic session").also(dirs::add)
            val healthy = store.create("Healthy synthetic session").also(dirs::add)
            store.close(deleted, 0)
            store.close(healthy, 0)
            store.sync { _, path, _, _, _ ->
                if (path.contains(deleted.name)) throw ServerResponseException(410)
                "{}"
            }
            assertTrue(store.manifest(deleted).getBoolean("deleted"))
            assertEquals(listOf("session.json"), deleted.listFiles()!!.map { it.name })
            assertTrue(store.manifest(healthy).getBoolean("uploaded"))
            val failed = store.create("Retry synthetic session").also(dirs::add)
            assertThrows(IllegalStateException::class.java) {
                store.sync { _, _, _, _, _ -> throw ServerResponseException(503) }
            }
            assertTrue(failed.listFiles()!!.any { it.extension == "job" })
            assertTrue(failed.listFiles()!!.any { it.extension == "bin" })
        }

    @Test
    fun duplicateImportOpensCanonicalMeetingAndRemovesSourceCopy() = fixture { store, owner, dirs ->
        val dir = File(store.root, uuid()).apply { mkdirs() }.also(dirs::add)
        File(dir, "source-import")
            .writeText("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nInvented words\n")
        store.queueImport(dir, "synthetic.vtt", "text/vtt")
        val canonical = uuid()
        store.sync { _, _, _, _, _ ->
            JSONObject().put("id", canonical).put("duplicate", true).toString()
        }
        assertEquals("${store.base}/sessions/$canonical", store.meetingUrl(dir))
        assertTrue(store.manifest(dir).getBoolean("uploaded"))
        assertFalse(File(dir, "source-import").exists())
        assertFalse(dir.listFiles()!!.any { it.extension == "bin" })
    }

    @Test
    fun interruptedImportQueueCommitRecoversBeforeAcknowledgment() = fixture { store, owner, dirs ->
        val dir = File(store.root, uuid()).apply { mkdirs() }.also(dirs::add)
        File(dir, "source-import").writeText("Invented recovery payload")
        atomic(
            File(dir, "session.json"),
            JSONObject()
                .put("owner", owner)
                .put("server", store.base)
                .put("title", "Interrupted import")
                .put("is_import", true)
                .put("closed", true)
                .put("import_name", "synthetic.vtt")
                .put("import_mime", "text/vtt")
                .put("import_boundary", "recovered-boundary")
                .toString(),
        )
        val canonical = uuid()
        var sent = false
        store.sync { _, path, bytes, mime, _ ->
            assertEquals("/api/v1/import", path)
            assertTrue(String(bytes!!).contains("Invented recovery payload"))
            assertTrue(mime.contains("recovered-boundary"))
            sent = true
            JSONObject().put("id", canonical).toString()
        }
        assertTrue(sent)
        assertTrue(store.manifest(dir).getBoolean("uploaded"))
        assertEquals("${store.base}/sessions/$canonical", store.meetingUrl(dir))
        assertFalse(File(dir, "source-import").exists())
    }
}
