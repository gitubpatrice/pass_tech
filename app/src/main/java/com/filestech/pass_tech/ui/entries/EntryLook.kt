package com.filestech.pass_tech.ui.entries

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.Categories
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType

/** How categories and entry types look: 2.7.1's icons and colours (`models/category.dart`, `home_screen.dart`). */
object EntryLook {

    private val Blue = Color(0xFF58A6FF)
    private val Orange = Color(0xFFFF7043)
    private val Green = Color(0xFF43A047)
    private val Purple = Color(0xFF7B1FA2)
    private val Teal = Color(0xFF00897B)
    private val Yellow = Color(0xFFFDD835)
    private val Grey = Color(0xFF8B949E)

    /** The colours of the note and card choices, and of their entries. */
    val NoteColor = Orange
    val CardColor = Green

    /** The colour of the "Password" choice of the add sheet (2.7.1: the Web blue). */
    val PasswordChoiceColor = Blue

    @StringRes
    fun categoryLabel(category: String): Int? = when (category) {
        Categories.WEB -> R.string.category_web
        Categories.EMAIL -> R.string.category_email
        Categories.BANK -> R.string.category_bank
        Categories.SOCIAL -> R.string.category_social
        Categories.APPS -> R.string.category_apps
        Categories.CARDS -> R.string.category_cards
        Categories.OTHER -> R.string.category_other
        // A category from an import that 2.7.1 does not know: shown as it is stored.
        else -> null
    }

    fun categoryIcon(category: String): ImageVector = when (category) {
        Categories.WEB -> Icons.Filled.Language
        Categories.EMAIL -> Icons.Outlined.Email
        Categories.BANK -> Icons.Outlined.AccountBalance
        Categories.SOCIAL -> Icons.Outlined.PeopleOutline
        Categories.APPS -> Icons.Filled.Apps
        Categories.CARDS -> Icons.Outlined.CreditCard
        else -> Icons.Outlined.Folder
    }

    fun categoryColor(category: String): Color = when (category) {
        Categories.WEB -> Blue
        Categories.EMAIL -> Orange
        Categories.BANK -> Green
        Categories.SOCIAL -> Purple
        Categories.APPS -> Teal
        Categories.CARDS -> Yellow
        else -> Grey
    }

    @StringRes
    fun typeLabel(type: EntryType): Int = when (type) {
        EntryType.PASSWORD -> R.string.entry_type_password
        EntryType.NOTE -> R.string.entry_type_note
        EntryType.CARD -> R.string.entry_type_card
    }

    /** A password entry wears its category; a note and a card wear their type. */
    fun icon(entry: Entry): ImageVector = when (entry.type) {
        EntryType.PASSWORD -> categoryIcon(entry.category)
        EntryType.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
        EntryType.CARD -> Icons.Filled.CreditCard
    }

    fun color(entry: Entry): Color = when (entry.type) {
        EntryType.PASSWORD -> categoryColor(entry.category)
        EntryType.NOTE -> NoteColor
        EntryType.CARD -> CardColor
    }

    /** The second line of a card in the list (2.7.1 `_EntryCard._subtitle`). */
    fun subtitle(entry: Entry): String = when (entry.type) {
        EntryType.PASSWORD -> entry.username
        EntryType.NOTE -> entry.notes.replace('\n', ' ').trim().let {
            if (it.length > NOTE_PREVIEW) it.take(NOTE_PREVIEW) + "…" else it
        }
        EntryType.CARD -> entry.cardNumber.replace(" ", "").let {
            if (it.length >= LAST_DIGITS) "•••• " + it.takeLast(LAST_DIGITS) else entry.cardIssuer
        }
    }

    /** "1234 5678 9012 3456". */
    fun groupCardNumber(number: String): String = number.replace(" ", "").chunked(LAST_DIGITS).joinToString(" ")

    /** All but the last four digits hidden; a number of four digits or fewer shown as it is (2.7.1). */
    fun maskCardNumber(number: String): String {
        val clean = number.replace(" ", "")
        return if (clean.length <= LAST_DIGITS) clean else "•••• •••• •••• " + clean.takeLast(LAST_DIGITS)
    }

    const val NOTE_PREVIEW = 60
    private const val LAST_DIGITS = 4
}
