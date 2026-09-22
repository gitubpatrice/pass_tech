package com.filestech.pass_tech.core.vault

import javax.crypto.Cipher

/**
 * Biometric unlock, as the vault sees it (design v2 §9, v2.1 §3): one secure-hardware key that works
 * only after a strong biometric, and seals the key of ONE vault together with that vault's generation.
 *
 * - Arming replaces whatever was armed: [cipherToArm] disarms first.
 * - [purge] disarms, durably, before returning. The vault purges BEFORE a decoy is published (otherwise
 *   a crash could leave a decoy in place while a fingerprint still opens the real vault, the 2.7.0
 *   flaw), at every deletion, and when the password of the armed vault changes. Purging is always
 *   safe: the worst outcome is that the owner enables it again.
 *
 * Every function may throw [KeystoreUnavailableException]: the secure hardware did not answer.
 */
interface BiometricBinding {

    /** What arming or unlocking starts with: the cipher the system prompt must authenticate. */
    sealed interface Start {
        class Ready(val cipher: Cipher) : Start

        data object NotArmed : Start

        /** The key is gone: a fingerprint was enrolled, or every one removed. Now disarmed. */
        data object Invalidated : Start
    }

    /** What [arm] sealed. The caller wipes [key]. */
    class Armed(val generation: String, val key: ByteArray)

    /** The generation of the vault a fingerprint opens; `null` if nothing is armed. */
    fun armedGeneration(): String?

    /** Disarms, then creates a new key and returns a cipher to seal with it, once authenticated. */
    fun cipherToArm(): Cipher

    /** Seals [key] and [generation] with a cipher from [cipherToArm] that the prompt authenticated. */
    fun arm(generation: String, key: ByteArray, cipher: Cipher)

    /** A cipher to open what is armed, for the prompt; never [Start.Ready] when nothing is armed. */
    fun cipherToUnlock(): Start

    /** What [arm] sealed, with a cipher from [cipherToUnlock] that the prompt authenticated; `null` if it does not open. */
    fun open(cipher: Cipher): Armed?

    fun purge()

    companion object {
        /** Nothing is ever armed, and nothing can be: for the tests that do not involve biometrics. */
        val NONE: BiometricBinding = object : BiometricBinding {
            override fun armedGeneration(): String? = null

            override fun cipherToArm(): Cipher = throw UnsupportedOperationException("no biometrics here")

            override fun arm(generation: String, key: ByteArray, cipher: Cipher) = throw UnsupportedOperationException("no biometrics here")

            override fun cipherToUnlock(): Start = Start.NotArmed

            override fun open(cipher: Cipher): Armed? = null

            override fun purge() = Unit
        }
    }
}
