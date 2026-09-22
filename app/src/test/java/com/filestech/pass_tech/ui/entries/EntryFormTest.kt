package com.filestech.pass_tech.ui.entries

import androidx.compose.ui.text.input.TextFieldValue
import com.filestech.pass_tech.core.model.Categories
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.totp.Totp
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The editor's rules, as 2.7.1's `entry_edit_screen.dart` applies them. */
class EntryFormTest {

    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val created = requireNotNull(DartDateTime.parseOrNull("2026-01-02T03:04:05.678"))
    private val later = requireNotNull(DartDateTime.parseOrNull("2026-09-22T14:00:00.000"))

    private fun saved(type: EntryType = EntryType.PASSWORD) = Entry(
        id = "id-1",
        type = type,
        title = "Mail",
        category = Categories.EMAIL,
        username = "alice",
        password = "  pass with spaces  ",
        cardNumber = "4970-1012",
        createdAt = created,
        updatedAt = created,
    )

    @Test
    fun `a new card goes to Bank, anything else to Other`() {
        assertThat(EntryForm.new(EntryType.CARD).category).isEqualTo(Categories.BANK)
        assertThat(EntryForm.new(EntryType.NOTE).category).isEqualTo(Categories.OTHER)
        assertThat(EntryForm.new(EntryType.PASSWORD).category).isEqualTo(Categories.OTHER)
    }

    @Test
    fun `the card number keeps 19 ASCII digits, in groups of 4, cursor at the end`() {
        val form = EntryForm.new(EntryType.CARD)
        form.changeCardNumber(TextFieldValue("4970a10 12-3456٧7890 12345"))
        assertThat(form.cardNumber.text).isEqualTo("4970 1012 3456 7890 123")
        assertThat(form.cardNumber.selection.start).isEqualTo(form.cardNumber.text.length)
    }

    @Test
    fun `the expiry becomes MM slash YY, and the slash goes when the year does`() {
        val form = EntryForm.new(EntryType.CARD)
        form.changeCardExpiry(TextFieldValue("12275"))
        assertThat(form.cardExpiry.text).isEqualTo("12/27")
        form.changeCardExpiry(TextFieldValue("12/"))
        assertThat(form.cardExpiry.text).isEqualTo("12")
    }

    @Test
    fun `CVV and PIN keep digits only, 4 and 8 at most`() {
        val form = EntryForm.new(EntryType.CARD)
        form.changeCardCvv("12a345")
        form.changeCardPin("1234 56789")
        assertThat(form.cardCvv).isEqualTo("1234")
        assertThat(form.cardPin).isEqualTo("12345678")
    }

    @Test
    fun `a pasted otpauth URI leaves only its secret, anything else stays as typed`() {
        val form = EntryForm.new(EntryType.PASSWORD)
        assertThat(form.changeTotpSecret("otpauth://totp/X:alice?secret=$secret&issuer=X")).isTrue()
        assertThat(form.totpSecret).isEqualTo(secret)
        assertThat(form.changeTotpSecret("otpauth://totp/X?secret=ABC1")).isFalse()
        assertThat(form.totpSecret).isEqualTo("otpauth://totp/X?secret=ABC1")
        assertThat(form.changeTotpSecret("JBSW")).isFalse()
        assertThat(form.totpSecret).isEqualTo("JBSW")
    }

    @Test
    fun `the title is required first, then a valid 2FA secret, which the next keystroke unflags`() {
        val form = EntryForm.new(EntryType.PASSWORD)
        form.changeTotpSecret("JBSW")
        assertThat(form.validate()).isEqualTo(EntryForm.Problem.TITLE_REQUIRED)
        form.title = "  "
        assertThat(form.validate()).isEqualTo(EntryForm.Problem.TITLE_REQUIRED)
        form.title = "Bank"
        assertThat(form.validate()).isEqualTo(EntryForm.Problem.INVALID_TOTP)
        assertThat(form.totpError).isEqualTo(Totp.SecretError.TOO_SHORT)
        form.changeTotpSecret(secret)
        assertThat(form.totpError).isNull()
        assertThat(form.validate()).isNull()
    }

    @Test
    fun `only a password entry checks its 2FA secret`() {
        val note = EntryForm.edit(saved(EntryType.NOTE).copy(totpSecret = "garbage"))
        assertThat(note.validate()).isNull()
    }

    @Test
    fun `saving trims every field but the password, and stores the card number without spaces`() {
        val form = EntryForm.new(EntryType.CARD)
        form.title = "  Visa  "
        form.password = "  keep  "
        form.cardholder = " Alice "
        form.changeCardNumber(TextFieldValue("4970101234567890"))
        val entry = form.toEntry(later) { "new-id" }
        assertThat(entry.id).isEqualTo("new-id")
        assertThat(entry.title).isEqualTo("Visa")
        assertThat(entry.password).isEqualTo("  keep  ")
        assertThat(entry.cardholderName).isEqualTo("Alice")
        assertThat(entry.cardNumber).isEqualTo("4970101234567890")
        assertThat(entry.createdAt).isEqualTo(later)
        assertThat(entry.updatedAt).isEqualTo(later)
    }

    @Test
    fun `an edited entry keeps its id, type and creation date, and nothing is reformatted on load`() {
        val form = EntryForm.edit(saved())
        assertThat(form.cardNumber.text).isEqualTo("4970-1012")
        form.title = "Mail 2"
        val entry = form.toEntry(later) { error("an edited entry keeps its id") }
        assertThat(entry.id).isEqualTo("id-1")
        assertThat(entry.type).isEqualTo(EntryType.PASSWORD)
        assertThat(entry.createdAt).isEqualTo(created)
        assertThat(entry.updatedAt).isEqualTo(later)
        assertThat(entry.password).isEqualTo("  pass with spaces  ")
    }

    @Test
    fun `leaving asks only when something differs from what was loaded`() {
        val form = EntryForm.edit(saved())
        assertThat(form.hasChanges).isFalse()
        form.favorite = true
        assertThat(form.hasChanges).isTrue()
        form.favorite = false
        assertThat(form.hasChanges).isFalse()
        form.changeCardNumber(TextFieldValue("4970"))
        assertThat(form.hasChanges).isTrue()
        assertThat(EntryForm.new(EntryType.NOTE).hasChanges).isFalse()
    }
}
