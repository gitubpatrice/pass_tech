package com.filestech.pass_tech.core.backup

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.EntryJson
import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64

/**
 * The `.ptbak` backup, against files produced by the Flutter app 2.7.1 itself
 * (`app/src/test/resources/compat/2.7.1`).
 */
class PtbakCodecTest {

    private val dir = "compat/2.7.1"
    private val expectedJson = Json.parseToJsonElement(Resources.text("$dir/entries.json")).jsonArray
    private val fixture = expectedJson.map { requireNotNull(EntryJson.fromJsonOrNull(it)) }

    @ParameterizedTest
    @ValueSource(strings = ["ptbak_v3.ptbak", "ptbak_v3_params.ptbak", "ptbak_v2.ptbak"])
    fun `backups written by Flutter 2_7_1 open with every entry intact`(file: String) {
        val imported = PtbakCodec.import(Resources.text("$dir/$file"), PASSPHRASE)
        assertThat(imported).isNotNull()
        assertThat(EntryJson.toJsonArray(imported!!.entries)).isEqualTo(expectedJson)
    }

    @Test
    fun `a v1 backup with the old entry schema opens as 2_7_1 opens it`() {
        val imported = PtbakCodec.import(Resources.text("$dir/ptbak_v1.ptbak"), PASSPHRASE)
        val expected = Json.parseToJsonElement(Resources.text("$dir/ptbak_v1.expected.json"))
        assertThat(imported?.version).isEqualTo(1)
        assertThat(EntryJson.toJsonArray(imported!!.entries)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ptbak_v3.ptbak", "ptbak_v3_params.ptbak", "ptbak_v2.ptbak", "ptbak_v1.ptbak"])
    fun `the wrong passphrase opens nothing`(file: String) {
        assertThat(PtbakCodec.import(Resources.text("$dir/$file"), "$PASSPHRASE!")).isNull()
        assertThat(PtbakCodec.import(Resources.text("$dir/$file"), "")).isNull()
    }

    @Test
    fun `the Argon2id parameters are read from the file, not assumed`() {
        // ptbak_v3_params carries m=4096 t=3 p=2. Forcing the defaults back into its kdf block makes the
        // AAD and the key differ from what it was sealed with: the tag must then fail.
        val forged = editV3(Resources.text("$dir/ptbak_v3_params.ptbak")) { kdf ->
            kdf + mapOf("m" to JsonPrimitive(19_456), "t" to JsonPrimitive(2), "p" to JsonPrimitive(1))
        }
        assertThat(PtbakCodec.import(forged, PASSPHRASE)).isNull()
    }

    @Test
    fun `an altered ciphertext is refused`() {
        val original = Json.parseToJsonElement(Resources.text("$dir/ptbak_v3.ptbak")).jsonObject
        val cipher = original.getValue("cipher").jsonObject
        val data = Base64.getDecoder().decode(cipher.getValue("data").jsonPrimitive.content)
        data[data.size / 2] = (data[data.size / 2].toInt() xor 0x01).toByte()
        val forged = JsonObject(
            original + ("cipher" to JsonObject(cipher + ("data" to JsonPrimitive(Base64.getEncoder().encodeToString(data))))),
        )
        assertThat(PtbakCodec.import(forged.toString(), PASSPHRASE)).isNull()
    }

    @Test
    fun `forged headers are refused before any derivation`() {
        val v3 = Resources.text("$dir/ptbak_v3.ptbak")
        val root = Json.parseToJsonElement(v3).jsonObject
        fun with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(root + (key to value)).toString()

        assertThat(PtbakCodec.import(with("magic", JsonPrimitive("PTBAX")), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("version", JsonPrimitive(0)), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("version", JsonPrimitive(4)), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("version", JsonPrimitive(99_999)), PASSPHRASE)).isNull()
        // Dart's `as int?` refuses a quoted number and a decimal; so must we.
        assertThat(PtbakCodec.import(with("version", JsonPrimitive("3")), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("version", JsonPrimitive(3.0)), PASSPHRASE)).isNull()

        assertThat(PtbakCodec.import(editV3(v3) { it + ("algo" to JsonPrimitive("pbkdf2")) }, PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(editV3(v3) { it + ("m" to JsonPrimitive(1_048_576)) }, PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(editV3(v3) { it + ("m" to JsonPrimitive("19456")) }, PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(editV3(v3) { it + ("t" to JsonPrimitive(17)) }, PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(editV3(v3) { it + ("p" to JsonPrimitive(5)) }, PASSPHRASE)).isNull()
        assertThat(
            PtbakCodec.import(editV3(v3) { it + ("salt" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(8)))) }, PASSPHRASE),
        ).isNull()

        assertThat(PtbakCodec.import("[]", PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import("not json", PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import("", PASSPHRASE)).isNull()
    }

    @Test
    fun `legacy headers are bounded like in 2_7_1`() {
        val root = Json.parseToJsonElement(Resources.text("$dir/ptbak_v2.ptbak")).jsonObject
        fun with(key: String, value: JsonPrimitive) = JsonObject(root + (key to value)).toString()

        assertThat(PtbakCodec.import(with("mac", JsonPrimitive("AAA=")), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("iterations", JsonPrimitive(0)), PASSPHRASE)).isNull()
        assertThat(PtbakCodec.import(with("iterations", JsonPrimitive(2_000_001)), PASSPHRASE)).isNull()
        // The MAC covers the version: a v2 file relabelled v1 must not verify.
        assertThat(PtbakCodec.import(with("version", JsonPrimitive(1)), PASSPHRASE)).isNull()
    }

    @Test
    fun `an oversized file is refused without being parsed`() {
        val huge = " ".repeat(PtbakCodec.MAX_FILE_CHARS + 1)
        assertThat(PtbakCodec.import(huge, PASSPHRASE)).isNull()
    }

    @Test
    fun `export writes the exact v3 layout of 2_7_1 and reads back`() {
        val exported = PtbakCodec.export(fixture, PASSPHRASE)
        val root = Json.parseToJsonElement(exported).jsonObject

        assertThat(root.keys).containsExactly("magic", "version", "kdf", "cipher").inOrder()
        assertThat(root.getValue("magic").jsonPrimitive.content).isEqualTo("PTBAK")
        assertThat(root.getValue("version").jsonPrimitive.int).isEqualTo(3)
        val kdf = root.getValue("kdf").jsonObject
        assertThat(kdf.keys).containsExactly("algo", "m", "t", "p", "salt").inOrder()
        assertThat(kdf.getValue("m").jsonPrimitive.int).isEqualTo(19_456)
        assertThat(kdf.getValue("t").jsonPrimitive.int).isEqualTo(2)
        assertThat(kdf.getValue("p").jsonPrimitive.int).isEqualTo(1)
        assertThat(Base64.getDecoder().decode(kdf.getValue("salt").jsonPrimitive.content)).hasLength(32)
        val cipher = root.getValue("cipher").jsonObject
        assertThat(cipher.keys).containsExactly("nonce", "data").inOrder()
        assertThat(Base64.getDecoder().decode(cipher.getValue("nonce").jsonPrimitive.content)).hasLength(12)
        // No entry count and no date in clear: 2.7.1 removed them on 2026-08-03.
        assertThat(exported).doesNotContain("count")
        assertThat(exported).doesNotContain("exportedAt")

        val imported = PtbakCodec.import(exported, PASSPHRASE)
        assertThat(EntryJson.toJsonArray(imported!!.entries)).isEqualTo(expectedJson)
    }

    @Test
    fun `two exports of the same entries share no salt and no nonce`() {
        val a = Json.parseToJsonElement(PtbakCodec.export(fixture, PASSPHRASE)).jsonObject
        val b = Json.parseToJsonElement(PtbakCodec.export(fixture, PASSPHRASE)).jsonObject
        assertThat(a.getValue("kdf").jsonObject["salt"]).isNotEqualTo(b.getValue("kdf").jsonObject["salt"])
        assertThat(a.getValue("cipher").jsonObject["nonce"]).isNotEqualTo(b.getValue("cipher").jsonObject["nonce"])
    }

    /**
     * Writes backups made by THIS code for the Flutter side of the check: `tools/compat` imports them
     * with the 2.7.1 reader (group "verify"). This is what proves the way back.
     */
    @Test
    fun `backups for the 2_7_1 reader`() {
        val out = Resources.outputDir("from-kotlin")
        out.resolve("fixture.ptbak").writeText(PtbakCodec.export(fixture, PASSPHRASE))
        out.resolve("fixture.expected.json").writeText(expectedJson.toString())
        out.resolve("params.ptbak").writeText(
            PtbakCodec.export(fixture, PASSPHRASE, KdfParams(memoryKiB = 4_096, iterations = 1, parallelism = 1)),
        )
        out.resolve("params.expected.json").writeText(expectedJson.toString())
        out.resolve("empty.ptbak").writeText(PtbakCodec.export(emptyList(), PASSPHRASE))
        out.resolve("empty.expected.json").writeText(JsonArray(emptyList()).toString())
        assertThat(out.listFiles()?.size).isAtLeast(6)
    }

    private fun editV3(content: String, edit: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>): String {
        val root = Json.parseToJsonElement(content).jsonObject
        val kdf = root.getValue("kdf").jsonObject
        return JsonObject(root + ("kdf" to JsonObject(edit(kdf)))).toString()
    }

    private companion object {
        const val PASSPHRASE = "Correct horse — batterie agrafée ✓ 2026"
    }
}
