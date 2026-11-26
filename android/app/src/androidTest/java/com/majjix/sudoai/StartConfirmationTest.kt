package com.majjix.sudoai

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartConfirmationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun firstStartActivationAndCancelNeverCreateCapture() {
        check(android.os.Build.FINGERPRINT.startsWith("google/sdk")) { "Disposable emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store.get(context)
        assertFalse(CaptureService.active)
        store.vault.clear()
        store.base = "https://127.0.0.1:1"
        val owner = uuid()
        store.vault.put("owner", owner)
        val activity =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        try {
            compose.onNodeWithText("Start session").performScrollTo().performClick()
            compose.onNodeWithText("Start recording?").assertIsDisplayed()
            assertFalse(CaptureService.active)
            assertTrue(store.sessions().none { store.manifest(it).optString("owner") == owner })
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Start recording?").assertDoesNotExist()
            assertFalse(CaptureService.active)
            assertTrue(store.sessions().none { store.manifest(it).optString("owner") == owner })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            store.vault.clear()
        }
    }
}
