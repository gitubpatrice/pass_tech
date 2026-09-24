package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.objectOrNull
import com.filestech.pass_tech.core.json.optBoolean
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A decoy created by a vault: which slot, and which incarnation of it (design v2 §3). */
data class ChildRef(val slot: Slot, val generation: String)

/**
 * What a vault knows about itself, encrypted with it (design v2 §3). Nothing here is ever readable
 * from another vault, which is what keeps the decoy session blank.
 */
data class VaultMeta(
    /** This incarnation, the same as in the slot's occupancy mark. */
    val generation: String,
    /** Created while no other slot was occupied: only a root vault may erase every slot. */
    val root: Boolean,
    /** The decoy this vault created, if any. At most one per vault. */
    val child: ChildRef?,
    /**
     * A decoy being created (design v2.1 §3): written BEFORE the child slot, confirmed or dropped at
     * the next opening. Without it, a crash between the two writes left an orphan decoy.
     */
    val pendingChild: ChildRef? = null,
)

/** The decrypted content of a vault slot. */
data class VaultPayload(val entries: List<Entry>, val meta: VaultMeta) {

    fun toBytes(): ByteArray {
        val json = buildJsonObject {
            put("entries", EntryJson.toJsonArray(entries))
            putJsonObject("meta") {
                put("gen", meta.generation)
                put("root", meta.root)
                putJsonArray("children") { meta.child?.let { add(it.toJson()) } }
                meta.pendingChild?.let { put("pendingChild", it.toJson()) }
            }
        }
        return JSON.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
    }

    companion object {
        private val JSON = Json

        /**
         * Reads a decrypted, padded payload. STRICT: a single entry that does not read refuses the
         * whole vault. Skipping it would lose it at the next save, silently.
         */
        fun fromPaddedBytesOrNull(padded: ByteArray): VaultPayload? =
            readOrNull {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(padded, 0, Padding.unpaddedLength(padded)))
                    .toString()
                val root = (JSON.parseToJsonElement(text) as? JsonObject).orReject()
                val entriesJson = (root["entries"] as? JsonArray).orReject()
                val entries = entriesJson.map { EntryJson.fromJsonOrNull(it).orReject() }
                val meta = root.objectOrNull("meta").orReject()
                val children = (meta["children"] as? JsonArray).orReject()
                ensure(children.size <= 1)
                VaultPayload(
                    entries = entries,
                    meta = VaultMeta(
                        generation = meta.requireString("gen"),
                        root = meta.optBoolean("root").orReject(),
                        child = children.firstOrNull()?.let(::childRef),
                        pendingChild = meta["pendingChild"]?.let(::childRef),
                    ),
                )
            }

        private fun ChildRef.toJson(): JsonObject = buildJsonObject {
            put("slot", slot.label)
            put("gen", generation)
        }

        private fun childRef(element: JsonElement): ChildRef {
            val obj = (element as? JsonObject).orReject()
            return ChildRef(Slot.fromLabel(obj.requireString("slot")).orReject(), obj.requireString("gen"))
        }
    }
}
