package com.majjix.sudoai

import org.junit.Assert.*
import org.junit.Test

class CaptureStartRequestTest {
    @Test
    fun duplicateCallbacksCanDispatchOnlyOneStart() {
        val request = CaptureStartRequest()
        assertTrue(request.begin())
        assertFalse(request.begin())
        assertTrue(request.consume(canStart = true))
        assertFalse(request.consume(canStart = true))
    }

    @Test
    fun stopOrDenialCancelsLatePermissionResult() {
        val request = CaptureStartRequest()
        request.begin()
        request.cancel()
        assertFalse(request.consume(canStart = true))
        assertTrue(request.begin())
        assertTrue(request.consume(canStart = true))
    }

    @Test
    fun recreationNeverInheritsAuthorityFromPreviousActivity() {
        val previous = CaptureStartRequest()
        previous.begin()
        val recreated = CaptureStartRequest()
        assertFalse(recreated.pending)
        assertFalse(recreated.consume(canStart = true))
    }

    @Test
    fun runningOrStoppingServiceRejectsAndConsumesTheRequest() {
        val request = CaptureStartRequest()
        request.begin()
        assertFalse(request.consume(canStart = false))
        assertFalse(request.consume(canStart = true))
    }
}
