package com.majjix.sudoai

// Authority belongs to one live Activity instance and one explicit Start gesture.
// Never serialize this gate: recreation cancels any unconsumed permission result.
internal class CaptureStartRequest {
    var pending = false
        private set

    fun begin(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun cancel() {
        pending = false
    }

    fun consume(canStart: Boolean): Boolean {
        val allowed = pending && canStart
        pending = false
        return allowed
    }
}
