package com.filestech.pass_tech.core.vault

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * The encrypted file of one vault slot (design v2.2).
 * ```
 * pwHash   = Argon2id(password, salt, m, t, p)                     32 bytes, parameters in the file
 * hw       = HMAC-SHA256(slot key, "pt:v5|slot=<label>|" || pwHash)  32 bytes, computed INSIDE the secure hardware
 * finalKey = HKDF-SHA256(salt, pwHash || hw, "pt:v5", 32)
 * data     = AES-256-GCM(finalKey, nonce, padded payload, AAD)
 * AAD      = "pt:v=5|slot=<label>|kdf=argon2id|m=<m>|t=<t>|p=<p>"
 * ```
 * The slot key ([Slot.hardwareKeyAlias]) never leaves the hardware and takes part in EVERY attempt:
 * with a copy of the files, or even with code running on the phone, a guess can only be checked on
 * this phone, through its Keystore, for as long as the key cannot be extracted. The v4 of the Flutter
 * app wrapped a random secret instead, which does not depend on the password: one unwrap was enough
 * to take the files elsewhere and test passwords at GPU speed (GPT 5.6 review of the design, v2.2).
 *
 * The Flutter app never reads these files, nor this app the Flutter ones: only the `.ptbak` backup,
 * sealed by its own passphrase and nothing hardware-bound, crosses between the two.
 */
object VaultContainer {

    private const val MAGIC = "PTVAULT"
    private const val VERSION = 5
    private val HKDF_INFO = "pt:v5".encodeToByteArray()

    private val json = Json

    /** Everything in a vault file except the ciphertext. Kept by an open session to save without re-deriving. */
    class Header(val slot: Slot, val params: KdfParams, val salt: ByteArray)

    /** A new header for [slot]: a fresh salt. Also the synthetic header that spends an attempt's work on an empty slot. */
    fun newHeader(slot: Slot, params: KdfParams = KdfParams.OWASP_MOBILE_2024) =
        Header(slot, params, SecretBytes.random(SlotCrypto.SALT_LENGTH))

    /** See [SlotCrypto.deriveKey]. The domain `"pt:v5|slot=<label>|"` is this file's own. */
    fun deriveKey(header: Header, password: ByteArray, keystore: SlotKeystore): KeyResult<ByteArray> =
        SlotCrypto.deriveKey(
            slot = header.slot,
            params = header.params,
            salt = header.salt,
            password = password,
            keystore = keystore,
            domain = "pt:v$VERSION|slot=${header.slot.label}|",
            info = HKDF_INFO,
        )

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
            val cipher = root.objectOrNull("cipher").orReject()
            ensure(kdf.optString("algo") == "argon2id")
            val params = KdfParams.validatedOrNull(
                memoryKiB = kdf.optInt("m").orReject(),
                iterations = kdf.optInt("t").orReject(),
                parallelism = kdf.optInt("p").orReject(),
            ).orReject()
            val decoder = Base64.getDecoder()
            val header = Header(slot = expectedSlot, params = params, salt = decoder.decode(kdf.requireString("salt")))
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

    /**
     * The padded payload, or `null` if [key] is not the key of this file (or the file was altered).
     *
     * The ciphertext may be followed by random bytes that another session wrote to bring this file to
     * the size of the others ([grownTo]), so where it ends is not known in advance. Each bucket it
     * could have been padded to is tried, smallest first, and the authentication tag decides: a wrong
     * boundary fails exactly as a wrong key does. A file with no filler matches on its first and only
     * candidate, which is why files written before this existed still open.
     */
    fun openOrNull(parsed: Parsed, key: ByteArray): ByteArray? =
        SlotFiller.openTrying(parsed.cipherAndTag) { AesGcm.decryptOrNull(key, parsed.nonce, it, aad(parsed.header)) }

    private fun aad(header: Header): ByteArray =
        with(header.params) { "pt:v=$VERSION|slot=${header.slot.label}|kdf=argon2id|m=$memoryKiB|t=$iterations|p=$parallelism" }
            .encodeToByteArray()
}
