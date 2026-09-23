package com.filestech.pass_tech.ui.generator

import com.filestech.pass_tech.core.password.Diceware
import com.filestech.pass_tech.core.password.PasswordGenerator.CharClass
import com.filestech.pass_tech.ui.generator.GeneratorState.Mode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GeneratorStateTest {

    @Test
    fun `it opens on 16 characters of every class, a password already drawn`() {
        val state = GeneratorState()
        assertThat(state.mode).isEqualTo(Mode.CHARACTERS)
        assertThat(state.password).hasLength(16)
        assertThat(state.classes).containsExactlyElementsIn(CharClass.entries)
    }

    @Test
    fun `every change of option draws again`() {
        val state = GeneratorState()
        state.changeLength(40)
        assertThat(state.password).hasLength(40)
        state.toggle(CharClass.UPPER, false)
        state.toggle(CharClass.SYMBOLS, false)
        state.toggle(CharClass.LOWER, false)
        assertThat(state.password.all { it in '0'..'9' }).isTrue()
    }

    @Test
    fun `unticking the last class ticks lower case again`() {
        val state = GeneratorState()
        CharClass.entries.forEach { state.toggle(it, false) }
        assertThat(state.classes).containsExactly(CharClass.LOWER)
        assertThat(state.password.all { it in 'a'..'z' }).isTrue()
    }

    @Test
    fun `lengths and word counts stay within 2_7_1's bounds`() {
        val state = GeneratorState()
        state.changeLength(4)
        assertThat(state.length).isEqualTo(8)
        state.changeLength(100)
        assertThat(state.length).isEqualTo(64)
        state.changeWords(1)
        assertThat(state.words).isEqualTo(3)
        state.changeWords(12)
        assertThat(state.words).isEqualTo(8)
    }

    @Test
    fun `the passphrase mode follows its separator, word count and number`() {
        // The language is given here rather than read from the machine running the test: the list
        // drawn from is the app's, and a test must not change answer with the locale of a runner.
        val language = Diceware.Language.FRENCH
        val state = GeneratorState(language)
        state.changeMode(Mode.PASSPHRASE)
        state.changeWords(3)
        state.changeSeparator("_")
        state.changeAppendNumber(false)
        val parts = state.password.split("_")
        assertThat(parts).hasSize(3)
        assertThat(parts.all { it in language.words }).isTrue()
        assertThat(state.entropyBits).isEqualTo(Diceware.entropyBits(language, 3, appendNumber = false))
    }
}
