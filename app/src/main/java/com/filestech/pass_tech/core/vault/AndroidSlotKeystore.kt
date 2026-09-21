package com.filestech.pass_tech.core.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SlotKeystore] on the AndroidKeyStore: AES-256-GCM keys generated inside the secure hardware and
 * never exported.
 *
 * No user authentication on these keys, as in the Flutter app: the master password is the factor,
 * and binding the keys to the lock screen would make the vault unreadable after a lock-screen change.
 * StrongBox is used when the device has one (Galaxy S24), the TEE otherwise (Galaxy S9).
 */
@Singleton
class AndroidSlotKeystore @Inject constructor() : SlotKeystore {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun ensureKey(alias: String) {
        if (keyStore.containsAlias(alias)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(alias, strongBox = true)
                return
            } catch (_: ProviderException) {
                // StrongBoxUnavailableException is a ProviderException. It is not named here: it only
                // exists from API 28, and a catch clause on it would break class loading on API 26-27.
                // Some OEM StrongBox implementations also fail with a plain ProviderException.
            }
        }
        generate(alias, strongBox = false)
    }

    override fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun wrap(alias: String, plain: ByteArray): SlotKeystore.Wrapped {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        val ciphertext = cipher.doFinal(plain)
        return SlotKeystore.Wrapped(ciphertext = ciphertext, nonce = cipher.iv)
    }

    override fun unwrapOrNull(alias: String, wrapped: SlotKeystore.Wrapped): ByteArray? =
        try {
            val key = keyStore.getKey(alias, null) as? SecretKey
            key?.let {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(TAG_BITS, wrapped.nonce))
                    doFinal(wrapped.ciphertext)
                }
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            // Keystore2 surfaces some hardware failures as ProviderException, which is unchecked.
            null
        }

    private fun key(alias: String): SecretKey =
        requireNotNull(keyStore.getKey(alias, null) as? SecretKey) { "no Keystore key $alias" }

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
