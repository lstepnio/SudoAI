package com.majjix.sudoai

import java.util.UUID

// Process-local, short-lived authority for exactly one confirmed UI request.
// An Intent alone must never authorize recording, including after service recreation.
internal class CaptureStartAuthorization(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) {
    private var ticket: String? = null
    private var issuedAt = 0L

    @Synchronized
    fun issue(): String =
        nonce().also {
            ticket = it
            issuedAt = clock()
        }

    @Synchronized
    fun consume(value: String?, canStart: Boolean): Boolean {
        val elapsed = clock() - issuedAt
        val allowed = canStart && value != null && value == ticket && elapsed in 0..15_000
        ticket = null
        return allowed
    }

    @Synchronized
    fun cancel() {
        ticket = null
    }

    companion object {
        const val EXTRA = "confirmed_start_ticket"
        val process = CaptureStartAuthorization()
    }
}
