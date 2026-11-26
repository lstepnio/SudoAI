package com.majjix.sudoai

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// The input starts before submission. Rejected submission must still release it.
internal fun captureWorker(
    executor: Executor,
    start: () -> Unit,
    release: () -> Unit,
    read: () -> Unit,
) {
    val released = AtomicBoolean(false)
    fun releaseOnce() {
        if (released.compareAndSet(false, true)) release()
    }
    try {
        start()
        executor.execute {
            try {
                read()
            } finally {
                releaseOnce()
            }
        }
    } catch (error: Exception) {
        releaseOnce()
        throw error
    }
}
