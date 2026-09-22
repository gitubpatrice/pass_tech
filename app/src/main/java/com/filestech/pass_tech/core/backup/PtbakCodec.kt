package com.filestech.pass_tech.core.backup

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.Argon2id
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.LegacyCrypto
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.objectOrNull
import com.filestech.pass_tech.core.json.optInt
import com.filestech.pass_tech.core.json.optString
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.reject
import com.filestech.pass_tech.core.json.requireString
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The `.ptbak` encrypted backup, the only file of Pass Tech meant to leave the device.
 *
 * - **Reads** v1, v2 and v3, with the exact rules of the Flutter app 2.7.1
 *   (`ImportExportService.importEncrypted`), so that every backup it accepts opens here too.
 * - **Writes** v3 only, byte-compatible with 2.7.1: a backup made by this app imports into the
 *   Flutter app. That is the way back if the Kotlin app ever has to be abandoned.
 *
 * Both directions are pinned by vectors produced by the Flutter code itself
 * (`app/src/test/resources/compat/2.7.1`, generator in `tools/compat`).
 *
 * v3: Argon2id (parameters written in the file) + AES-256-GCM, with
 *     AAD = "ptbak:v=3|kdf=argon2id|m=<m>|t=<t>|p=<p>|salt=<salt as written>".
 * v2: PBKDF2-HMAC-SHA256 (64 bytes: 32 for AES-256-CBC, 32 for HMAC-SHA256),
 *     MAC = HMAC(aad || iv || data) with aad = "ptbak:v=2|iter=<n>|salt=<salt as written>".
 * v1: as v2, 100 000 iterations, MAC = HMAC(iv || data).
 */
object PtbakCodec {

    /** Same cap as the Flutter app, measured like it, in UTF-16 code units. */
    const val MAX_FILE_CHARS = 50 * 1024 * 1024

    private const val MAGIC = "PTBAK"
    private const val VERSION_V3 = 3
    private const val SALT_LENGTH = 32
    private const val MIN_SALT_LENGTH = 16
    private const val LEGACY_DEFAULT_ITERATIONS = 600_000
    private const val LEGACY_MAX_ITERATIONS = 2_000_000
    private const val LEGACY_KEY_LENGTH = 64
    private const val LEGACY_HALF = 32
    private const val LEGACY_MAC_LENGTH = 32

    private val json = Json

    /** Result of a successful import: the entries the file held, minus those Dart would skip too. */
    class Imported(val version: Int, val entries: List<Entry>)

    /**
     * Encrypts [entries] into a v3 backup with [passphrase], using [params] (the current write
     * recommendation by default).
     */
    fun export(entries: List<Entry>, passphrase: String, params: KdfParams = KdfParams.OWASP_MOBILE_2024): String {
        val salt = SecretBytes.random(SALT_LENGTH)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val sealed = deriveV3(passphrase, salt, params).useThenWipe { key ->
            json.encodeToString(JsonArray.serializer(), EntryJson.toJsonArray(entries))
                .encodeToByteArray()
                .useThenWipe { plain -> AesGcm.encrypt(key, plain, aadV3(saltB64, params)) }
        }
        val file = buildJsonObject {
            put("magic", MAGIC)
            put("version", VERSION_V3)
            putJsonObject("kdf") {
                put("algo", "argon2id")
                put("m", params.memoryKiB)
                put("t", params.iterations)
                put("p", params.parallelism)
                put("salt", saltB64)
            }
            putJsonObject("cipher") {
                put("nonce", Base64.getEncoder().encodeToString(sealed.nonce))
                put("data", Base64.getEncoder().encodeToString(sealed.cipherAndTag))
            }
        }
        return json.encodeToString(JsonObject.serializer(), file)
    }

    /**
     * Whether a file the owner picked is a backup: its name, or its own magic word, as 2.7.1 decides it.
     * Only the passphrase then tells whether it really is one.
     */
    fun looksLikeBackup(fileName: String, content: String): Boolean =
        fileName.endsWith(".ptbak", ignoreCase = true) ||
            readOrNull { (json.parseToJsonElement(content) as? JsonObject).orReject().optString("magic") } == MAGIC

    /**
     * @return the decrypted entries, or `null` if the passphrase is wrong or the file is damaged,
     * forged or not a backup. Those cases are deliberately indistinguishable, as in the Flutter app.
     *
     * Every check below rejects (see `FileRejected.kt`), and [readOrNull] is the only place that
     * turns a rejection into `null`: one exit, so no path can forget to fail closed.
     */
    fun import(content: String, passphrase: String): Imported? =
        readOrNull {
            ensure(content.length <= MAX_FILE_CHARS)
            val root = (json.parseToJsonElement(content) as? JsonObject).orReject()
            ensure(root.optString("magic") == MAGIC)
            val version = root.optInt("version") ?: 1
            val entries = when (version) {
                VERSION_V3 -> importV3(root, passphrase)
                1, 2 -> importLegacy(root, version, passphrase)
                else -> reject()
            }
            Imported(version, entries)
        }

