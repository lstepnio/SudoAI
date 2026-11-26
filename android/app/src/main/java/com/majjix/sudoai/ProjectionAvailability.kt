package com.majjix.sudoai

internal const val PROJECTION_REVOKED =
    "Screen sharing revoked; stop and start to grant consent again"

// Consent loss is terminal for this capture. Unlocking and resuming cannot grant it again.
internal fun projectionInterruption(
    consentLive: Boolean,
    locked: Boolean,
    paused: Boolean,
): String? =
    when {
        !consentLive -> PROJECTION_REVOKED
        locked -> "Screen locked; no screen moments"
        paused -> "Paused by user"
        else -> null
    }
