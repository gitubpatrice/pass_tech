package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.vault.SlotKeystore

/**
 * A [SlotKeystore] for JVM tests: one AES-256 key per slot, in memory, with the same GCM layout as
 * the AndroidKeyStore implementation (ciphertext followed by the tag, 12-byte nonce, no AAD).
 */
class InMemorySlotKeystore : SlotKeystore {

    private val keys = mutableMapOf<String, ByteArray>()

    /** Every alias [unwrapOrNull] was asked for, in order: lets a test check which slots an attempt touched. */
    val unwrapCalls = mutableListOf<String>()

    override fun ensureKey(alias: String) {
        keys.getOrPut(alias) { SecretBytes.random(AesGcm.KEY_LENGTH) }
    }

    override fun deleteKey(alias: String) {
        keys.remove(alias)
    }

    override fun wrap(alias: String, plain: ByteArray): SlotKeystore.Wrapped {
        val sealed = AesGcm.encrypt(requireNotNull(keys[alias]) { "no key $alias" }, plain, ByteArray(0))
        return SlotKeystore.Wrapped(sealed.cipherAndTag, sealed.nonce)
    }

    override fun unwrapOrNull(alias: String, wrapped: SlotKeystore.Wrapped): ByteArray? {
        unwrapCalls += alias
        return keys[alias]?.let { AesGcm.decryptOrNull(it, wrapped.nonce, wrapped.ciphertext, ByteArray(0)) }
    }
}
