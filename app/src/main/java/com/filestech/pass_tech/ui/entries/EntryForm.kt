package com.filestech.pass_tech.ui.entries

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.filestech.pass_tech.core.model.Categories
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.totp.Totp

/**
 * What the entry editor holds while the user types (2.7.1, `entry_edit_screen.dart`): every field of
 * every type, loaded from the entry being edited, and the 2.7.1 rules for each. Never saved into the
 * activity's saved state: it holds secrets.
 *
 * A field is only reformatted when the user edits it, never on load, as 2.7.1 does: a card number
 * imported with other characters stays as it is until the user touches it.
 */
class EntryForm private constructor(val type: EntryType, private val original: Entry?, suggest: String?) {

    enum class Problem { TITLE_REQUIRED, INVALID_TOTP }

    val isEdit: Boolean get() = original != null

    var category by mutableStateOf(original?.category ?: Categories.defaultFor(type))
    var favorite by mutableStateOf(original?.isFavorite ?: false)
    var title by mutableStateOf(original?.title.orEmpty())
    var username by mutableStateOf(original?.username.orEmpty())
    var password by mutableStateOf(original?.password.orEmpty())
    var url by mutableStateOf(original?.url.orEmpty())

    /** Free text, one domain per line: the list is rebuilt from it at save, never as it is typed. */
    var otherDomains by mutableStateOf(original?.otherDomains.orEmpty().joinToString("\n"))
    var totpSecret by mutableStateOf(original?.totpSecret.orEmpty())
        private set
    var notes by mutableStateOf(original?.notes.orEmpty())
    var cardholder by mutableStateOf(original?.cardholderName.orEmpty())
    var cardNumber by mutableStateOf(TextFieldValue(original?.cardNumber.orEmpty()))
        private set
    var cardExpiry by mutableStateOf(TextFieldValue(original?.cardExpiry.orEmpty()))
        private set
    var cardCvv by mutableStateOf(original?.cardCvv.orEmpty())
        private set
    var cardPin by mutableStateOf(original?.cardPin.orEmpty())
        private set
    var cardIssuer by mutableStateOf(original?.cardIssuer.orEmpty())

    /** Set by [validate] when the 2FA secret is refused; cleared as soon as the field changes. */
    var totpError by mutableStateOf<Totp.SecretError?>(null)
        private set

    /**
     * The domain the anti-phishing check has just refused, offered above the field so it need not be
     * read off a dialog and typed back in. Only ever set when the editor was opened from that refusal,
     * and gone once added — adding it changes nothing until the entry is saved.
     */
    var suggestedDomain by mutableStateOf(suggest)
        private set

    /** Puts [suggestedDomain] into the field. The save is what declares it. */
    fun acceptSuggestedDomain() {
        val domain = suggestedDomain ?: return
        otherDomains = (domains(otherDomains) + domain).distinct().joinToString("\n")
        suggestedDomain = null
    }

    /** A save is running: the Save button waits. */
    var saving by mutableStateOf(false)

    private val initial = snapshot()

    /** Something differs from what was loaded: leaving asks first (2.7.1, UX 2026-08-04). */
    val hasChanges: Boolean get() = snapshot() != initial

    /**
     * The 2FA field. A pasted `otpauth://` URI is replaced by the secret it carries. Returns `true`
     * when that happened, for the "secret added" message.
     */
    fun changeTotpSecret(value: String): Boolean {
        totpError = null
        val secret = Totp.secretFromUri(value)
        totpSecret = secret ?: value
        return secret != null
    }

    /** Digits only (ASCII, as Flutter's `digitsOnly`), 19 at most, in groups of 4. */
    fun changeCardNumber(value: TextFieldValue) {
        cardNumber = atEnd(digits(value.text).take(CARD_NUMBER_DIGITS).chunked(GROUP).joinToString(" "))
    }

    /** Digits only, 4 at most, "MM/YY". */
    fun changeCardExpiry(value: TextFieldValue) {
        val digits = digits(value.text).take(EXPIRY_DIGITS)
        cardExpiry = atEnd(if (digits.length <= 2) digits else digits.take(2) + "/" + digits.drop(2))
    }

    fun changeCardCvv(value: String) {
        cardCvv = digits(value).take(CVV_DIGITS)
    }

    fun changeCardPin(value: String) {
        cardPin = digits(value).take(PIN_DIGITS)
    }

    /** `null` if the entry can be saved. The title first, then the 2FA secret of a password entry. */
    fun validate(): Problem? {
        if (title.isBlank()) return Problem.TITLE_REQUIRED
        val secret = totpSecret.trim()
        if (type == EntryType.PASSWORD && secret.isNotEmpty()) {
            totpError = Totp.validate(secret)
            if (totpError != null) return Problem.INVALID_TOTP
        }
        return null
    }

    /**
     * The entry to save, trimmed as 2.7.1 trims it (the password never is). An edited entry keeps its
     * id and creation date; a new one gets [newId].
     */
    fun toEntry(now: DartDateTime, newId: () -> String): Entry =
        Entry(
            id = original?.id ?: newId(),
            type = type,
            title = title.trim(),
            category = category,
            username = username.trim(),
            password = password,
            url = url.trim(),
            otherDomains = domains(otherDomains).take(Entry.MAX_OTHER_DOMAINS),
            totpSecret = totpSecret.trim(),
            notes = notes.trim(),
            isFavorite = favorite,
            cardholderName = cardholder.trim(),
            cardNumber = cardNumber.text.replace(" ", "").trim(),
            cardExpiry = cardExpiry.text.trim(),
            cardCvv = cardCvv.trim(),
            cardPin = cardPin.trim(),
            cardIssuer = cardIssuer.trim(),
            createdAt = original?.createdAt ?: now,
            updatedAt = now,
        )

    private fun snapshot(): List<Any> = listOf(
        category, favorite, title, username, password, url, otherDomains, totpSecret, notes,
        cardholder, cardNumber.text, cardExpiry.text, cardCvv, cardPin, cardIssuer,
    )

    companion object {
        private const val CARD_NUMBER_DIGITS = 19
        private const val GROUP = 4
        private const val EXPIRY_DIGITS = 4
        private const val CVV_DIGITS = 4
        private const val PIN_DIGITS = 8

        fun new(type: EntryType) = EntryForm(type, null, suggest = null)

        /**
         * The type of an entry cannot change once created (2.7.1). [suggest] is the domain a refused
         * copy wants declared here: it is offered, never added.
         */
        fun edit(entry: Entry, suggest: String? = null) = EntryForm(entry.type, entry, suggest)

        private fun digits(text: String) = text.filter { it in '0'..'9' }

        /** No domain holds a space, a comma or a semicolon, so all three separate a pasted list. */
        private fun domains(text: String): List<String> =
            text.split('\n', ',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** 2.7.1's formatters put the cursor at the end. */
        private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))
    }
}
