package com.filestech.pass_tech.core.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Reads decoded JSON with the exact type rules of the Flutter app's `value as T?` casts.
//
// kotlinx.serialization is more forgiving than Dart: `intOrNull` accepts the string "3" and
// `booleanOrNull` the string "true". Dart throws on both, and the Flutter app then refuses the whole
// file or the entry. Accepting what 2.7.1 refuses would make the two apps disagree on the same backup,
// so every read goes through here.
//
// Convention, as in Dart: a missing key and a JSON `null` both read as absent (`null`); a present
// value of the wrong type throws DartCastException.

private val INTEGER_LITERAL = Regex("-?\\d+")

private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }

/** A JSON number or boolean literal, never a string: Dart does not convert "3" into 3. */
private fun JsonElement.literalOrNull(): String? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content

/** `json[key] as String?` */
fun JsonObject.optString(key: String): String? {
    val value = present(key) ?: return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw DartCastException(key)
}

/** `json[key] as String` */
fun JsonObject.requireString(key: String): String = optString(key) ?: throw DartCastException(key)

/**
 * `json[key] as int?`. Dart decodes a number as `int` only when it has no fraction and no exponent:
 * `3.0` is a double there, and the cast fails. Values beyond the Int range are refused too; every
 * integer field of the Pass Tech formats is bounded far below it.
 */
fun JsonObject.optInt(key: String): Int? {
    val value = present(key) ?: return null
    return value.literalOrNull()?.takeIf(INTEGER_LITERAL::matches)?.toIntOrNull() ?: throw DartCastException(key)
}

/** `json[key] as bool?` */
fun JsonObject.optBoolean(key: String): Boolean? {
    val value = present(key) ?: return null
    return when (value.literalOrNull()) {
        "true" -> true
        "false" -> false
        else -> throw DartCastException(key)
    }
}

/** `json[key] is Map ? json[key] : null`: the Flutter app tests the type here, it does not cast. */
fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject
