package com.majjix.sudoai

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StopRestartTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
        assertTrue(message, condition())
    }

    private fun openActivity(): Activity =
        instrumentation
            .startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            .also { instrumentation.waitForIdleSync() }

    private fun start(title: String): Intent {
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
                .putExtra("title", title)
        context.startForegroundService(request)
        return request
    }

    private fun stop() =
        context.startService(Intent(context, CaptureService::class.java).setAction("stop"))

    @Test
    fun stoppingInstanceRejectsLateStartAndFreshInstanceAcceptsExplicitStart() {
        check(
            android.os.Build.MODEL.contains("sdk", ignoreCase = true) ||
                android.os.Build.FINGERPRINT.startsWith("google/sdk")
        ) {
            "Disposable emulator only"
        }
        val store = Store.get(context)
        assertFalse("No capture existed before synthetic test", CaptureService.active)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        val owner = uuid()
        store.vault.put("owner", owner)
        // No bearer token is installed, so automatic retries cannot contact any service.
        for (permission in listOf("BLUETOOTH_CONNECT", "BLUETOOTH_SCAN")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "pm grant ${context.packageName} android.permission.$permission"
                    )
                )
                .use { it.readBytes() }
        }
        var activity: Activity? = null
        fun ownSessions(): List<File> =
            store.sessions().filter { store.manifest(it).optString("owner") == owner }
        try {
            activity = openActivity()
            val firstRequest = start("Synthetic first Omi-only session")
            await("First foreground capture started") {
                CaptureService.active && CaptureService.activeId != null
            }
            val firstId = CaptureService.activeId!!
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            activity = null
            // Keep the real finalizer blocked in Store, with no Compose UI querying its monitor.
            synchronized(store) {
                stop()
                start("Synthetic late start must be ignored")
                await("Old instance reached its stopping phase") {
                    CaptureService.snapshot.startsWith("Saving")
                }
                SystemClock.sleep(150)
                val mainProgress = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    mainProgress.countDown()
                }
                assertTrue(
                    "Late Start did not block in another session creation",
                    mainProgress.await(2, java.util.concurrent.TimeUnit.SECONDS),
                )
                assertTrue("Old instance remains alive while finalizing", CaptureService.active)
                assertEquals(firstId, CaptureService.activeId)
                assertEquals("Late start did not create a second session", 1, ownSessions().size)
            }
            await("Old stopping instance was destroyed") { !CaptureService.active }
            assertTrue(store.manifest(File(store.root, firstId)).getBoolean("closed"))

            // A service can be recreated by a stale Intent. Replaying the original request
            // must not recreate its recording, even though no capture is currently active.
            val rejectedBefore = CaptureService.rejectedStarts
            context.startForegroundService(firstRequest)
            await("Replayed Start reached the actual service and was rejected") {
                CaptureService.rejectedStarts > rejectedBefore
            }
            assertFalse("Replayed request did not start capture", CaptureService.active)
            assertEquals("Replayed request did not create another directory", 1, ownSessions().size)

            activity = openActivity()
            start("Synthetic fresh explicit Omi-only session")
            await("Fresh instance accepted explicit Start") {
                CaptureService.active && CaptureService.activeId != null
            }
            assertNotEquals(firstId, CaptureService.activeId)
            assertEquals(2, ownSessions().size)

            stop()
            await("Second instance stopped and was destroyed") { !CaptureService.active }
            assertEquals("Only the two explicit sessions exist", 2, ownSessions().size)
            for (dir in ownSessions()) {
                assertTrue(store.manifest(dir).getBoolean("closed"))
                assertFalse(
                    "No audio or BLE connection was used",
                    dir.listFiles()!!.any {
                        it.name.startsWith("partial-") || it.name.startsWith("200-")
                    },
                )
            }
        } finally {
            if (CaptureService.active) {
                stop()
                await("Synthetic capture cleaned up") { !CaptureService.active }
            }
            activity?.let { current ->
                instrumentation.runOnMainSync { current.finish() }
                await("Synthetic activity destroyed before removing fixtures") {
                    current.isDestroyed
                }
            }
            ownSessions().forEach { it.deleteRecursively() }
            store.vault.clear()
        }
    }
}