    private fun importV3(root: JsonObject, passphrase: String): List<Entry> {
        val kdf = root.objectOrNull("kdf").orReject()
        val cipher = root.objectOrNull("cipher").orReject()
        ensure(kdf.optString("algo") == "argon2id")
        val params = paramsFromFile(kdf)

        val saltB64 = kdf.optString("salt") ?: ""
        val salt = decodeBase64(saltB64)
        ensure(salt.size >= MIN_SALT_LENGTH)
        val nonce = decodeBase64(cipher.requireString("nonce"))
        val data = decodeBase64(cipher.requireString("data"))
        ensure(data.size >= AesGcm.TAG_LENGTH)

        val plain = deriveV3(passphrase, salt, params)
            .useThenWipe { key -> AesGcm.decryptOrNull(key, nonce, data, aadV3(saltB64, params)) }
            .orReject()
        // Strict UTF-8, as `utf8.decode` in Dart: a malformed plaintext refuses the whole file.
        return plain.useThenWipe { bytes -> parseEntries(decodeUtf8Strict(bytes)) }
    }

    private fun importLegacy(root: JsonObject, version: Int, passphrase: String): List<Entry> {
        val iterations = root.optInt("iterations") ?: LEGACY_DEFAULT_ITERATIONS
        ensure(iterations in 1..LEGACY_MAX_ITERATIONS)

        val saltB64 = root.requireString("salt")
        val salt = decodeBase64(saltB64)
        val iv = decodeBase64(root.requireString("iv"))
        val mac = decodeBase64(root.requireString("mac"))
        val data = decodeBase64(root.requireString("data"))
        // Checked BEFORE deriving: a forged short MAC must not get a cheap comparison.
        ensure(mac.size == LEGACY_MAC_LENGTH)

        val key = passphrase.encodeToByteArray().useThenWipe { pw ->
            LegacyCrypto.pbkdf2HmacSha256(pw, salt, iterations, LEGACY_KEY_LENGTH)
        }
        return key.useThenWipe {
            val encKey = key.copyOfRange(0, LEGACY_HALF)
            val macKey = key.copyOfRange(LEGACY_HALF, LEGACY_KEY_LENGTH)
            try {
                val expected = if (version >= 2) {
                    val aad = "ptbak:v=$version|iter=$iterations|salt=$saltB64".encodeToByteArray()
                    LegacyCrypto.hmacSha256(macKey, aad, iv, data)
                } else {
                    LegacyCrypto.hmacSha256(macKey, iv, data)
                }
                ensure(SecretBytes.constantTimeEquals(expected, mac))
                // Lenient UTF-8 here, as 2.7.1 reads the legacy formats (`allowMalformed: true`).
                LegacyCrypto.aesCbcDecryptOrNull(encKey, iv, data).orReject()
                    .useThenWipe { bytes -> parseEntries(bytes.decodeToString()) }
            } finally {
                encKey.wipe()
                macKey.wipe()
            }
        }
    }

    /** A JSON array of entries. An entry Dart would reject is skipped, the rest is kept. */
    private fun parseEntries(text: String): List<Entry> =
        (json.parseToJsonElement(text) as? JsonArray).orReject().mapNotNull(EntryJson::fromJsonOrNull)

    /**
     * `KdfParams.fromFileOrNull` of the Flutter app: all three absent means the defaults (no file
     * ever omitted them, but nothing breaks), present but of the wrong type or out of bounds means
     * the file is refused.
     */
    private fun paramsFromFile(kdf: JsonObject): KdfParams {
        val m = kdf.optInt("m")
        val t = kdf.optInt("t")
        val p = kdf.optInt("p")
        return when {
            m == null && t == null && p == null -> KdfParams.OWASP_MOBILE_2024
            m == null || t == null || p == null -> reject()
            else -> KdfParams.validatedOrNull(m, t, p).orReject()
        }
    }

    private fun deriveV3(passphrase: String, salt: ByteArray, params: KdfParams): ByteArray =
        passphrase.encodeToByteArray().useThenWipe { pw -> Argon2id.derive(pw, salt, params) }

    private fun aadV3(saltB64: String, params: KdfParams): ByteArray =
        "ptbak:v=$VERSION_V3|kdf=argon2id|m=${params.memoryKiB}|t=${params.iterations}|p=${params.parallelism}|salt=$saltB64"
            .encodeToByteArray()

    /** Dart's `base64Decode` accepts both the standard and the URL-safe alphabet. */
    private fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/'))

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}
