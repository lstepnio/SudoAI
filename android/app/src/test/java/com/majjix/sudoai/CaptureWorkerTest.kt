package com.majjix.sudoai

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class CaptureWorkerTest {
    @Test
    fun stoppedExecutorCannotLeaveStartedInputOpen() {
        var open = false
        var reads = 0
        try {
            captureWorker(
                Executor { throw RejectedExecutionException() },
                { open = true },
                { open = false },
                { reads++ },
            )
            fail("Submission must fail")
        } catch (_: RejectedExecutionException) {}
        assertFalse(open)
        assertEquals(0, reads)
    }

    @Test
    fun startupAndReadFailuresBothReleaseExactlyOnce() {
        for (failOnStart in listOf(true, false)) {
            var releases = 0
            try {
                captureWorker(
                    Executor { it.run() },
                    { if (failOnStart) error("start") },
                    { releases++ },
                    { error("read") },
                )
                fail("Failure must propagate")
            } catch (_: IllegalStateException) {}
            assertEquals(1, releases)
        }
    }
}
