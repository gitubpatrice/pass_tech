package com.filestech.pass_tech.core.audit

import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The audit of a vault: what it counts, what it refuses to count, and what it says when it cannot. */
class VaultAuditTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 23, 12, 0)

    /** The hash of a password, for the tests: itself. The real one is SHA-1, and is not the point here. */
    private val itself: (String) -> String = { it }

    private fun entry(
        id: String,
        password: String = "Fk3!mzPqrs7Lw",
        category: String = "Web",
        totp: String = "",
        type: EntryType = EntryType.PASSWORD,
        updated: LocalDateTime = now,
    ) = Entry(
        id = id,
        type = type,
        title = id,
        category = category,
        password = password,
        totpSecret = totp,
        createdAt = DartDateTime.of(updated, isUtc = false),
        updatedAt = DartDateTime.of(updated, isUtc = false),
    )

    private fun audit(entries: List<Entry>, breached: Set<String>? = null, checked: Set<String>? = null) =
        VaultAudit.of(entries, now, breached, checked, itself)

    @Test
    fun `a vault with no password has no score`() {
        assertThat(audit(emptyList()).score).isNull()
        assertThat(audit(listOf(entry("n", type = EntryType.NOTE, password = ""))).score).isNull()
        // "—" with a partial-score warning beside it was 2.7.1's answer here.
        assertThat(audit(emptyList()).partial).isFalse()
    }

    @Test
    fun `the score is the average health, so it follows the size of the vault`() {
        val sixWeak = (1..6).map { entry("w$it", password = "azerty") }
        val sixWeakAmongTwoHundred = sixWeak + (1..194).map { entry("s$it", password = "Fk3!mzPqrs7Lw$it") }
        // 2.7.1's first formula stopped subtracting at six and answered "70, Good" to both.
        assertThat(audit(sixWeak).score).isEqualTo(AuditScore.WEAK)
        assertThat(audit(sixWeakAmongTwoHundred).score).isGreaterThan(95)
    }

    @Test
    fun `an entry counts for its worst fault, once`() {
        // Weak AND reused AND a sensitive account with no second factor: still 25, not three penalties.
        val twins = listOf(entry("a", password = "azerty", category = "Banque"), entry("b", password = "azerty", category = "Banque"))
        assertThat(audit(twins).score).isEqualTo(AuditScore.WEAK)
    }

    @Test
    fun `the same password on two accounts costs on both`() {
        // Strong, so that the duplicate is the only fault left and the scale has to show it. With a
        // weak one, "worst fault wins" hides this rule entirely — which is how the first version of
        // this file let a sabotage of the duplicate detection pass (2026-09-23).
        val shared = "Fk3!mzPqrs7Lw"
        val result = audit(listOf(entry("a", password = shared), entry("b", password = shared)))
        assertThat(result.duplicates.map { it.id }).containsExactly("a", "b")
        assertThat(result.score).isEqualTo(AuditScore.DUPLICATE)
        // One of each: nothing shared, nothing to report.
        val apart = audit(listOf(entry("a", password = shared), entry("b", password = "Nw4!bxQtuv8Mz")))
        assertThat(apart.duplicates).isEmpty()
        assertThat(apart.score).isEqualTo(AuditScore.HEALTHY)
    }

    @Test
    fun `a breach outranks everything, and is keyed by value`() {
        val shared = "Fk3!mzPqrs7Lw"
        val two = listOf(entry("a", password = shared), entry("b", password = shared))
        val result = audit(two, breached = setOf(shared), checked = setOf(shared))
        assertThat(result.score).isEqualTo(AuditScore.COMPROMISED)
        // Both entries are named, because they share the password that was found.
        assertThat(result.breached?.map { it.id }).containsExactly("a", "b")
        assertThat(result.partial).isFalse()
    }

    @Test
    fun `a password that was changed since the check stops being named`() {
        val result = audit(listOf(entry("a", password = "Nw4!bxQtuv8Mz")), breached = setOf("old"), checked = setOf("old"))
        assertThat(result.breached).isEmpty()
        // And it has not been checked in its new form, so the score says so.
        assertThat(result.partial).isTrue()
    }

    @Test
    fun `the score is partial until every password has been put against the list`() {
        val entries = listOf(entry("a", password = "Fk3!mzPqrs7Lw"), entry("b", password = "Nw4!bxQtuv8Mz"))
        assertThat(audit(entries).partial).isTrue()
        assertThat(audit(entries, breached = emptySet(), checked = setOf("Fk3!mzPqrs7Lw")).partial).isTrue()
        assertThat(audit(entries, breached = emptySet(), checked = setOf("Fk3!mzPqrs7Lw", "Nw4!bxQtuv8Mz")).partial).isFalse()
    }

    @Test
    fun `a sensitive account with no second factor costs, and only a sensitive one`() {
        val bank = audit(listOf(entry("a", category = "Banque")))
        assertThat(bank.score).isEqualTo(AuditScore.NO_SECOND_FACTOR)
        assertThat(bank.missingSecondFactor.map { it.id }).containsExactly("a")
        assertThat(audit(listOf(entry("a", category = "Web"))).score).isEqualTo(AuditScore.HEALTHY)
        assertThat(audit(listOf(entry("a", category = "Banque", totp = "JBSWY3DPEHPK3PXP"))).score).isEqualTo(AuditScore.HEALTHY)
    }

    @Test
    fun `the sensitive categories are the ones stored in the vault, in French`() {
        // Comparing against translated labels would make this check quietly fail outside French.
        assertThat(AuditScore.SENSITIVE_CATEGORIES).containsExactly("Banque", "Email", "Réseaux sociaux")
    }

    @Test
    fun `age is shown and never counted`() {
        val old = entry("a", updated = now.minusDays(VaultAudit.STALE_DAYS + 1))
        val result = audit(listOf(old))
        assertThat(result.stale.map { it.id }).containsExactly("a")
        // NIST SP 800-63B: no forced rotation. Punishing a strong unique password pushes to `Pass1` → `Pass2`.
        assertThat(result.score).isEqualTo(AuditScore.HEALTHY)
        assertThat(audit(listOf(entry("a", updated = now.minusDays(VaultAudit.STALE_DAYS - 1)))).stale).isEmpty()
    }

    @Test
    fun `what the vault holds is counted by type`() {
        val entries = listOf(
            entry("p", totp = "JBSWY3DPEHPK3PXP"),
            entry("q"),
            entry("n", type = EntryType.NOTE, password = ""),
            entry("c", type = EntryType.CARD, password = ""),
        )
        val result = audit(entries)
        assertThat(result.entries).isEqualTo(4)
        assertThat(result.passwords).isEqualTo(2)
        assertThat(result.withSecondFactor).isEqualTo(1)
        assertThat(result.notes).isEqualTo(1)
        assertThat(result.cards).isEqualTo(1)
    }

    @Test
    fun `the scale is 2-7-1's`() {
        assertThat(AuditScore.pointsFor(compromised = true, weak = true, duplicate = true, sensitiveWithoutSecondFactor = true))
            .isEqualTo(AuditScore.COMPROMISED)
        assertThat(AuditScore.pointsFor(compromised = false, weak = true, duplicate = true, sensitiveWithoutSecondFactor = true))
            .isEqualTo(AuditScore.WEAK)
        assertThat(AuditScore.pointsFor(compromised = false, weak = false, duplicate = true, sensitiveWithoutSecondFactor = true))
            .isEqualTo(AuditScore.DUPLICATE)
        assertThat(AuditScore.pointsFor(compromised = false, weak = false, duplicate = false, sensitiveWithoutSecondFactor = true))
            .isEqualTo(AuditScore.NO_SECOND_FACTOR)
        assertThat(AuditScore.pointsFor(compromised = false, weak = false, duplicate = false, sensitiveWithoutSecondFactor = false))
            .isEqualTo(AuditScore.HEALTHY)
        assertThat(AuditScore.average(emptyList())).isNull()
        assertThat(AuditScore.average(listOf(100, 0))).isEqualTo(50)
    }
}
