package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.objectOrNull
import com.filestech.pass_tech.core.json.optInt
import com.filestech.pass_tech.core.json.optString
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryJson
import com.filestech.pass_tech.core.vault.KeyResult
import com.filestech.pass_tech.core.vault.Padding
import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.SlotCrypto
import com.filestech.pass_tech.core.vault.SlotKeystore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** What an heir sees: a copy of the entries of one vault, taken the day it was configured. */
data class HeirSnapshot(val generation: String, val entries: List<Entry>)

/**
 * The encrypted snapshot of one slot, `pt_heir_<slot>.enc`, opened by the heir passphrase alone —
 * never by the master password (design v2 §8).
 *
 * Same shape as a vault file minus the occupancy mark, and **sealed under its own domain**
 * ([SlotCrypto.deriveKey]): the same passphrase gives two unrelated keys here and in the vault, so
 * swapping the two files gains nothing.
 *
 * **Bound to the hardware like the vault**, which 2.7.1 did not do: its heir file was derived from
 * the passphrase alone, so a copy of it could be guessed at elsewhere, at GPU speed, and it holds
 * every secret of the vault behind a passphrase written down on paper somewhere. Here an attempt
 * costs one Keystore HMAC on this phone. The heir uses this phone anyway: the vault itself cannot be
 * opened on another, and a `.ptbak` backup is what crosses between phones.
 */
object HeirContainer {

    private const val MAGIC = "PTHEIR"
    private const val VERSION = 1
    private val HKDF_INFO = "pt:heir$VERSION".encodeToByteArray()

    private val json = Json

    class Header(val slot: Slot, val params: KdfParams, val salt: ByteArray)

    fun newHeader(slot: Slot, params: KdfParams) = Header(slot, params, SecretBytes.random(SlotCrypto.SALT_LENGTH))

    fun deriveKey(header: Header, passphrase: ByteArray, keystore: SlotKeystore): KeyResult<ByteArray> =
        SlotCrypto.deriveKey(
            slot = header.slot,
            params = header.params,
            salt = header.salt,
            password = passphrase,
            keystore = keystore,
            domain = "pt:heir$VERSION|slot=${header.slot.label}|",
            info = HKDF_INFO,
        )

    /**
     * [mark] says whether this file is a real snapshot or a dummy, sealed by the hardware and
     * readable without the passphrase: see [HeirMark] for why that is needed and what it must never
     * be used for.
     */
    fun seal(header: Header, key: ByteArray, paddedPayload: ByteArray, mark: SlotKeystore.Wrapped): String {
        val sealed = AesGcm.encrypt(key, paddedPayload, aad(header))
        val encoder = Base64.getEncoder()
        val file = buildJsonObject {
            put("magic", MAGIC)
            put("version", VERSION)
            put("slot", header.slot.label)
            putJsonObject("occ") {
                put("nonce", encoder.encodeToString(mark.nonce))
                put("data", encoder.encodeToString(mark.ciphertext))
            }
            putJsonObject("kdf") {
                put("algo", "argon2id")
                put("m", header.params.memoryKiB)
                put("t", header.params.iterations)
                put("p", header.params.parallelism)
                put("salt", encoder.encodeToString(header.salt))
            }
            putJsonObject("cipher") {
                put("algo", "AES-GCM-256")
                put("nonce", encoder.encodeToString(sealed.nonce))
                put("data", encoder.encodeToString(sealed.cipherAndTag))
            }
        }
        return json.encodeToString(JsonObject.serializer(), file)
    }

    /** [mark] is `null` for a file written before the mark existed: that reads as UNKNOWN, never touched. */
    class Parsed(val header: Header, val nonce: ByteArray, val cipherAndTag: ByteArray, val mark: SlotKeystore.Wrapped?)

    /** `null` if this is not a heir snapshot of [expectedSlot]: a file moved between slots is refused. */
    fun parseOrNull(content: String, expectedSlot: Slot): Parsed? =
        readOrNull {
            val root = (json.parseToJsonElement(content) as? JsonObject).orReject()
            ensure(root.optString("magic") == MAGIC && root.optInt("version") == VERSION)
            ensure(root.optString("slot") == expectedSlot.label)
            val kdf = root.objectOrNull("kdf").orReject()
            val cipher = root.objectOrNull("cipher").orReject()
            ensure(kdf.optString("algo") == "argon2id")
            val params = KdfParams.validatedOrNull(
                memoryKiB = kdf.optInt("m").orReject(),
                iterations = kdf.optInt("t").orReject(),
                parallelism = kdf.optInt("p").orReject(),
            ).orReject()
            val decoder = Base64.getDecoder()
            val occ = root.objectOrNull("occ")?.let {
                SlotKeystore.Wrapped(
                    ciphertext = decoder.decode(it.requireString("data")),
                    nonce = decoder.decode(it.requireString("nonce")),
                )
            }
            Parsed(
                header = Header(expectedSlot, params, decoder.decode(kdf.requireString("salt"))),
                nonce = decoder.decode(cipher.requireString("nonce")),
                cipherAndTag = decoder.decode(cipher.requireString("data")),
                mark = occ,
            )
        }

    fun openOrNull(parsed: Parsed, key: ByteArray): ByteArray? =
        AesGcm.decryptOrNull(key, parsed.nonce, parsed.cipherAndTag, aad(parsed.header))

    /** The snapshot, ready to be padded and sealed. */
    fun payloadOf(snapshot: HeirSnapshot): ByteArray {
        val content = buildJsonObject {
            put("gen", snapshot.generation)
            put("entries", EntryJson.toJsonArray(snapshot.entries))
        }
        return json.encodeToString(JsonObject.serializer(), content).encodeToByteArray()
    }

    /** Reads a decrypted, padded snapshot. Strict: one entry that does not read refuses the file. */
    fun snapshotOrNull(padded: ByteArray): HeirSnapshot? =
        readOrNull {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(padded, 0, Padding.unpaddedLength(padded)))
                .toString()
            val root = (json.parseToJsonElement(text) as? JsonObject).orReject()
            val entries = (root["entries"] as? JsonArray).orReject()
            HeirSnapshot(
                generation = root.requireString("gen"),
                entries = entries.map { EntryJson.fromJsonOrNull(it).orReject() },
            )
        }

    private fun aad(header: Header): ByteArray =
        with(header.params) { "pt:heir=$VERSION|slot=${header.slot.label}|kdf=argon2id|m=$memoryKiB|t=$iterations|p=$parallelism" }
            .encodeToByteArray()
}
