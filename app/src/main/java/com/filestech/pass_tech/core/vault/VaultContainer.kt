package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.Argon2id
import com.filestech.pass_tech.core.crypto.HkdfSha256
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.objectOrNull
import com.filestech.pass_tech.core.json.optInt
import com.filestech.pass_tech.core.json.optString
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * The encrypted file of one vault slot.
 *
 * Same construction as the vault v4 of the Flutter app, which was reviewed and audited:
 * ```
 * pwHash   = Argon2id(password, salt, m, t, p)                  (parameters written in the file)
 * secret   = unwrap(KEK of the slot, wrappedSecret)             (AndroidKeyStore, never leaves it)
 * finalKey = HKDF-SHA256(salt, pwHash || secret, "pt:v5", 32)
 * data     = AES-256-GCM(finalKey, nonce, padded payload, AAD)
 * AAD      = "pt:v=5|slot=<label>|kdf=argon2id|m=<m>|t=<t>|p=<p>"
 * ```
 * Version 5 because the payload changed (an object with entries and slot metadata instead of a bare
 * array) and the slot label replaced the Keystore alias in the AAD. The Flutter app never reads
 * these files, nor this app the Flutter ones (the Keystore keys differ anyway): only the `.ptbak`
 * backup crosses between the two.
 */
object VaultContainer {

    private const val MAGIC = "PTVAULT"
    private const val VERSION = 5
    private const val SALT_LENGTH = 32
    private const val SECRET_LENGTH = 32
    private const val KEY_LENGTH = 32
    private val HKDF_INFO = "pt:v5".encodeToByteArray()

    private val json = Json

    /** Everything in a vault file except the ciphertext. Kept by an open session to save without re-deriving. */
    class Header(
        val slot: Slot,
        val params: KdfParams,
        val salt: ByteArray,
        val wrappedSecret: SlotKeystore.Wrapped,
    )

    /** A new header for [slot]: fresh salt, fresh hardware secret wrapped by the slot's Keystore key. */
    fun newHeader(slot: Slot, keystore: SlotKeystore, params: KdfParams = KdfParams.OWASP_MOBILE_2024): Header {
        keystore.ensureKey(slot)
        val wrapped = SecretBytes.random(SECRET_LENGTH).useThenWipe { secret -> keystore.wrap(slot, secret) }
        return Header(slot, params, SecretBytes.random(SALT_LENGTH), wrapped)
    }

    /**
     * Derives the key of a file. ALWAYS runs Argon2id, even when the hardware secret cannot be
     * unwrapped: the unlock loop must take the same time whatever the slot holds.
     *
     * @return the key, or `null` if the Keystore could not unwrap the secret.
     */
    fun deriveKeyOrNull(header: Header, password: ByteArray, keystore: SlotKeystore): ByteArray? {
        val pwHash = Argon2id.derive(password, header.salt, header.params)
        return pwHash.useThenWipe { hash ->
            keystore.unwrapOrNull(header.slot, header.wrappedSecret)?.useThenWipe { secret ->
                (hash + secret).useThenWipe { ikm -> HkdfSha256.derive(header.salt, ikm, HKDF_INFO, KEY_LENGTH) }
            }
        }
    }

    /**
     * Encrypts [paddedPayload] under [key] into the file content, with the slot's [occupancy] mark
     * (sealed separately by the Keystore, readable without the password).
     */
    fun seal(header: Header, key: ByteArray, paddedPayload: ByteArray, occupancy: SlotKeystore.Wrapped): String {
        val sealed = AesGcm.encrypt(key, paddedPayload, aad(header))
        val encoder = Base64.getEncoder()
        val file = buildJsonObject {
            put("magic", MAGIC)
            put("version", VERSION)
            put("slot", header.slot.label)
            putJsonObject("occ") {
                put("nonce", encoder.encodeToString(occupancy.nonce))
                put("data", encoder.encodeToString(occupancy.ciphertext))
            }
            putJsonObject("kdf") {
                put("algo", "argon2id")
                put("m", header.params.memoryKiB)
                put("t", header.params.iterations)
                put("p", header.params.parallelism)
                put("salt", encoder.encodeToString(header.salt))
            }
            putJsonObject("kek") {
                put("algo", "AES-GCM-256")
                put("wrappedSecret", encoder.encodeToString(header.wrappedSecret.ciphertext))
                put("wrapNonce", encoder.encodeToString(header.wrappedSecret.nonce))
            }
            putJsonObject("cipher") {
                put("algo", "AES-GCM-256")
                put("nonce", encoder.encodeToString(sealed.nonce))
                put("data", encoder.encodeToString(sealed.cipherAndTag))
            }
        }
        return json.encodeToString(JsonObject.serializer(), file)
    }

    /** A parsed file: its occupancy mark (still sealed), its header and its ciphertext. */
    class Parsed(val occupancy: SlotKeystore.Wrapped, val header: Header, val nonce: ByteArray, val cipherAndTag: ByteArray)

    /**
     * Parses a file found in [expectedSlot]. `null` if it is not a v5 vault file, or if it claims
     * another slot: a file copied between slots is refused before any derivation.
     */
    fun parseOrNull(content: String, expectedSlot: Slot): Parsed? =
        readOrNull {
            val root = (json.parseToJsonElement(content) as? JsonObject).orReject()
            ensure(root.optString("magic") == MAGIC && root.optInt("version") == VERSION)
            ensure(root.optString("slot") == expectedSlot.label)
            val occ = root.objectOrNull("occ").orReject()
            val kdf = root.objectOrNull("kdf").orReject()
            val kek = root.objectOrNull("kek").orReject()
            val cipher = root.objectOrNull("cipher").orReject()
            ensure(kdf.optString("algo") == "argon2id")
            val params = KdfParams.validatedOrNull(
                memoryKiB = kdf.optInt("m").orReject(),
                iterations = kdf.optInt("t").orReject(),
                parallelism = kdf.optInt("p").orReject(),
            ).orReject()
            val decoder = Base64.getDecoder()
            val header = Header(
                slot = expectedSlot,
                params = params,
                salt = decoder.decode(kdf.requireString("salt")),
                wrappedSecret = SlotKeystore.Wrapped(
                    ciphertext = decoder.decode(kek.requireString("wrappedSecret")),
                    nonce = decoder.decode(kek.requireString("wrapNonce")),
                ),
            )
            Parsed(
                occupancy = SlotKeystore.Wrapped(
                    ciphertext = decoder.decode(occ.requireString("data")),
                    nonce = decoder.decode(occ.requireString("nonce")),
                ),
                header = header,
                nonce = decoder.decode(cipher.requireString("nonce")),
                cipherAndTag = decoder.decode(cipher.requireString("data")),
            )
        }

    /** The padded payload, or `null` if [key] is not the key of this file (or the file was altered). */
    fun openOrNull(parsed: Parsed, key: ByteArray): ByteArray? =
        AesGcm.decryptOrNull(key, parsed.nonce, parsed.cipherAndTag, aad(parsed.header))

    private fun aad(header: Header): ByteArray =
        with(header.params) { "pt:v=$VERSION|slot=${header.slot.label}|kdf=argon2id|m=$memoryKiB|t=$iterations|p=$parallelism" }
            .encodeToByteArray()
}
