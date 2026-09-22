package com.filestech.pass_tech.core.password

import com.filestech.pass_tech.core.password.PasswordGenerator.CharClass
import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln

/** The generator's rules, as 2.7.1's `generator_screen.dart` and `diceware_fr.dart` apply them. */
class PasswordGeneratorTest {

    private fun log2(n: Int) = ln(n.toDouble()) / ln(2.0)

    @Test
    fun `a password has the length asked and only characters of the classes ticked`() {
        repeat(200) {
            val digitsOnly = PasswordGenerator.generate(12, setOf(CharClass.DIGITS))
            assertThat(digitsOnly).hasLength(12)
            assertThat(digitsOnly.all { it in '0'..'9' }).isTrue()
        }
        val all = CharClass.entries.toSet()
        val pool = CharClass.entries.joinToString("") { it.chars }
        repeat(200) { assertThat(PasswordGenerator.generate(64, all).all { it in pool }).isTrue() }
    }

    @Test
    fun `every class of 2_7_1, symbols included, and all of them drawn over time`() {
        assertThat(CharClass.SYMBOLS.chars).isEqualTo("!@#$%^&*()-_=+[]{}|;:,.<>?")
        val drawn = (1..300).joinToString("") { PasswordGenerator.generate(64, CharClass.entries.toSet()) }.toSet()
        assertThat(drawn).containsExactlyElementsIn(CharClass.entries.joinToString("") { it.chars }.toSet())
    }

    @Test
    fun `no class ticked draws lower case, as 2_7_1`() {
        assertThat(PasswordGenerator.effectiveClasses(emptySet())).containsExactly(CharClass.LOWER)
        assertThat(PasswordGenerator.generate(20, emptySet()).all { it in 'a'..'z' }).isTrue()
    }

    @Test
    fun `entropy and score, on 2_7_1's scale`() {
        val bits = PasswordGenerator.entropyBits(16, CharClass.entries.toSet())
        assertThat(abs(bits - 16 * log2(88))).isLessThan(1e-9)
        assertThat(PasswordGenerator.score(40.0)).isEqualTo(0.5)
        assertThat(PasswordGenerator.score(200.0)).isEqualTo(1.0)
    }

    @Test
    fun `the passphrase list is 2_7_1's, duplicates dropped`() {
        assertThat(Diceware.WORDS).hasSize(471)
        assertThat(Diceware.WORDS.toSet()).hasSize(471)
        assertThat(Diceware.WORDS.first()).isEqualTo("chat")
        assertThat(Diceware.WORDS.last()).isEqualTo("ile")
        assertThat(Diceware.WORDS.all { word -> word.all { it in 'a'..'z' } }).isTrue()
        val bits = Diceware.entropyBits(5, appendNumber = true)
        assertThat(abs(bits - (5 * log2(471) + log2(90)))).isLessThan(1e-9)
        assertThat(Diceware.entropyBits(5, appendNumber = false)).isLessThan(bits)
    }

    @Test
    fun `a passphrase has its words, its separator, and a number from 10 to 99 at the end`() {
        repeat(200) {
            val parts = Diceware.generate(4, ".", appendNumber = true).split(".")
            assertThat(parts).hasSize(5)
            assertThat(parts.take(4).all { it in Diceware.WORDS }).isTrue()
            assertThat(parts.last().toInt()).isIn(10..99)
        }
        assertThat(Diceware.generate(3, " ", appendNumber = false).split(" ").all { it in Diceware.WORDS }).isTrue()
    }

    @Test
    fun `the list is the one the Dart file holds, in its order`() {
        val dart = Resources.text("compat/2.7.1/diceware_fr_words.txt").lines().filter { it.isNotBlank() }
        assertThat(Diceware.WORDS).containsExactlyElementsIn(dart.distinct()).inOrder()
    }
}
