package com.filestech.pass_tech.core.vault

/**
 * What the vault needs from biometric unlock: to disarm it, durably, before any structural change.
 *
 * Design v2 §9 and v2.1: biometrics are purged BEFORE a decoy is published (otherwise a crash could
 * leave a decoy in place while a fingerprint still opens the real vault, the 2.7.0 flaw), and at
 * every deletion and every password change. Purging all of it is always safe: the worst outcome is
 * that the owner re-enables it.
 */
fun interface BiometricBinding {
    /** Disarms biometric unlock, durably, before returning. */
    fun purge()

    companion object {
        /** Until the biometric feature lands: nothing is ever armed, so there is nothing to purge. */
        val NONE = BiometricBinding {}
    }
}
