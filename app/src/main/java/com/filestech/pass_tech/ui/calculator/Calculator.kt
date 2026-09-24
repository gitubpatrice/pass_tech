package com.filestech.pass_tech.ui.calculator

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The calculator that stands in front of the app once the panic disguise is on (2.7.1,
 * `CalculatorActivity.kt`). It really computes: a dead facade, whose keys do nothing or whose results
 * are wrong, gives the disguise away as surely as no disguise at all.
 *
 * [separator] is the decimal mark of the language the phone shows, and [errorText] what a division by
 * zero prints. **2.7.1 got both wrong**, and the same way: it formatted with the phone's language,
 * which writes "1,5" in French, then read that back with `toDouble`, which only accepts a dot. Every
 * chained operation after a decimal number therefore used 0 instead — "1,5 × 2" gave 0 on a French
 * phone and 3 on an English one. Its error word was French, hard-coded, on every phone.
 *
 * Keys are the strings of [KEYS]; a digit is its own key. State machine only: the screen draws it.
 */
class Calculator(private val separator: Char, private val errorText: String) {

    var display: String = ZERO
        private set

    /** The left operand, `null` until an operator is pressed. */
    private var accumulator: Double? = null
    private var pendingOperator: String? = null

    /** True when the next digit replaces what is shown instead of being appended. */
    private var startNewOperand = true

    fun press(key: String) {
        when (key) {
            CLEAR -> clear()
            BACKSPACE -> backspace()
            SIGN -> show(-current())
            PERCENT -> show(current() / PERCENT_DIVISOR)
            DIVIDE, MULTIPLY, MINUS, PLUS -> {
                // Chaining: "2 + 3 +" shows 5 before going on.
                applyPending()
                pendingOperator = key
                startNewOperand = true
            }
            EQUALS -> {
                applyPending()
                pendingOperator = null
                startNewOperand = true
            }
            DOT -> dot()
            else -> digit(key)
        }
    }

    private fun clear() {
        accumulator = null
        pendingOperator = null
        startNewOperand = true
        display = ZERO
    }

    /** On a result that is not a number ("Error"), there is nothing to rub out: start again. */
    private fun backspace() {
        display = when {
            parsed() == null -> ZERO
            display.length <= 1 -> ZERO
            else -> display.dropLast(1).takeUnless { it == "-" } ?: ZERO
        }
    }

    private fun dot() {
        if (startNewOperand) {
            display = ZERO + separator
            startNewOperand = false
        } else if (!display.contains(separator)) {
            display += separator
        }
    }

    private fun digit(key: String) {
        if (startNewOperand || display == ZERO) {
            display = key
            startNewOperand = false
        } else {
            display += key
        }
    }

    private fun applyPending() {
        val left = accumulator
        val operator = pendingOperator
        val right = current()
        if (left == null || operator == null) {
            accumulator = right
            return
        }
        val result = when (operator) {
            PLUS -> left + right
            MINUS -> left - right
            MULTIPLY -> left * right
            // Dividing by zero gives an infinity, which [show] prints as the error word: no exception,
            // so no crash report that would say this is not an ordinary calculator.
            else -> left / right
        }
        accumulator = result
        show(result)
    }

    /** What is shown, as a number; 0 when it is not one (the error word). */
    private fun current(): Double = parsed() ?: 0.0

    private fun parsed(): Double? = display.replace(separator, '.').toDoubleOrNull()

    private fun show(value: Double) {
        display = if (value.isNaN() || value.isInfinite()) errorText else format.format(value)
    }

    /**
     * Root symbols with one change, the decimal mark: the digits and the minus sign stay the ones
     * [parsed] can read back, whatever language the phone is in.
     */
    private val format = DecimalFormat(
        "#.##########",
        DecimalFormatSymbols(Locale.ROOT).also { it.decimalSeparator = separator },
    )

    companion object {
        const val CLEAR = "C"
        const val SIGN = "±"
        const val PERCENT = "%"
        const val DIVIDE = "÷"
        const val MULTIPLY = "×"
        const val MINUS = "−"
        const val PLUS = "+"
        const val EQUALS = "="
        const val DOT = "."
        const val BACKSPACE = "⌫"

        private const val ZERO = "0"
        private const val PERCENT_DIVISOR = 100.0

        /** The pad, row by row, as 2.7.1 draws it. */
        val KEYS = listOf(
            CLEAR, SIGN, PERCENT, DIVIDE,
            "7", "8", "9", MULTIPLY,
            "4", "5", "6", MINUS,
            "1", "2", "3", PLUS,
            "0", DOT, BACKSPACE, EQUALS,
        )

        val OPERATORS = setOf(DIVIDE, MULTIPLY, MINUS, PLUS)
    }
}
