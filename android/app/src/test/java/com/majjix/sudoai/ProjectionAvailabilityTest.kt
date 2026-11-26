package com.majjix.sudoai

import org.junit.Assert.*
import org.junit.Test

class ProjectionAvailabilityTest {
    @Test
    fun revokedConsentCannotBeRestoredByUnlockOrResume() {
        // Mirrors user actions after revocation, including late OCR and audio callbacks:
        // every route to successful source status must consult this same policy.
        for (locked in listOf(true, false)) {
            for (paused in listOf(true, false)) {
                assertEquals(PROJECTION_REVOKED, projectionInterruption(false, locked, paused))
            }
        }
    }

    @Test
    fun liveConsentCanResumeAfterTemporaryLockOrPause() {
        assertEquals("Screen locked; no screen moments", projectionInterruption(true, true, false))
        assertEquals("Screen locked; no screen moments", projectionInterruption(true, true, true))
        assertEquals("Paused by user", projectionInterruption(true, false, true))
        assertNull(projectionInterruption(true, false, false))
    }
}
