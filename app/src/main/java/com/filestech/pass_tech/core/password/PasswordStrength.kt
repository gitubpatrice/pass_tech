package com.filestech.pass_tech.core.password

import kotlin.math.ceil
import kotlin.math.ln

/**
 * How strong a password is, as the Flutter app (2.7.1, `password_strength_service.dart`) measured
 * it: Shannon entropy over the character classes present, on an EFFECTIVE length that discounts
 * repetitions and sequences, and zero for a common password even when dressed up. Normalised on
 * 80 bits = very strong. Same verdicts as 2.7.1, checked on its own test cases.
 *
 * The entropy OVERESTIMATES human passwords: [isCommon] only catches the best known ones. This is
 * why the minimum length of [PasswordPolicy] does not go lower.
 */
object PasswordStrength {

    enum class Level { WEAK, MEDIUM, STRONG, VERY_STRONG }

    private const val MAX_BITS = 80.0
    private const val LETTERS = 26
    private const val DIGITS = 10

    // Aligned on the 26 symbols of the generator (2.3.8), not the 32 printable ones.
    private const val SYMBOLS = 26
    private const val MEDIUM_FROM = 0.35
    private const val STRONG_FROM = 0.65
    private const val VERY_STRONG_FROM = 0.85
    private const val WEAK_BELOW_LENGTH = 10

    // A common root counts when it has at least this many letters, and weighs at least half the password.
    private const val ROOT_MIN_LENGTH = 5

    private val UPPER = Regex("[A-Z]")
    private val LOWER = Regex("[a-z]")
    private val DIGIT = Regex("[0-9]")
    private val SYMBOL = Regex("[^A-Za-z0-9]")
    private val DRESSING = Regex("^[\\d\\W_]+|[\\d\\W_]+$")
    private val NOT_A_LETTER = Regex("[^a-z]")

    /** Normalised score in [0, 1]. */
    fun score(password: String): Double =
        if (password.isEmpty()) 0.0 else (entropyBits(password) / MAX_BITS).coerceIn(0.0, 1.0)

    /** `effective length × log2(pool)`, the pool being the sum of the classes present. */
    fun entropyBits(password: String): Double {
        val pool = listOf(UPPER to LETTERS, LOWER to LETTERS, DIGIT to DIGITS, SYMBOL to SYMBOLS)
            .filter { (regex, _) -> regex.containsMatchIn(password) }
            .sumOf { (_, size) -> size }
        return if (password.isEmpty() || isCommon(password) || pool == 0) 0.0 else effectiveLength(password) * log2(pool.toDouble())
    }

    fun level(score: Double): Level = when {
        score < MEDIUM_FROM -> Level.WEAK
        score < STRONG_FROM -> Level.MEDIUM
        score < VERY_STRONG_FROM -> Level.STRONG
        else -> Level.VERY_STRONG
    }

    /** Weak: shorter than 10, common, or a score under 0.35. */
    fun isWeak(password: String): Boolean =
        password.length < WEAK_BELOW_LENGTH || isCommon(password) || score(password) < MEDIUM_FROM

    /**
     * A common password, or a common root dressed with digits and punctuation (`Password123!`,
     * `azerty2024`), l33t included (`P@ssw0rd`). The dressing is stripped BEFORE the l33t
     * normalisation, which would otherwise turn `123` into letters and hide the root.
     */
    fun isCommon(password: String): Boolean {
        val lower = password.lowercase()
        val trimmed = lower.replace(DRESSING, "")
        return setOf(deleet(lower), deleet(trimmed)).any { candidate ->
            val core = candidate.replace(NOT_A_LETTER, "")
            core.isNotEmpty() && (core in COMMON_ROOTS || COMMON_ROOTS.any { weighsHalf(it, core) })
        }
    }

    /** A root of at least 5 letters that is at least half of [core]: `passwordxyz` counts, `chaisenuageturbine` does not. */
    private fun weighsHalf(root: String, core: String) =
        root.length >= ROOT_MIN_LENGTH && core.contains(root) && root.length * 2 >= core.length

    /**
     * Repetitions (`aaaa`) and sequences (`1234`, `dcba`) do not count for their raw length: a run of
     * `n` characters with a constant step of 0, +1 or -1 counts `1 + ceil(log2(n))`.
     */
    private fun effectiveLength(password: String): Int {
        var effective = 0
        var i = 0
        while (i < password.length) {
            val run = runLength(password, i)
            effective += if (run == 1) 1 else 1 + ceil(log2(run.toDouble())).toInt()
            i += run
        }
        return effective
    }

    /** The length of the run starting at [start]: characters whose code steps by a constant 0, +1 or -1. */
    private fun runLength(password: String, start: Int): Int {
        if (start + 1 >= password.length) return 1
        val delta = password[start + 1].code - password[start].code
        if (delta !in -1..1) return 1
        var end = start + 1
        while (end < password.length && password[end].code - password[end - 1].code == delta) end++
        return end - start
    }

    // Same formula as Dart (`log(x) / ln2`), so that the rounding matches to the last bit.
    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun deleet(text: String): String = buildString { text.forEach { append(LEET[it] ?: it) } }

    private val LEET = mapOf(
        '@' to 'a', '4' to 'a', '8' to 'b', '(' to 'c', '3' to 'e', '6' to 'g', '1' to 'l',
        '!' to 'i', '0' to 'o', '5' to 's', '$' to 's', '7' to 't', '2' to 'z',
    )

    /** The most common passwords and roots, French ones included. Local on purpose: no online check of the master secret. */
    private val COMMON_ROOTS = setOf(
        "password", "passwd", "motdepasse", "secret", "azerty", "qwerty", "qwertz", "azertyuiop",
        "qwertyuiop", "asdfgh", "wxcvbn", "zxcvbn", "letmein", "welcome", "admin", "administrateur",
        "root", "toor", "login", "user", "utilisateur", "test", "guest", "invite", "bonjour", "salut",
        "coucou", "hello", "monkey", "dragon", "soleil", "chouchou", "doudou", "nicolas", "jetaime",
        "amour", "bisous", "chocolat", "football", "baseball", "superman", "batman", "starwars",
        "pokemon", "princesse", "princess", "sunshine", "iloveyou", "trustno", "freedom", "whatever",
        "master", "shadow", "michael", "jennifer", "thomas", "jordan", "hunter", "ranger", "liverpool",
        "marseille", "chelsea", "arsenal", "juventus", "barcelona", "realmadrid", "motorola", "samsung",
        "iphone", "android", "internet", "computer", "ordinateur", "maison", "famille", "vacances",
        "anniversaire",
    )
}
