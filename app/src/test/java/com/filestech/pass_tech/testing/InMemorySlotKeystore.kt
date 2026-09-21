package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.vault.KeyResult
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.SlotKeystore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A [SlotKeystore] for JVM tests: keys in memory, the same GCM layout as the AndroidKeyStore
 * implementation (ciphertext followed by the tag, 12-byte nonce, no AAD), HMAC-SHA256 for slot keys.
 * It can also play a Keystore that does not answer, per alias: see [unavailable].
 */
class InMemorySlotKeystore : SlotKeystore {

    private val aesKeys = mutableMapOf<String, ByteArray>()
    private val hmacKeys = mutableMapOf<String, ByteArray>()

    /** Every alias [unwrap] was asked for, in order. */
    val unwrapCalls = mutableListOf<String>()

    /** Every alias [hmac] was asked for, in order: lets a test check which slots an attempt touched. */
    val hmacCalls = mutableListOf<String>()

    /** Every HMAC key ever generated, in order, including re-creations. */
    val hmacKeysCreated = mutableListOf<String>()

    /** Aliases for which the Keystore does not answer: reads give Unavailable, writes throw. */
    val unavailable = mutableSetOf<String>()

    /** When set, every HMAC from this many calls on (counted in [hmacCalls]) gives Unavailable. */
    var hmacUnavailableAfter: Int? = null

    /** The raw HMAC key of [alias], for tests that recompute the specification by hand. */
    fun hmacKey(alias: String): ByteArray? = hmacKeys[alias]

    override fun ensureAesKey(alias: String) {
        if (alias in unavailable) throw KeystoreUnavailableException()
        aesKeys.getOrPut(alias) { SecretBytes.random(AesGcm.KEY_LENGTH) }
    }

    override fun ensureHmacKeys(aliases: Collection<String>) {
        aliases.filter { it !in hmacKeys }.forEach { alias ->
            if (alias in unavailable) throw KeystoreUnavailableException()
            hmacKeys[alias] = SecretBytes.random(HMAC_KEY_LENGTH)
            hmacKeysCreated += alias
        }
    }

    override fun deleteKey(alias: String) {
        aesKeys.remove(alias)
        hmacKeys.remove(alias)
    }

    override fun wrap(alias: String, plain: ByteArray): SlotKeystore.Wrapped {
        if (alias in unavailable) throw KeystoreUnavailableException()
        val sealed = AesGcm.encrypt(requireNotNull(aesKeys[alias]) { "no key $alias" }, plain, ByteArray(0))
        return SlotKeystore.Wrapped(sealed.cipherAndTag, sealed.nonce)
    }

    override fun unwrap(alias: String, wrapped: SlotKeystore.Wrapped): KeyResult<ByteArray> {
        unwrapCalls += alias
        val key = aesKeys[alias]
        return when {
            alias in unavailable -> KeyResult.Unavailable
            key == null -> KeyResult.NoKey
            else -> AesGcm.decryptOrNull(key, wrapped.nonce, wrapped.ciphertext, ByteArray(0))
                ?.let { KeyResult.Done(it) }
                ?: KeyResult.Refused
        }
    }

    override fun hmac(alias: String, data: ByteArray): KeyResult<ByteArray> {
        hmacCalls += alias
        val key = hmacKeys[alias]
        return when {
            alias in unavailable || hmacUnavailableAfter?.let { hmacCalls.size > it } == true -> KeyResult.Unavailable
            key == null -> KeyResult.NoKey
            else -> KeyResult.Done(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data))
        }
    }

    private companion object {
        const val HMAC_KEY_LENGTH = 32
    }
}
