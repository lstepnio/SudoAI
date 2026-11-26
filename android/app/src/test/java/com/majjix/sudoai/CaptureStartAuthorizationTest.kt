package com.majjix.sudoai

import org.junit.Assert.*
import org.junit.Test

class CaptureStartAuthorizationTest {
    @Test
    fun missingUnknownAndReplayedIntentsCannotAuthorizeCapture() {
        val gate = CaptureStartAuthorization()
        assertFalse(gate.consume(null, true))
        assertFalse(gate.consume("unrequested", true))
        val ticket = gate.issue()
        assertTrue(gate.consume(ticket, true))
        assertFalse(gate.consume(ticket, true))
    }

    @Test
    fun stopAndBusyServiceInvalidateQueuedRequests() {
        val gate = CaptureStartAuthorization()
        val stopped = gate.issue()
        gate.cancel()
        assertFalse(gate.consume(stopped, true))
        val busy = gate.issue()
        assertFalse(gate.consume(busy, false))
        assertFalse(gate.consume(busy, true))
    }

    @Test
    fun supersededAndPreviousProcessRequestsAreRejected() {
        var next = 0
        val gate = CaptureStartAuthorization(nonce = { (++next).toString() })
        val old = gate.issue()
        val fresh = gate.issue()
        assertFalse(gate.consume(old, true))
        // A mismatched request fails closed, including the pending newer request.
        assertFalse(gate.consume(fresh, true))
        val previousProcess = gate.issue()
        assertFalse(CaptureStartAuthorization().consume(previousProcess, true))
    }

    @Test
    fun expiredOrBackwardsClockRequestsAreRejected() {
        var time = 100L
        val gate = CaptureStartAuthorization(clock = { time })
        val expired = gate.issue()
        time += 15_001
        assertFalse(gate.consume(expired, true))
        val backwards = gate.issue()
        time--
        assertFalse(gate.consume(backwards, true))
        val fresh = gate.issue()
        time += 15_000
        assertTrue(gate.consume(fresh, true))
    }
}
