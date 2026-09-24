package com.filestech.pass_tech.core.audit

/**
 * The scale of the vault audit, and nothing else: no entries, no clock, no network.
 *
 * **The score is the average health of the vault's passwords.** 2.7.1 arrived at this after its own
 * screen had subtracted fixed penalties that stopped at six: a vault of six passwords, all six weak,
 * read "70/100, Good", exactly like a vault of two hundred with six weak ones — the score reassured
 * precisely where it should not have. An average is proportional by construction. The four defects
 * 2.7.1 fixed there are kept fixed here, and its reasoning with them:
 *
 * - a breach is the only FACTUAL signal the app has, and it was worth nothing in the old score while
 *   age — the most arguable one — cost up to twenty points;
 * - age no longer touches the score. NIST SP 800-63B says not to force rotation and to change only
 *   on a known compromise; punishing a strong, unique, uncompromised password for being a year old
 *   pushes towards `Password1` → `Password2`, which is weaker. It is shown, not counted;
 * - an entry counts for its WORST fault, once. Weak and reused used to cost twice.
 */
object AuditScore {

    /**
     * Where a second factor is expected. These are the CANONICAL values stored in the vault, not
     * translated labels: the vault keeps its categories in French so that existing vaults still read
     * (`Categories`), and comparing against translated text would make the check quietly fail in
     * every other language. "Réseaux sociaux" is here because taking over a social account is a way
     * into the others — resets, delegated sign-in — just as a mailbox is.
     */
    val SENSITIVE_CATEGORIES = setOf("Banque", "Email", "Réseaux sociaux")

    /**
     * Health points, not severity percentages: a password known to be in a public breach offers no
     * protection at all any more, however complex it is.
     */
    const val COMPROMISED = 0
    const val WEAK = 25
    const val DUPLICATE = 55
    const val NO_SECOND_FACTOR = 85
    const val HEALTHY = 100

    /** The order of the tests IS the scale: it says which fault wins when an entry has several. */
    fun pointsFor(compromised: Boolean, weak: Boolean, duplicate: Boolean, sensitiveWithoutSecondFactor: Boolean): Int =
        when {
            compromised -> COMPROMISED
            weak -> WEAK
            duplicate -> DUPLICATE
            sensitiveWithoutSecondFactor -> NO_SECOND_FACTOR
            else -> HEALTHY
        }

    /**
     * `null` when there is no password to score. The old formula answered 100, "Excellent", which
     * amounted to congratulating an empty vault.
     */
    fun average(points: Collection<Int>): Int? =
        if (points.isEmpty()) {
            null
        } else {
            Math.round(points.sum().toDouble() / points.size).toInt().coerceIn(0, HEALTHY)
        }
}
