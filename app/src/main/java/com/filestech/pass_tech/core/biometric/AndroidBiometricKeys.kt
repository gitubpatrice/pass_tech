package com.filestech.pass_tech.core.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [BiometricKeys] on the AndroidKeyStore (design v2 §9): authentication at every use, by a strong
 * biometric only (never the lock-screen code), and invalidated by a new enrolment.
 *
 * In the TEE, not StrongBox: it guards one unwrap per unlock, and the vault key it releases is only as
 * safe as the phone that runs the app.
 */
class AndroidBiometricKeys(private val alias: String = ALIAS) : BiometricKeys {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun cipherToSeal(): Cipher = unavailableOnFailure {
        deleteEntry()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    // -1: a biometric at every use. The default already, written out rather than left to
                    // the device (GPT review of the biometrics, 2026-09-22).
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun cipherToOpen(nonce: ByteArray): Cipher? =
        try {
            unavailableOnFailure {
                (keyStore.getKey(alias, null) as? SecretKey)?.let { key ->
                    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
                }
            }
        } catch (e: KeystoreUnavailableException) {
            if (e.cause is KeyPermanentlyInvalidatedException) null else throw e
        }

    override fun delete() = unavailableOnFailure { deleteEntry() }

    private fun deleteEntry() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /**
     * A Keystore failure: nothing is armed or opened, the caller says "retry". Key generation also
     * throws [IllegalStateException] when no biometric is enrolled, which the screens check first.
     */
    private inline fun <T> unavailableOnFailure(block: () -> T): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw KeystoreUnavailableException(e)
        } catch (e: ProviderException) {
            throw KeystoreUnavailableException(e)
        } catch (e: IllegalStateException) {
            throw KeystoreUnavailableException(e)
        }

    companion object {
        const val ALIAS = "pt_bio"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
