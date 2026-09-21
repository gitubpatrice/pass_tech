package com.filestech.pass_tech.core.vault

/**
 * AES-256-GCM keys that live in secure hardware, addressed by alias. They never leave it: the app
 * can only ask them to seal or open.
 *
 * Every hardware-bound secret of the app goes through here: the per-slot keys that wrap each vault's
 * hardware secret (see [Slot.keystoreAlias]), and the key of the encrypted state store. One
 * implementation of the Keystore calls, not one per use.
 *
 * An interface so the vault logic runs in JVM tests against `InMemorySlotKeystore`; the device
 * implementation is [AndroidSlotKeystore].
 */
interface SlotKeystore {

    class Wrapped(val ciphertext: ByteArray, val nonce: ByteArray)

    /** Creates the key [alias] if it does not exist. */
    fun ensureKey(alias: String)

    /** Deletes the key [alias]. Deleting an absent key is not an error. */
    fun deleteKey(alias: String)

    fun wrap(alias: String, plain: ByteArray): Wrapped

    /**
     * `null` if the key is missing, the data does not authenticate, or the Keystore fails. The caller
     * must read `null` as "does not open", NEVER as a reason to rewrite anything: a transient
     * Keystore failure looks exactly the same.
     */
    fun unwrapOrNull(alias: String, wrapped: Wrapped): ByteArray?
}

fun SlotKeystore.ensureKey(slot: Slot) = ensureKey(slot.keystoreAlias)

fun SlotKeystore.deleteKey(slot: Slot) = deleteKey(slot.keystoreAlias)

fun SlotKeystore.wrap(slot: Slot, secret: ByteArray) = wrap(slot.keystoreAlias, secret)

fun SlotKeystore.unwrapOrNull(slot: Slot, wrapped: SlotKeystore.Wrapped) = unwrapOrNull(slot.keystoreAlias, wrapped)
