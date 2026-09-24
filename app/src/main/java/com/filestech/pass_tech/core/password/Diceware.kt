package com.filestech.pass_tech.core.password

import com.filestech.pass_tech.core.password.words.EnglishWords
import com.filestech.pass_tech.core.password.words.FrenchWords
import com.filestech.pass_tech.core.password.words.GermanWords
import com.filestech.pass_tech.core.password.words.ItalianWords
import com.filestech.pass_tech.core.password.words.SpanishWords
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.ln

/**
 * Passphrases of common words, drawn from the list of the language the app is showing.
 *
 * **Why the language matters at all.** A passphrase is offered because it can be remembered; a
 * phrase of French words handed to someone reading a German screen is a random string with spaces
 * in it, and they will write it down or pick something weaker. 2.7.1 had the one French list and
 * used it whatever the language — for four owners out of five, the one feature that promised to be
 * memorable was the one that could not be.
 *
 * **The entropy is read off the list actually used.** The lists are not the same length (French 471,
 * English 513, German 499, Italian 504, Spanish 505), so `log2(size)` differs by a tenth of a bit
 * between them. Fixing a number here instead — the way 2.7.1's comment announced 512 words for a
 * list of 471 — would put a figure on a screen that the code does not honour.
 */
object Diceware {

    /** 2.7.1 appends a number from 10 to 99: 90 values. */
    private const val NUMBER_FROM = 10
    private const val NUMBER_VALUES = 90

    /**
     * A list and the language it belongs to. [ENGLISH] is what an app in any other language falls
     * back to, because it is the language the app itself defaults to.
     */
    enum class Language(val code: String, val words: List<String>) {
        ENGLISH("en", EnglishWords.LIST),
        FRENCH("fr", FrenchWords.LIST),
        GERMAN("de", GermanWords.LIST),
        ITALIAN("it", ItalianWords.LIST),
        SPANISH("es", SpanishWords.LIST),
        ;

        companion object {
            fun of(code: String?): Language = entries.firstOrNull { it.code == code } ?: ENGLISH

            /**
             * What the app is showing. Android applies a per-app language to the process default, so
             * this follows the choice made under Settings › Apps as well as the phone's own.
             */
            fun current(): Language = of(Locale.getDefault().language)
        }
    }

    fun entropyBits(language: Language, words: Int, appendNumber: Boolean): Double =
        words * log2(language.words.size) + if (appendNumber) log2(NUMBER_VALUES) else 0.0

    fun generate(
        language: Language,
        words: Int,
        separator: String,
        appendNumber: Boolean,
        random: SecureRandom = SecureRandom(),
    ): String {
        val list = language.words
        val picked = List(words) { list[random.nextInt(list.size)] }
        val phrase = picked.joinToString(separator)
        return if (appendNumber) phrase + separator + (NUMBER_FROM + random.nextInt(NUMBER_VALUES)) else phrase
    }

    private fun log2(n: Int): Double = ln(n.toDouble()) / ln(2.0)
}
