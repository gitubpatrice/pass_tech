package com.filestech.pass_tech.core.state

import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import com.filestech.pass_tech.core.storage.AtomicFiles
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
 * themselves, never here. An unreadable state reads as empty, which at worst resets the lockout
 * delays and disarms the heir and biometric features.
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
        return readOrNull {
            val sealed = (JSON.parseToJsonElement(content) as? JsonObject).orReject()
            ensure(sealed.requireString("magic") == MAGIC)
            val wrapped = SlotKeystore.Wrapped(
                ciphertext = Base64.getDecoder().decode(sealed.requireString("data")),
                nonce = Base64.getDecoder().decode(sealed.requireString("nonce")),
            )
            val plain = keystore.unwrapOrNull(KEY_ALIAS, wrapped).orReject()
            (JSON.parseToJsonElement(plain.decodeToString()) as? JsonObject).orReject()
        } ?: EMPTY
    }

    private fun writeUnlocked(state: JsonObject) {
        keystore.ensureKey(KEY_ALIAS)
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
