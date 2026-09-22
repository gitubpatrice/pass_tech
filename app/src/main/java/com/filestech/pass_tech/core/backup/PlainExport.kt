package com.filestech.pass_tech.core.backup

import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The export that protects nothing: every password, card number and note in clear, indented, as 2.7.1
 * writes it (`VaultService.exportJson`). The screen asks twice and re-authenticates before calling this.
 *
 * It is the same shape as a Pass Tech backup's content, so [ImportParser] reads it straight back.
 */
object PlainExport {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(entries: List<Entry>): String = json.encodeToString(JsonArray.serializer(), EntryJson.toJsonArray(entries))
}
