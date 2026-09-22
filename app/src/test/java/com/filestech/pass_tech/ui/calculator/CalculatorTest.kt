package com.filestech.pass_tech.ui.calculator

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The calculator of the panic disguise. It has to be right for the disguise to hold: a facade that
 * answers 0 to "1,5 × 2" is a facade.
 */
class CalculatorTest {

    private fun calculator(separator: Char = '.') = Calculator(separator, ERROR)

    private fun Calculator.type(vararg keys: String): String {
        keys.forEach { press(it) }
        return display
    }

    @Test
    fun `it starts at zero and a digit replaces it`() {
        assertThat(calculator().display).isEqualTo("0")
        assertThat(calculator().type("7")).isEqualTo("7")
        assertThat(calculator().type("7", "0", "5")).isEqualTo("705")
    }

    @Test
    fun `the four operations`() {
        assertThat(calculator().type("2", "+", "3", "=")).isEqualTo("5")
        assertThat(calculator().type("9", "−", "4", "=")).isEqualTo("5")
        assertThat(calculator().type("6", "×", "7", "=")).isEqualTo("42")
        assertThat(calculator().type("8", "÷", "2", "=")).isEqualTo("4")
    }

    @Test
    fun `chaining shows the running total before going on`() {
        val calculator = calculator()
        assertThat(calculator.type("2", "+", "3", "+")).isEqualTo("5")
        assertThat(calculator.type("4", "=")).isEqualTo("9")
    }

    /**
     * 2.7.1's flaw, and the reason this class exists apart from the screen: it wrote decimals with the
     * phone's language and read them back with a dot only. On a French phone "1,5 × 2" gave 0.
     */
    @Test
    fun `a decimal number computes with the comma of a French phone, not only with a dot`() {
        assertThat(calculator(',').type("1", ".", "5", "×", "2", "=")).isEqualTo("3")
        assertThat(calculator(',').type("1", ".", "5")).isEqualTo("1,5")
        assertThat(calculator('.').type("1", ".", "5", "×", "2", "=")).isEqualTo("3")
        assertThat(calculator('.').type("0", ".", "1", "+", "0", ".", "2", "=")).isEqualTo("0.3")
    }

    @Test
    fun `the decimal mark opens a number and is never doubled`() {
        assertThat(calculator().type(".")).isEqualTo("0.")
        assertThat(calculator().type("5", ".", ".", "2", ".")).isEqualTo("5.2")
    }

    @Test
    fun `dividing by zero says so, and never throws`() {
        assertThat(calculator().type("5", "÷", "0", "=")).isEqualTo(ERROR)
        assertThat(calculator(',').type("5", "÷", "0", "=")).isEqualTo(ERROR)
    }

    @Test
    fun `after an error, a digit starts a new number and the backspace clears`() {
        val calculator = calculator()
        calculator.type("5", "÷", "0", "=")
        assertThat(calculator.type("7")).isEqualTo("7")

        val other = calculator()
        other.type("5", "÷", "0", "=")
        // 2.7.1 rubbed out one letter of its error word and showed "Erreu".
        assertThat(other.type("⌫")).isEqualTo("0")
    }

    @Test
    fun `the backspace rubs out one character and stops at zero`() {
        val calculator = calculator()
        assertThat(calculator.type("1", "2", "3", "⌫")).isEqualTo("12")
        assertThat(calculator.type("⌫", "⌫")).isEqualTo("0")
        assertThat(calculator.type("⌫")).isEqualTo("0")
    }

    @Test
    fun `a lone minus sign left by the backspace reads as zero`() {
        val calculator = calculator()
        assertThat(calculator.type("5", "±", "⌫")).isEqualTo("0")
    }

    @Test
    fun `sign and percent`() {
        assertThat(calculator().type("5", "±")).isEqualTo("-5")
        assertThat(calculator().type("5", "±", "±")).isEqualTo("5")
        assertThat(calculator().type("5", "0", "%")).isEqualTo("0.5")
        assertThat(calculator(',').type("5", "0", "%")).isEqualTo("0,5")
    }

    @Test
    fun `clear forgets the operation in progress`() {
        val calculator = calculator()
        calculator.type("2", "+", "3")
        assertThat(calculator.type("C")).isEqualTo("0")
        assertThat(calculator.type("4", "=")).isEqualTo("4")
    }

    @Test
    fun `equals with nothing pending shows what was typed`() {
        assertThat(calculator().type("7", "=")).isEqualTo("7")
    }

    @Test
    fun `a result is shown with at most ten decimals and no grouping`() {
        assertThat(calculator().type("1", "÷", "3", "=")).isEqualTo("0.3333333333")
        assertThat(calculator().type("1", "0", "0", "0", "0", "0", "0", "×", "2", "=")).isEqualTo("2000000")
    }

    @Test
    fun `the pad holds the twenty keys of 2_7_1, in its order`() {
        assertThat(Calculator.KEYS).hasSize(20)
        assertThat(Calculator.KEYS.take(4)).containsExactly("C", "±", "%", "÷").inOrder()
        assertThat(Calculator.KEYS.takeLast(4)).containsExactly("0", ".", "⌫", "=").inOrder()
    }

    private companion object {
        const val ERROR = "Error"
    }
}
