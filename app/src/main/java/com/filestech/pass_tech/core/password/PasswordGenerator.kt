package com.filestech.pass_tech.core.password

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Random passwords from character classes (2.7.1, `generator_screen.dart`): every character drawn
 * uniformly from the classes ticked, no guarantee that each class appears.
 */
object PasswordGenerator {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 16

    /** The score of the strength bar: 80 bits is full (2.7.1, the same scale as the creation screen). */
    private const val FULL_BITS = 80.0

    enum class CharClass(val chars: String) {
        UPPER("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        LOWER("abcdefghijklmnopqrstuvwxyz"),
        DIGITS("0123456789"),
        SYMBOLS("!@#$%^&*()-_=+[]{}|;:,.<>?"),
    }

    /** No class ticked: lower case comes back on, as in 2.7.1, since a password needs something to draw from. */
    fun effectiveClasses(classes: Set<CharClass>): Set<CharClass> = classes.ifEmpty { setOf(CharClass.LOWER) }

    fun generate(length: Int, classes: Set<CharClass>, random: SecureRandom = SecureRandom()): String {
        val pool = effectiveClasses(classes).sortedBy { it.ordinal }.joinToString("") { it.chars }
        return String(CharArray(length) { pool[random.nextInt(pool.length)] })
    }

    /** From the classes ticked, not from the characters drawn: what the generator can produce. */
    fun entropyBits(length: Int, classes: Set<CharClass>): Double {
        val pool = effectiveClasses(classes).sumOf { it.chars.length }
        return length * ln(pool.toDouble()) / ln(2.0)
    }

    /** 0 to 1, for the strength bar and its label. */
    fun score(bits: Double): Double = (bits / FULL_BITS).coerceIn(0.0, 1.0)
}
