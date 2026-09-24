package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.biometric.BiometricKeys
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [BiometricKeys] in memory, with no prompt: a cipher it hands out works as if the biometric had been
 * seen. [enrollFingerprint] plays the system destroying the key on a new enrolment.
 */
class FakeBiometricKeys : BiometricKeys {

    private var key: SecretKey? = null

    /** Every call throws, as a Keystore that does not answer. */
    var unavailable = false

    /** How many keys were created: arming always starts from a new one. */
    var created = 0
        private set

    val hasKey: Boolean get() = key != null

    fun enrollFingerprint() {
        key = null
    }

    override fun cipherToSeal(): Cipher {
        answering()
        val fresh = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
        key = fresh
        created++
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fresh) }
    }

    override fun cipherToOpen(nonce: ByteArray): Cipher? {
        answering()
        return key?.let { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(TAG_BITS, nonce)) } }
    }

    override fun delete() {
        answering()
        key = null
    }

    private fun answering() {
        if (unavailable) throw KeystoreUnavailableException()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
