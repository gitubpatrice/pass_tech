package com.filestech.pass_tech.core.state

import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import com.filestech.pass_tech.core.storage.AtomicFiles
import com.filestech.pass_tech.core.vault.KeyResult
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.SlotKeystore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * Small app state that must not be readable in clear on disk: the brute-force counters, the clock
 * floor, the heir state and the biometric binding. Sealed with the Keystore key [KEY_ALIAS].
 *
 * Losing this file must never cost a vault (design v2 §4): vault occupancy lives in the vault files
 * themselves, never here. A LOST state (no file, a damaged file, a key that is gone, data that do not
 * authenticate) reads as empty, which at worst resets the lockout delays and disarms the heir and
 * biometric features.
 *
 * A Keystore that does not ANSWER is not a lost state: the file is probably fine. Reading it as empty
 * would reset the lockout, and anyone able to keep the secure hardware busy could then guess without
 * delay (GPT 5.6 review of the design, v2.2). Both [read] and [update] throw
 * [KeystoreUnavailableException] instead, and nothing is written.
 */
class StateStore(private val file: File, private val keystore: SlotKeystore) {

    private val lock = Any()

    fun read(): JsonObject = synchronized(lock) { readUnlocked() }

    /** Applies [transform] to the current state and writes the result atomically. */
    fun update(transform: (JsonObject) -> JsonObject): JsonObject =
        synchronized(lock) {
            transform(readUnlocked()).also(::writeUnlocked)
        }

    fun clear() {
        synchronized(lock) { file.delete() }
    }

    private fun readUnlocked(): JsonObject {
        val content = file.takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: return EMPTY
        val wrapped = readOrNull {
            val sealed = (JSON.parseToJsonElement(content) as? JsonObject).orReject()
            ensure(sealed.requireString("magic") == MAGIC)
            SlotKeystore.Wrapped(
                ciphertext = Base64.getDecoder().decode(sealed.requireString("data")),
                nonce = Base64.getDecoder().decode(sealed.requireString("nonce")),
            )
        } ?: return EMPTY
        return when (val plain = keystore.unwrap(KEY_ALIAS, wrapped)) {
            is KeyResult.Done -> plain.value.useThenWipe(::parseState) ?: EMPTY
            KeyResult.NoKey, KeyResult.Refused -> EMPTY
            KeyResult.Unavailable -> throw KeystoreUnavailableException()
        }
    }

    private fun parseState(plain: ByteArray): JsonObject? =
        readOrNull { (JSON.parseToJsonElement(plain.decodeToString()) as? JsonObject).orReject() }

    private fun writeUnlocked(state: JsonObject) {
        keystore.ensureAesKey(KEY_ALIAS)
        val wrapped = keystore.wrap(KEY_ALIAS, JSON.encodeToString(JsonObject.serializer(), state).encodeToByteArray())
        val sealed = buildJsonObject {
            put("magic", MAGIC)
            put("nonce", Base64.getEncoder().encodeToString(wrapped.nonce))
            put("data", Base64.getEncoder().encodeToString(wrapped.ciphertext))
        }
        AtomicFiles.write(file, JSON.encodeToString(JsonObject.serializer(), sealed).encodeToByteArray())
    }

    companion object {
        const val KEY_ALIAS = "pt_state"
        const val FILE_NAME = "pt_state.enc"
        private const val MAGIC = "PTSTATE1"
        private val JSON = Json
        private val EMPTY = JsonObject(emptyMap())
    }
}
