package com.filestech.pass_tech.core.vault

/**
 * The keys of the app that live in secure hardware, addressed by alias. They never leave it: the app
 * can only ask them to seal, open or sign.
 *
 * Two kinds (design v2.2):
 * - **AES-256-GCM keys, in the TEE**, for the app's own data: the occupancy marks ([OccupancyMark])
 *   and the state store. They serve several times per operation, and StrongBox would cost ~235 ms
 *   each time (measured on a Galaxy S24) for no protection the threat model counts.
 * - **HMAC-SHA256 keys, one per slot**, that take part in every password attempt ([VaultContainer]).
 *   StrongBox when the device has one that works, the TEE otherwise, the same level for the whole set.
 *
 * Every read says what happened ([KeyResult]): a missing key, data that do not authenticate, and a
 * Keystore that did not answer are three different things, and only the last one may be retried.
 *
 * An interface so the vault logic runs in JVM tests against `InMemorySlotKeystore`; the device
 * implementation is [AndroidSlotKeystore].
 */
interface SlotKeystore {

    class Wrapped(val ciphertext: ByteArray, val nonce: ByteArray)

    /** Creates the AES key [alias] if it does not exist. */
    fun ensureAesKey(alias: String)

    /**
     * Creates those of the HMAC keys [aliases] that do not exist, all at the same hardware level. Only
     * ever called for slots that are provably free: a key is never re-created under a vault.
     */
    fun ensureHmacKeys(aliases: Collection<String>)

    /** Deletes the key [alias]. Deleting an absent key is not an error. */
    fun deleteKey(alias: String)

    /** Seals [plain] with the AES key [alias]. Throws [KeystoreUnavailableException] if the Keystore fails. */
    fun wrap(alias: String, plain: ByteArray): Wrapped

    fun unwrap(alias: String, wrapped: Wrapped): KeyResult<ByteArray>

    /** HMAC-SHA256 of [data] under the key [alias], computed inside the secure hardware. */
    fun hmac(alias: String, data: ByteArray): KeyResult<ByteArray>
}

/**
 * What a Keystore read gives back. [Unavailable] must never be read as "wrong password", nor as a
 * reason to rewrite anything: it only says that nothing is known yet.
 */
sealed interface KeyResult<out T> {
    data class Done<T>(val value: T) : KeyResult<T>

    /** The key does not exist. Permanent. */
    data object NoKey : KeyResult<Nothing>

    /** The data do not authenticate under this key: altered, or sealed by another key. Permanent. */
    data object Refused : KeyResult<Nothing>

    /** The Keystore did not answer: busy hardware, a restarting service... Retry later. */
    data object Unavailable : KeyResult<Nothing>
}

fun <T> KeyResult<T>.valueOrNull(): T? = (this as? KeyResult.Done)?.value

/** The Keystore did not answer. The operation stopped before writing anything it could not finish. */
class KeystoreUnavailableException(cause: Throwable? = null) : Exception("the Keystore did not answer", cause)
