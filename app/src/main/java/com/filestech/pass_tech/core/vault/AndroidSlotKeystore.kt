package com.filestech.pass_tech.core.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SlotKeystore] on the AndroidKeyStore: keys generated inside the secure hardware and never exported.
 *
 * No user authentication on these keys, as in the Flutter app: the master password is the factor, and
 * binding the keys to the lock screen would make the vault unreadable after a lock-screen change.
 *
 * Where each key lives (design v2.2, measured on a Galaxy S24: StrongBox ~150-235 ms per operation,
 * the TEE ~5 ms):
 * - the AES keys (occupancy marks, state store) in the TEE, always;
 * - the slot HMAC keys in StrongBox if a probe key there works, in the TEE otherwise, and the same
 *   level for every key of one call: a mixed set would answer at different speeds.
 */
@Singleton
class AndroidSlotKeystore @Inject constructor() : SlotKeystore {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun ensureAesKey(alias: String) = unavailableOnFailure {
        if (!keyStore.containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        }
    }

    override fun ensureHmacKeys(aliases: Collection<String>) = unavailableOnFailure {
        val missing = aliases.filterNot(keyStore::containsAlias)
        if (missing.isNotEmpty() && !generateAll(missing, strongBox = strongBoxWorks())) generateAll(missing, strongBox = false)
    }

    override fun deleteKey(alias: String) = unavailableOnFailure {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun wrap(alias: String, plain: ByteArray): SlotKeystore.Wrapped = unavailableOnFailure {
        val key = requireNotNull(keyStore.getKey(alias, null) as? SecretKey) { "no Keystore key $alias" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        SlotKeystore.Wrapped(ciphertext = cipher.doFinal(plain), nonce = cipher.iv)
    }

    override fun unwrap(alias: String, wrapped: SlotKeystore.Wrapped): KeyResult<ByteArray> =
        keyOperation(alias) { key ->
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped.nonce))
                doFinal(wrapped.ciphertext)
            }
        }

    override fun hmac(alias: String, data: ByteArray): KeyResult<ByteArray> =
        keyOperation(alias) { key -> Mac.getInstance(HMAC_ALGORITHM).apply { init(key) }.doFinal(data) }

    /**
     * Runs [operation] with the key [alias], and says how it failed. Only a missing key and data that
     * do not authenticate are known to be permanent; anything else (busy hardware, a restarting
     * Keystore service, a key that cannot be loaded right now) is [KeyResult.Unavailable].
     */
    private inline fun keyOperation(alias: String, operation: (SecretKey) -> ByteArray): KeyResult<ByteArray> =
        try {
            when (val key = keyStore.getKey(alias, null)) {
                is SecretKey -> KeyResult.Done(operation(key))
                else -> KeyResult.NoKey
            }
        } catch (_: AEADBadTagException) {
            KeyResult.Refused
        } catch (_: KeyPermanentlyInvalidatedException) {
            KeyResult.NoKey
        } catch (_: Exception) {
            KeyResult.Unavailable
        }

    /** Generates [aliases] at one level. At StrongBox, a failure undoes this call's keys and says false. */
    private fun generateAll(aliases: List<String>, strongBox: Boolean): Boolean {
        val created = mutableListOf<String>()
        return try {
            aliases.forEach { alias ->
                generateHmac(alias, strongBox)
                created += alias
            }
            true
        } catch (e: Exception) {
            // StrongBox fails through ProviderException, or through a GeneralSecurityException at init.
            if (!strongBox) throw e
            // No file depends on these keys yet: they are created before anything is written.
            created.forEach(keyStore::deleteEntry)
            false
        }
    }

    /** Whether StrongBox really works here: a throwaway key, used once, deleted (GPT review of v2.2, P6). */
    private fun strongBoxWorks(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            generateHmac(PROBE_ALIAS, strongBox = true)
            (keyStore.getKey(PROBE_ALIAS, null) as? SecretKey)
                ?.let { Mac.getInstance(HMAC_ALGORITHM).apply { init(it) }.doFinal(PROBE_INPUT).size == HMAC_BYTES } == true
        } catch (_: Exception) {
            false // any failure means "no": the TEE is used
        } finally {
            try {
                keyStore.deleteEntry(PROBE_ALIAS)
            } catch (_: Exception) {
                // Nothing depends on the probe key; a leftover is harmless and replaced by the next probe.
            }
        }
    }

    private fun generateHmac(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).apply { init(spec) }.generateKey()
    }

    /** A Keystore failure while creating or sealing: nothing is written, the caller says "retry". */
    private inline fun <T> unavailableOnFailure(block: () -> T): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw KeystoreUnavailableException(e)
        } catch (e: ProviderException) {
            throw KeystoreUnavailableException(e)
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val HMAC_BYTES = 32
        const val PROBE_ALIAS = "pt_v5_hw_probe"
        val PROBE_INPUT = "pt:probe".encodeToByteArray()
    }
}
