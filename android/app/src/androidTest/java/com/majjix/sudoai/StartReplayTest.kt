package com.majjix.sudoai

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartReplayTest {
    @Test
    fun consumedStartIntentCannotCreateASecondRecordingAfterStop() {
        check(android.os.Build.FINGERPRINT.startsWith("google/sdk")) { "Disposable emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store.get(context)
        assertFalse(CaptureService.active)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        val owner = uuid()
        store.vault.put("owner", owner)
        fun sessions() = store.sessions().filter { store.manifest(it).optString("owner") == owner }
        fun waitUntil(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 8000
            while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
            assertTrue(condition())
        }
        for (permission in listOf("BLUETOOTH_CONNECT", "BLUETOOTH_SCAN")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "pm grant ${context.packageName} android.permission.$permission"
                    )
                )
                .use { it.readBytes() }
        }
        val activity =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        val request =
            Intent(context, CaptureService::class.java)
                .setAction("start")
                .putExtra(
                    CaptureStartAuthorization.EXTRA,
                    CaptureStartAuthorization.process.issue(),
                )
                .putExtra("mic", false)
                .putExtra("omi", true)
                .putExtra("screen", false)
                .putExtra("playback", false)
                .putExtra("title", "Invented no-audio replay regression")
        fun stop() =
            context.startService(Intent(context, CaptureService::class.java).setAction("stop"))
        try {
            context.startForegroundService(request)
            waitUntil { CaptureService.active }
            stop()
            waitUntil { !CaptureService.active }
            assertEquals(1, sessions().size)
            // Deliver the identical request after teardown, when the service is free to start.
            context.startForegroundService(request)
            // Stay alive beyond Android's foreground-promotion deadline as well.
            SystemClock.sleep(6500)
            instrumentation.waitForIdleSync()
            assertEquals("Consumed Start intent created another recording", 1, sessions().size)
            assertFalse("Replayed Start reactivated capture", CaptureService.active)
        } finally {
            if (CaptureService.active) {
                stop()
                waitUntil { !CaptureService.active }
            }
            instrumentation.runOnMainSync { activity.finish() }
            waitUntil { activity.isDestroyed }
            sessions().forEach { it.deleteRecursively() }
            store.vault.clear()
        }
    }
}
