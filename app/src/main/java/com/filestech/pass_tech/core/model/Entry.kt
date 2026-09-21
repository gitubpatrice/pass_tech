package com.filestech.pass_tech.core.model

enum class EntryType(val wireName: String) {
    PASSWORD("password"),
    NOTE("note"),
    CARD("card"),
    ;

    companion object {
        /** As in the Flutter app, an unknown or missing type reads as a password entry. */
        fun fromWireName(name: String?): EntryType = entries.firstOrNull { it.wireName == name } ?: PASSWORD
    }
}

/**
 * One vault entry. The field set and the JSON names are those of the Flutter app (2.7.1,
 * `lib/models/entry.dart`), so that backups move both ways without loss.
 *
 * `category` is stored as the canonical FRENCH name (`Banque`, `Réseaux sociaux`...) and translated
 * at display time, like in the Flutter app: translating it in storage would break the category of
 * every entry the day the language changes.
 */
data class Entry(
    val id: String,
    val type: EntryType = EntryType.PASSWORD,
    val title: String,
    val category: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val totpSecret: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val cardholderName: String = "",
    val cardNumber: String = "",
    val cardExpiry: String = "",
    val cardCvv: String = "",
    val cardPin: String = "",
    val cardIssuer: String = "",
    val createdAt: DartDateTime,
    val updatedAt: DartDateTime,
) {
    /** Never the fields: a data class would print the password into any log or crash report. */
    override fun toString(): String = "Entry(id=$id, type=$type)"

    companion object {
        const val DEFAULT_CATEGORY = "Autres"
    }
}
