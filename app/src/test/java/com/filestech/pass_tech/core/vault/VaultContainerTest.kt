package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class VaultContainerTest {

    private val keystore = InMemorySlotKeystore()
    private val password = "correct horse battery staple".encodeToByteArray()
    private val payload = Padding.pad("""{"entries":[],"meta":{}}""".encodeToByteArray(), Padding.FIRST_BUCKET)

    private fun sealedFile(slot: Slot = Slot.A): String {
        val header = VaultContainer.newHeader(slot, keystore)
        val key = requireNotNull(VaultContainer.deriveKeyOrNull(header, password, keystore))
        return VaultContainer.seal(header, key, payload, OccupancyMark.occupied().seal(slot, keystore))
    }

    private fun open(content: String, slot: Slot = Slot.A, pw: ByteArray = password): ByteArray? =
        VaultContainer.parseOrNull(content, slot)?.let { parsed ->
            VaultContainer.deriveKeyOrNull(parsed.header, pw, keystore)?.let { key -> VaultContainer.openOrNull(parsed, key) }
        }

    @Test
    fun `a sealed slot opens with its password and gives the padded payload back`() {
        assertThat(open(sealedFile())).isEqualTo(payload)
    }

    @Test
    fun `the wrong password opens nothing`() {
        assertThat(open(sealedFile(), pw = "correct horse battery stapl".encodeToByteArray())).isNull()
    }

    @Test
    fun `a file copied into another slot is refused`() {
        assertThat(open(sealedFile(Slot.A), slot = Slot.B)).isNull()
    }

    @Test
    fun `relabelling a file for another slot breaks its authentication`() {
        // Even with the slot field rewritten to match, the AAD binds the original slot.
        keystore.ensureKey(Slot.B)
        val root = Json.parseToJsonElement(sealedFile(Slot.A)).jsonObject
        val relabelled = JsonObject(root + ("slot" to JsonPrimitive("b"))).toString()
        assertThat(open(relabelled, slot = Slot.B)).isNull()
    }

    @Test
    fun `the Argon2id parameters are bound to the ciphertext`() {
        val root = Json.parseToJsonElement(sealedFile()).jsonObject
        val kdf = root.getValue("kdf").jsonObject
        val forged = JsonObject(root + ("kdf" to JsonObject(kdf + ("t" to JsonPrimitive(3))))).toString()
        assertThat(open(forged)).isNull()
    }

    @Test
    fun `without the Keystore key of the slot, the password alone opens nothing`() {
        val file = sealedFile()
        keystore.deleteKey(Slot.A)
        keystore.ensureKey(Slot.A)
        assertThat(open(file)).isNull()
    }

    @Test
    fun `out-of-bounds parameters are refused before any derivation`() {
        val root = Json.parseToJsonElement(sealedFile()).jsonObject
        val kdf = root.getValue("kdf").jsonObject
        val hostile = JsonObject(root + ("kdf" to JsonObject(kdf + ("m" to JsonPrimitive(KdfParams.MAX_MEMORY_KIB + 1))))).toString()
        assertThat(VaultContainer.parseOrNull(hostile, Slot.A)).isNull()
    }

    @Test
    fun `foreign and damaged content is refused`() {
        assertThat(VaultContainer.parseOrNull("", Slot.A)).isNull()
        assertThat(VaultContainer.parseOrNull("[]", Slot.A)).isNull()
        assertThat(VaultContainer.parseOrNull("""{"magic":"PTVAULT","version":4}""", Slot.A)).isNull()
        val truncated = sealedFile().let { it.substring(0, it.length / 2) }
        assertThat(VaultContainer.parseOrNull(truncated, Slot.A)).isNull()
    }

    @Test
    fun `two seals of the same payload share no salt and no nonce`() {
        val a = Json.parseToJsonElement(sealedFile()).jsonObject
        val b = Json.parseToJsonElement(sealedFile()).jsonObject
        assertThat(a.getValue("kdf").jsonObject["salt"]).isNotEqualTo(b.getValue("kdf").jsonObject["salt"])
        assertThat(a.getValue("cipher").jsonObject["nonce"]).isNotEqualTo(b.getValue("cipher").jsonObject["nonce"])
    }
}
