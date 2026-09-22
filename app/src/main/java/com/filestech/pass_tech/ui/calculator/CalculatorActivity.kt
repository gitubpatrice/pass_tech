package com.filestech.pass_tech.ui.calculator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.MainActivity
import com.filestech.pass_tech.R
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormatSymbols

/**
 * The calculator the launcher opens while the panic disguise is on (2.7.1, `CalculatorActivity.kt`).
 *
 * It is a real calculator. Before 2.5.4 the disguised alias opened the unlock screen, so the disguise
 * denied itself on the first tap — worse than none, since whoever checks then knows something was
 * being hidden from them.
 *
 * **The way back is a two-second press on the display.** Deliberately a gesture and not a numeric
 * code: a code is forgotten, and the button that undoes the disguise lives inside settings that are
 * then unreachable — and this repository is public, so the code would be too. A gesture has nothing
 * to remember. Its limit is stated in THREAT_MODEL: it is public as well, so it protects against a
 * distracted look, not against someone who already knows the phone carries Pass Tech.
 *
 * No Hilt, no vault, no FLAG_SECURE: a calculator whose screen cannot be photographed is a giveaway,
 * and this screen holds nothing worth hiding.
 */
class CalculatorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalculatorScreen(onReveal = ::revealPassTech) }
    }

    /**
     * Opens Pass Tech and closes the facade. The normal icon is NOT put back: that would make Pass
     * Tech reappear on the launcher without the owner asking, when they may only be looking something
     * up while still under the disguise. Showing it again is an explicit action, from the settings.
     */
    private fun revealPassTech() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
        } catch (_: Exception) {
            // Never an error message: a calculator that apologises for failing to open something
            // says there is something to open.
        }
    }
}

@Composable
private fun CalculatorScreen(onReveal: () -> Unit) {
    // The mark this phone writes decimals with, so that the calculator reads like the ones beside it.
    val locales = LocalConfiguration.current.locales
    val errorText = stringResource(R.string.calculator_error)
    val separator = remember(locales) {
        DecimalFormatSymbols.getInstance(locales[0]).decimalSeparator
    }
    val calculator = remember(separator, errorText) { Calculator(separator, errorText) }
    var display by remember { mutableStateOf(calculator.display) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .safeDrawingPadding(),
    ) {
        Display(display, onReveal)
        Pad(separator) {
            calculator.press(it)
            display = calculator.display
        }
    }
}

/** Right-aligned, as every calculator is, and the one way back out of the disguise. */
@Composable
private fun Display(display: String, onReveal: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DISPLAY_BACKGROUND)
            .pointerInput(onReveal) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Null means the timeout won: the finger is still down after two seconds.
                    val lifted = withTimeoutOrNull(REVEAL_HOLD_MILLIS) { waitForUpOrCancellation() }
                    if (lifted == null) onReveal()
                }
            }
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = display,
            color = TEXT,
            fontSize = 44.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Pad(separator: Char, onKey: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Calculator.KEYS.chunked(COLUMNS).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key -> Key(key, separator, Modifier.weight(1f), onKey) }
            }
        }
    }
}

@Composable
private fun Key(key: String, separator: Char, modifier: Modifier, onKey: (String) -> Unit) {
    val accent = key == Calculator.EQUALS
    Button(
        onClick = { onKey(key) },
        modifier = modifier.layout { measurable, constraints ->
            // A square-ish key grid: the row gives the height, the column the width.
            val placeable = measurable.measure(constraints.copy(minHeight = constraints.maxHeight))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                accent -> ACCENT
                key in Calculator.OPERATORS -> OPERATOR
                else -> Color.White
            },
            contentColor = if (accent) Color.White else TEXT,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text = if (key == Calculator.DOT) separator.toString() else key, fontSize = 22.sp)
    }
}

/** 2.7.1's colours, to the digit: a plain grey-and-white calculator with one warm key. */
private val TEXT = Color(0xFF212121)
private val ACCENT = Color(0xFFFF8A65)
private val OPERATOR = Color(0xFFECEFF1)
private val DISPLAY_BACKGROUND = Color(0xFFF5F5F5)
private const val COLUMNS = 4
private const val REVEAL_HOLD_MILLIS = 2000L
