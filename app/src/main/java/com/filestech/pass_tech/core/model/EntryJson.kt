package com.filestech.pass_tech.core.model

import com.filestech.pass_tech.core.json.DartCastException
import com.filestech.pass_tech.core.json.optBoolean
import com.filestech.pass_tech.core.json.optString
import com.filestech.pass_tech.core.json.requireString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON form of [Entry], identical to `Entry.toJson` / `Entry.fromJson` of the Flutter app: same keys,
 * same order, same defaults, same strictness — plus [Entry.otherDomains], the one field 2.7.1 knows
 * nothing about, written only when it holds something.
 */
object EntryJson {

    private const val OTHER_DOMAINS = "otherDomains"

    /**
     * `Entry.fromJson`, returning `null` where Dart throws: missing `id` or `title`, a field of the wrong
     * type, or a date `DateTime.parse` refuses.
     */
    fun fromJsonOrNull(element: JsonElement): Entry? {
        val json = element as? JsonObject ?: return null
        return try {
            // `json[key] as String? ?? ''`, the default of every optional text field.
            fun text(key: String): String = json.optString(key) ?: ""

            // `DateTime.parse(json[key] as String)`: a date Dart cannot parse rejects the entry.
            fun date(key: String): DartDateTime = DartDateTime.parseOrNull(json.requireString(key)) ?: throw DartCastException(key)

            Entry(
                id = json.requireString("id"),
                type = EntryType.fromWireName(json.optString("type")),
                title = json.requireString("title"),
                category = json.optString("category") ?: Entry.DEFAULT_CATEGORY,
                username = text("username"),
                password = text("password"),
                url = text("url"),
                otherDomains = otherDomains(json),
                totpSecret = text("totpSecret"),
                notes = text("notes"),
                isFavorite = json.optBoolean("isFavorite") ?: false,
                cardholderName = text("cardholderName"),
                cardNumber = text("cardNumber"),
                cardExpiry = text("cardExpiry"),
                cardCvv = text("cardCvv"),
                cardPin = text("cardPin"),
                cardIssuer = text("cardIssuer"),
                createdAt = date("createdAt"),
                updatedAt = date("updatedAt"),
            )
        } catch (_: DartCastException) {
            null
        }
    }

    /**
     * Lenient where the rest of this reader is strict, and deliberately so: a field the Flutter app
     * never wrote can only come from another tool, and a value of the wrong shape there must not cost
     * the owner a password. Reading it as none leaves the check comparing against [Entry.url] alone,
     * which refuses more, not less.
     */
    private fun otherDomains(json: JsonObject): List<String> =
        (json[OTHER_DOMAINS] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(Entry.MAX_OTHER_DOMAINS)
            .orEmpty()

    fun toJson(entry: Entry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("type", entry.type.wireName)
        put("title", entry.title)
        put("category", entry.category)
        put("username", entry.username)
        put("password", entry.password)
        put("url", entry.url)
        // Absent when empty: an entry that does not use it writes exactly the JSON 2.7.1 wrote.
        if (entry.otherDomains.isNotEmpty()) {
            putJsonArray(OTHER_DOMAINS) { entry.otherDomains.forEach { add(it) } }
        }
        put("totpSecret", entry.totpSecret)
        put("notes", entry.notes)
        put("isFavorite", entry.isFavorite)
        put("cardholderName", entry.cardholderName)
        put("cardNumber", entry.cardNumber)
        put("cardExpiry", entry.cardExpiry)
        put("cardCvv", entry.cardCvv)
        put("cardPin", entry.cardPin)
        put("cardIssuer", entry.cardIssuer)
        put("createdAt", entry.createdAt.toIso8601String())
        put("updatedAt", entry.updatedAt.toIso8601String())
    }

    fun toJsonArray(entries: List<Entry>): JsonArray = JsonArray(entries.map(::toJson))
}
