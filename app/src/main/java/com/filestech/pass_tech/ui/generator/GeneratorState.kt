package com.filestech.pass_tech.ui.generator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.filestech.pass_tech.core.password.Diceware
import com.filestech.pass_tech.core.password.PasswordGenerator
import com.filestech.pass_tech.core.password.PasswordGenerator.CharClass
import java.security.SecureRandom

/**
 * The generator's options and its current password (2.7.1, `generator_screen.dart`). Every change of
 * option draws a new password. The options are not kept once the screen closes, as in 2.7.1.
 */
class GeneratorState(
    /** The word list a passphrase is drawn from: the language the app is showing, read once here. */
    private val language: Diceware.Language = Diceware.Language.current(),
    private val random: SecureRandom = SecureRandom(),
) {

    enum class Mode { CHARACTERS, PASSPHRASE }

    var mode by mutableStateOf(Mode.CHARACTERS)
        private set
    var length by mutableIntStateOf(PasswordGenerator.DEFAULT_LENGTH)
        private set
    var classes by mutableStateOf(CharClass.entries.toSet())
        private set
    var words by mutableIntStateOf(DEFAULT_WORDS)
        private set
    var appendNumber by mutableStateOf(true)
        private set
    var separator by mutableStateOf(SEPARATORS.first())
        private set
    var password by mutableStateOf("")
        private set

    val entropyBits: Double
        get() = when (mode) {
            Mode.CHARACTERS -> PasswordGenerator.entropyBits(length, classes)
            Mode.PASSPHRASE -> Diceware.entropyBits(language, words, appendNumber)
        }

    init {
        generate()
    }

    fun generate() {
        password = when (mode) {
            Mode.CHARACTERS -> PasswordGenerator.generate(length, classes, random)
            Mode.PASSPHRASE -> Diceware.generate(language, words, separator, appendNumber, random)
        }
    }

    fun changeMode(value: Mode) = update { mode = value }

    fun changeLength(value: Int) = update { length = value.coerceIn(PasswordGenerator.MIN_LENGTH, PasswordGenerator.MAX_LENGTH) }

    /** Unticking the last class ticks lower case again, as 2.7.1 does. */
    fun toggle(charClass: CharClass, on: Boolean) =
        update { classes = PasswordGenerator.effectiveClasses(if (on) classes + charClass else classes - charClass) }

    fun changeWords(value: Int) = update { words = value.coerceIn(MIN_WORDS, MAX_WORDS) }

    fun changeAppendNumber(value: Boolean) = update { appendNumber = value }

    fun changeSeparator(value: String) = update { separator = value }

    private fun update(change: () -> Unit) {
        change()
        generate()
    }

    companion object {
        const val MIN_WORDS = 3
        const val MAX_WORDS = 8
        const val DEFAULT_WORDS = 5

        /** 2.7.1's separators: hyphen, dot, underscore, space. */
        val SEPARATORS = listOf("-", ".", "_", " ")
    }
}
