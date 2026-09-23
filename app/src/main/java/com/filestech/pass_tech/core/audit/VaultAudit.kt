package com.filestech.pass_tech.core.audit

import com.filestech.pass_tech.core.crypto.Sha1
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.password.PasswordStrength
import java.time.LocalDateTime

/**
 * What the audit screen shows, worked out from the entries alone plus whatever the breach check has
 * brought back. A pure function: hand it the same vault twice and it answers the same thing twice.
 *
 * The breach results arrive as **hashes**, not passwords. They have to be keyed by value rather than
 * by entry, so that two entries sharing one breached password are both named and an entry whose
 * password has since been changed stops being named; keying them by their hash gives that without
 * holding a second copy of every password in memory.
 */
object VaultAudit {

    /** Past this, an entry is shown as not looked at in a while. It does not touch the score. */
    const val STALE_DAYS = 365L

    data class Result(
        /** `null` when the vault holds no password at all. */
        val score: Int?,
        /**
         * At least one password has not been put against the breach list — never run, run before
         * this entry was added, or its own request failed. The score then ignores the only factual
         * signal the app has, and says so rather than passing for a verdict.
         */
        val partial: Boolean,
        val weak: List<Entry> = emptyList(),
        val duplicates: List<Entry> = emptyList(),
        val missingSecondFactor: List<Entry> = emptyList(),
        val stale: List<Entry> = emptyList(),
        /** `null` until a check has run. Empty means it ran and found none. */
        val breached: List<Entry>? = null,
        val entries: Int = 0,
        val passwords: Int = 0,
        val withSecondFactor: Int = 0,
        val notes: Int = 0,
        val cards: Int = 0,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun of(
        entries: List<Entry>,
        now: LocalDateTime,
        breachedHashes: Set<String>? = null,
        checkedHashes: Set<String>? = null,
        hash: (String) -> String = Sha1::hex,
    ): Result {
        val passwords = entries.filter { it.type == EntryType.PASSWORD && it.password.isNotEmpty() }
        val sameAs = passwords.groupingBy { it.password }.eachCount()
        val weak = passwords.filter { PasswordStrength.isWeak(it.password) }
        val weakIds = weak.mapTo(mutableSetOf()) { it.id }
        val duplicates = passwords.filter { (sameAs[it.password] ?: 0) > 1 }
        val duplicateIds = duplicates.mapTo(mutableSetOf()) { it.id }
        val missing = passwords.filter { it.category in AuditScore.SENSITIVE_CATEGORIES && it.totpSecret.isEmpty() }
        val missingIds = missing.mapTo(mutableSetOf()) { it.id }
        val stale = entries.filter { it.updatedAt.fields.isBefore(now.minusDays(STALE_DAYS)) }

        val hashes = passwords.associateBy({ it.id }, { hash(it.password) })
        val breached = breachedHashes?.let { known -> passwords.filter { hashes[it.id] in known } }
        val points = passwords.map { entry ->
            AuditScore.pointsFor(
                compromised = breachedHashes?.contains(hashes[entry.id]) == true,
                weak = entry.id in weakIds,
                duplicate = entry.id in duplicateIds,
                sensitiveWithoutSecondFactor = entry.id in missingIds,
            )
        }
        val score = AuditScore.average(points)
        return Result(
            score = score,
            // No score, nothing to complete: a vault whose every entry has an empty password used to
            // show "—" with a partial-score warning next to it (2.7.1).
            partial = score != null && passwords.any { checkedHashes?.contains(hashes[it.id]) != true },
            weak = weak,
            duplicates = duplicates,
            missingSecondFactor = missing,
            stale = stale,
            breached = breached,
            entries = entries.size,
            passwords = passwords.size,
            withSecondFactor = entries.count { it.totpSecret.isNotEmpty() },
            notes = entries.count { it.type == EntryType.NOTE },
            cards = entries.count { it.type == EntryType.CARD },
        )
    }
}
