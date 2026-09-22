package com.filestech.pass_tech.core.biometric

import javax.crypto.Cipher

/**
 * The one biometric key, `pt_bio`, in the secure hardware: AES-GCM, usable only right after a strong
 * biometric, and destroyed by the system when a fingerprint is enrolled.
 *
 * Every function may throw [com.filestech.pass_tech.core.vault.KeystoreUnavailableException].
 */
interface BiometricKeys {

    /** Replaces the key with a new one, and returns a cipher to seal with it once authenticated. */
    fun cipherToSeal(): Cipher

    /** A cipher to open what was sealed under [nonce]; `null` if the key is gone or invalidated. */
    fun cipherToOpen(nonce: ByteArray): Cipher?

    fun delete()
}
