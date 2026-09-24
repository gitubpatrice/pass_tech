package com.filestech.pass_tech.core.model

/**
 * The seven categories of 2.7.1 (`lib/models/category.dart`), by their canonical FRENCH names: that is
 * what the vault and the backups store, whatever the language of the app (see [Entry.category]).
 */
object Categories {
    const val WEB = "Web"
    const val EMAIL = "Email"
    const val BANK = "Banque"
    const val SOCIAL = "Réseaux sociaux"
    const val APPS = "Apps"
    const val CARDS = "Cartes"
    const val OTHER = Entry.DEFAULT_CATEGORY

    val ALL = listOf(WEB, EMAIL, BANK, SOCIAL, APPS, CARDS, OTHER)

    /** 2.7.1's default for a new entry: Bank for a card, Other for the rest. */
    fun defaultFor(type: EntryType): String = if (type == EntryType.CARD) BANK else OTHER
}
