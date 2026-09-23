package com.filestech.pass_tech.core.password

import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.security.SecureRandom
import java.util.Locale

/**
 * The five word lists and what is drawn from them.
 *
 * **A list is the one place in this app where a typing slip becomes a security claim.** The entropy
 * a screen shows is `log2(size)` per word: a word written twice is a word counted twice, and the
 * figure announced is then higher than the truth. So the rules below are checked on every list, in
 * every language, rather than trusted to the care taken while writing them — which is exactly how a
 * repeat and an invented word got into the English list before this test existed.
 */
class DicewareTest {

    private val languages = Diceware.Language.entries

    @TestFactory
    fun `no word is written twice`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language.code) {
                assertThat(language.words).containsNoDuplicates()
            }
        }

    /**
     * Lower case, letters only, on every list. No accent and no `ß`: a passphrase is typed in a
     * hurry, often on a keyboard whose layout is not the owner's, and that is where a mark above a
     * letter goes wrong. German writes `ae oe ue ss`, Spanish leaves out what needs an `ñ`.
     */
    @TestFactory
    fun `every word can be typed on any keyboard`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language.code) {
                assertThat(language.words.filterNot { it.matches(Regex("[a-z]+")) }).isEmpty()
            }
        }

    /**
     * Three to nine letters — short enough to type on a phone, long enough to be a word.
     *
     * **French is exempt, and deliberately.** Its list is 2.7.1's own: it carries `or` at two
     * letters and `interrupteur` at twelve. Shortening it would change what a passphrase made in
     * 2.7.1 was worth, so it is kept as it was and the exemption is written here rather than the
     * rule quietly relaxed for everyone.
     */
    @TestFactory
    fun `the lists written for this rewrite keep to three to nine letters`(): List<DynamicTest> =
        languages.filter { it != Diceware.Language.FRENCH }.map { language ->
            DynamicTest.dynamicTest(language.code) {
                assertThat(language.words.filterNot { it.matches(Regex("[a-z]{3,9}")) }).isEmpty()
            }
        }

    @Test
    fun `the exemption is only French, and only for its length`() {
        val french = Diceware.Language.FRENCH.words
        assertThat(french).contains("or")
        assertThat(french).contains("interrupteur")
    }

    /**
     * A floor, not a fixed count: the lists are written by hand and grow. Below this, a passphrase
     * of five words would fall under the strength the generator offers beside it.
     */
    @TestFactory
    fun `every list is long enough to be worth drawing from`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language.code) {
                assertThat(language.words.size).isAtLeast(400)
            }
        }

    @Test
    fun `the French list is 2_7_1's own file, in its order, duplicates dropped`() {
        val french = Diceware.Language.FRENCH.words
        assertThat(french).hasSize(471)
        assertThat(french.first()).isEqualTo("chat")
        assertThat(french.last()).isEqualTo("ile")
        val dart = Resources.text("compat/2.7.1/diceware_fr_words.txt").lines().filter { it.isNotBlank() }
        assertThat(french).containsExactlyElementsIn(dart.distinct()).inOrder()
    }

    /**
     * The five lists are different lists, not one translated five times: two languages sharing a
     * word is fine — `panda`, `koala` — but two sharing most of them would mean one was pasted over
     * the other, and the count of "how many languages does this app offer" would be a fiction.
     */
    @Test
    fun `the lists are five lists and not one`() {
        languages.forEach { first ->
            languages.filter { it != first }.forEach { second ->
                val shared = first.words.intersect(second.words.toSet()).size
                assertThat(shared).isLessThan(first.words.size / 4)
            }
        }
    }

    @Test
    fun `the language is the app's, and anything unknown falls back to English`() {
        assertThat(Diceware.Language.of("de")).isEqualTo(Diceware.Language.GERMAN)
        assertThat(Diceware.Language.of("fr")).isEqualTo(Diceware.Language.FRENCH)
        assertThat(Diceware.Language.of("pt")).isEqualTo(Diceware.Language.ENGLISH)
        assertThat(Diceware.Language.of(null)).isEqualTo(Diceware.Language.ENGLISH)
        assertThat(Diceware.Language.of("")).isEqualTo(Diceware.Language.ENGLISH)
        assertThat(Diceware.Language.current()).isEqualTo(Diceware.Language.of(Locale.getDefault().language))
    }

    /**
     * 2.7.1 announced 512 words for a list of 471 and showed the entropy of neither. Here the figure
     * is read off the list that was actually drawn from, so it differs between languages — and that
     * is the point: a number on a screen has to be the one the code honours.
     */
    @TestFactory
    fun `the entropy is the one of the list actually used`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language.code) {
                val perWord = Diceware.entropyBits(language, words = 1, appendNumber = false)
                assertThat(perWord).isWithin(TOLERANCE).of(log2(language.words.size))
                assertThat(Diceware.entropyBits(language, 5, appendNumber = false))
                    .isWithin(TOLERANCE).of(5 * perWord)
                // The appended number is worth the 90 values it is drawn from, and no more.
                assertThat(Diceware.entropyBits(language, 5, appendNumber = true))
                    .isWithin(TOLERANCE).of(5 * perWord + log2(90))
            }
        }

    @TestFactory
    fun `a phrase is drawn from its own language, and nowhere else`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language.code) {
                val random = SecureRandom()
                repeat(REPEATS) {
                    val parts = Diceware.generate(language, 4, ".", appendNumber = true, random).split(".")
                    assertThat(parts).hasSize(5)
                    assertThat(parts.take(4).all { it in language.words }).isTrue()
                    assertThat(parts.last().toInt()).isIn(10..99)
                }
                val phrase = Diceware.generate(language, 3, " ", appendNumber = false, random)
                assertThat(phrase.split(" ").all { it in language.words }).isTrue()
            }
        }

    private fun log2(n: Int): Double = kotlin.math.ln(n.toDouble()) / kotlin.math.ln(2.0)

    private companion object {
        const val REPEATS = 50
        const val TOLERANCE = 1e-9
    }
}
