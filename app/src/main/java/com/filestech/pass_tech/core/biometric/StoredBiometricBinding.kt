package com.filestech.pass_tech.core.biometric

import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.BiometricBinding
import com.filestech.pass_tech.core.vault.BiometricBinding.Start
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/**
 * [BiometricBinding] kept in the sealed [StateStore]: the armed vault's generation, and its key sealed
 * by [keys] with the generation as associated data, so that neither can be swapped for another.
 *
 * The generation stays readable without a biometric: the owner of another vault is told that a
 * fingerprint opens a vault that is not theirs (design v2 §9). A lost state reads as not armed.
 */
class StoredBiometricBinding(private val store: StateStore, private val keys: BiometricKeys) : BiometricBinding {

    private class Record(val generation: String, val nonce: ByteArray, val sealed: ByteArray)

    override fun armedGeneration(): String? = record()?.generation

    override fun cipherToArm(): Cipher {
        purge()
        return keys.cipherToSeal()
    }

    override fun arm(generation: String, key: ByteArray, cipher: Cipher) {
        val sealed = crypto {
            cipher.updateAAD(associatedData(generation))
            cipher.doFinal(key)
        }
        val nonce = cipher.iv
        store.update {
            JsonObject(
                it + mapOf(
                    GENERATION to JsonPrimitive(generation),
                    NONCE to JsonPrimitive(Base64.getEncoder().encodeToString(nonce)),
                    SEALED to JsonPrimitive(Base64.getEncoder().encodeToString(sealed)),
                ),
            )
        }
    }

    override fun cipherToUnlock(): Start {
        val record = record() ?: return Start.NotArmed
        val cipher = keys.cipherToOpen(record.nonce)
        if (cipher == null) {
            purge()
            return Start.Invalidated
        }
        return Start.Ready(cipher)
    }

    override fun open(cipher: Cipher): BiometricBinding.Armed? {
        val record = record() ?: return null
        return try {
            val key = crypto {
                cipher.updateAAD(associatedData(record.generation))
                cipher.doFinal(record.sealed)
            }
            BiometricBinding.Armed(record.generation, key)
        } catch (e: KeystoreUnavailableException) {
            if (e.cause is AEADBadTagException) null else throw e
        }
    }

    /** The key first: once it is gone, what the state still holds opens nothing. */
    override fun purge() {
        keys.delete()
        if (store.read().keys.any { it in FIELDS }) store.update { JsonObject(it - FIELDS) }
    }

    /** `null` when not armed, or when a field is missing or damaged: a lost state disarms. */
    private fun record(): Record? {
        val state = store.read()
        fun field(name: String) = (state[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val generation = field(GENERATION)
        val nonce = field(NONCE)
        val sealed = field(SEALED)
        if (generation == null || nonce == null || sealed == null) return null
        return try {
            Record(generation, Base64.getDecoder().decode(nonce), Base64.getDecoder().decode(sealed))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private inline fun <T> crypto(block: () -> T): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw KeystoreUnavailableException(e)
        } catch (e: ProviderException) {
            throw KeystoreUnavailableException(e)
        }

    private companion object {
        const val GENERATION = "bio.gen"
        const val NONCE = "bio.nonce"
        const val SEALED = "bio.sealed"
        val FIELDS = setOf(GENERATION, NONCE, SEALED)

        fun associatedData(generation: String) = "pt:bio|gen=$generation".encodeToByteArray()
    }
}
