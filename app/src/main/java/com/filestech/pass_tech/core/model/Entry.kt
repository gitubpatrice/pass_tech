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
 *
 * [otherDomains] is the one field 2.7.1 does not know. It is written only when it holds something,
 * so an entry that never used it is byte-identical to what 2.7.1 wrote, and a 2.7.1 reader — which
 * ignores keys it does not know — opens a 3.0 backup whatever happens.
 */
data class Entry(
    val id: String,
    val type: EntryType = EntryType.PASSWORD,
    val title: String,
    val category: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    /**
     * The other addresses this same account signs in at: `login.microsoftonline.com` for an
     * `office.com` entry, a company's SSO, another country's extension. The anti-phishing check
     * accepts them exactly as it accepts [url] — see `DomainMatch.check`.
     */
    val otherDomains: List<String> = emptyList(),
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

        /**
         * How many [otherDomains] an entry may carry. The anti-phishing check measures an edit
         * distance against each of them before every password copy, so an unbounded list — pasted
         * by hand, or imported from a vault that holds hundreds of addresses — would be paid for on
         * the one action that must stay instant.
         */
        const val MAX_OTHER_DOMAINS = 16
    }
}
