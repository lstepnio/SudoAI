package com.majjix.sudoai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCaptureTest {
    @Test
    fun keystoreAndDurableChunkReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store.get(context)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        store.vault.put("owner", "synthetic-test")
        assertEquals("synthetic-test", store.owner)
        val prefs = context.getSharedPreferences("credentials", 0).all.toString()
        assertFalse(prefs.contains("synthetic-test"))
        val dir = store.create("Synthetic WAV recovery")
        try {
            var clock = 1100L
            val writer = PcmChunks(store, dir, "mic") { clock }
            writer.append(ByteArray(3200))
            clock = 1100
            assertEquals(3200, File(dir, "partial-mic.pcm").length().toInt())
            val partial = JSONObject(File(dir, "partial-mic.json").readText())
            writer.flush()
            val job =
                dir.listFiles()!!.single { it.name.startsWith("200-mic-") && it.extension == "job" }
            val before = job.readText()
            store.chunk(
                dir,
                "mic",
                1000,
                1100,
                AudioBytes.wav(ByteArray(3200)),
                partial.getInt("seq"),
            )
            assertEquals(before, job.readText())
            assertEquals(1, store.nextSequence(dir, "mic"))
            store.vault.put("owner", "another-account")
            store.vault.put("token", "synthetic-test-token")
            store.sync() // The original account's queue must be held without connecting to the
            // unavailable server.
            assertTrue(job.exists())
            store.close(dir, 1100)
            assertEquals(1, store.manifest(dir).getInt("count_mic"))
        } finally {
            dir.deleteRecursively()
            store.vault.clear()
        }
    }

    @Test
    fun foregroundControlsWithoutMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store.get(context)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        store.vault.put("owner", "synthetic-service-test")
        for (permission in
            listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
            )) instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} $permission")
            .close()
        val activity =
            instrumentation.startActivitySync(
                android.content
                    .Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        instrumentation.waitForIdleSync()
        context.startForegroundService(
            android.content
                .Intent(context, CaptureService::class.java)
                .setAction("start")
                .putExtra(
                    CaptureStartAuthorization.EXTRA,
                    CaptureStartAuthorization.process.issue(),
                )
                .putExtra("mic", false)
                .putExtra("omi", true)
                .putExtra("title", "Synthetic service controls")
        )
        repeat(50) { if (!CaptureService.active) android.os.SystemClock.sleep(100) }
        assertTrue("Foreground service started", CaptureService.active)
        val dir = java.io.File(store.root, CaptureService.activeId!!)
        try {
            context.startService(
                android.content.Intent(context, CaptureService::class.java).setAction("bookmark")
            )
            context.startService(
                android.content.Intent(context, CaptureService::class.java).setAction("pause")
            )
            android.os.SystemClock.sleep(250)
            assertTrue(CaptureService.snapshot.startsWith("Paused"))
            context.startService(
                android.content.Intent(context, CaptureService::class.java).setAction("stop")
            )
            repeat(50) { if (CaptureService.active) android.os.SystemClock.sleep(100) }
            assertFalse(CaptureService.active)
            assertTrue(store.manifest(dir).getBoolean("closed"))
            assertTrue(
                dir.listFiles()!!.any {
                    it.extension == "job" &&
                        JSONObject(it.readText()).getString("path").contains("/moments/")
                }
            )
            assertFalse(
                "No microphone data captured",
                dir.listFiles()!!.any { it.name.startsWith("200-mic-") },
            )
        } finally {
            // Destroy the real Compose view before removing files it is rendering.
            instrumentation.runOnMainSync { activity.finish() }
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            while (
                !activity.isDestroyed && android.os.SystemClock.uptimeMillis() < deadline
            ) android.os.SystemClock.sleep(20)
            assertTrue("Fixture activity destroyed before deleting its files", activity.isDestroyed)
            dir.deleteRecursively()
            store.vault.clear()
        }
    }

    @Test
    fun nativeOpusSilenceDecodesTo16kMono() {
        // RFC 6716-compatible 20ms Opus silence packet, no real audio or microphone access.
        OpusDecoder(21).use { decoder ->
            var samples = 0
            repeat(8) {
                samples +=
                    decoder.decode(byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())).size /
                        2
            }
            assertTrue("Native decoder produced $samples samples", samples >= 7 * 320)
            assertTrue("Native decoder output must be 16kHz", samples <= 8 * 320)
        }
    }
}
