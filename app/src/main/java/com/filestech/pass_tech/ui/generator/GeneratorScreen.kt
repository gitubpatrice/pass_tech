package com.filestech.pass_tech.ui.generator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.password.PasswordGenerator
import com.filestech.pass_tech.core.password.PasswordGenerator.CharClass
import com.filestech.pass_tech.core.password.PasswordStrength
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.generator.GeneratorState.Mode
import com.filestech.pass_tech.ui.theme.StrengthColors
import kotlin.math.roundToInt

/**
 * The password generator (2.7.1, `generator_screen.dart`): the password and its strength, Generate and
 * Copy, then the options of the mode. Opened from the editor, "Use" puts the password in its field.
 */
@Composable
fun GeneratorScreen(
    state: GeneratorState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onUse: (() -> Unit)?,
    onCopy: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val haptics = LocalHapticFeedback.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.generator_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (onUse != null) {
                        TextButton(onClick = onUse, enabled = state.password.isNotEmpty()) {
                            Text(stringResource(R.string.generator_use), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Result(state)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = state::generate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.generator_generate))
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopy(state.password)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.generator_copy))
                }
            }
            Spacer(Modifier.height(24.dp))
            ModeSelector(state)
            Spacer(Modifier.height(16.dp))
            when (state.mode) {
                Mode.CHARACTERS -> CharacterOptions(state)
                Mode.PASSPHRASE -> PassphraseOptions(state)
            }
        }
    }
}

/** The password, then the strength bar and "Strong · 104 bits". */
@Composable
private fun Result(state: GeneratorState) {
    val bits = state.entropyBits
    val score = PasswordGenerator.score(bits)
    val (color, label) = when (PasswordStrength.level(score)) {
        PasswordStrength.Level.WEAK -> StrengthColors.Weak to R.string.strength_weak
        PasswordStrength.Level.MEDIUM -> StrengthColors.Medium to R.string.strength_medium
        PasswordStrength.Level.STRONG -> StrengthColors.Strong to R.string.strength_strong
        PasswordStrength.Level.VERY_STRONG -> StrengthColors.VeryStrong to R.string.strength_very_strong
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.password,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(score.toFloat())
                        .fillMaxHeight()
                        .background(color),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.generator_strength_suffix, stringResource(label), bits.roundToInt()),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun ModeSelector(state: GeneratorState) {
    val modes = listOf(
        Triple(Mode.CHARACTERS, R.string.generator_mode_chars, Icons.Filled.Tag),
        Triple(Mode.PASSPHRASE, R.string.generator_mode_phrase, Icons.Filled.Translate),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label, icon) ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = { state.changeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun CharacterOptions(state: GeneratorState) {
    Text(stringResource(R.string.generator_length, state.length), fontWeight = FontWeight.SemiBold)
    Slider(
        value = state.length.toFloat(),
        onValueChange = { state.changeLength(it.roundToInt()) },
        valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat(),
        steps = PasswordGenerator.MAX_LENGTH - PasswordGenerator.MIN_LENGTH - 1,
    )
    Spacer(Modifier.height(8.dp))
    OptionsHeader(stringResource(R.string.generator_chars_header))
    val labels = mapOf(
        CharClass.UPPER to R.string.generator_chars_upper,
        CharClass.LOWER to R.string.generator_chars_lower,
        CharClass.DIGITS to R.string.generator_chars_digits,
        CharClass.SYMBOLS to R.string.generator_chars_symbols,
    )
    CharClass.entries.forEach { charClass ->
        OptionSwitch(stringResource(labels.getValue(charClass)), charClass in state.classes) { state.toggle(charClass, it) }
    }
}

@Composable
private fun PassphraseOptions(state: GeneratorState) {
    Text(stringResource(R.string.generator_phrase_words, state.words), fontWeight = FontWeight.SemiBold)
    Slider(
        value = state.words.toFloat(),
        onValueChange = { state.changeWords(it.roundToInt()) },
        valueRange = GeneratorState.MIN_WORDS.toFloat()..GeneratorState.MAX_WORDS.toFloat(),
        steps = GeneratorState.MAX_WORDS - GeneratorState.MIN_WORDS - 1,
    )
    Spacer(Modifier.height(8.dp))
    OptionsHeader(stringResource(R.string.generator_options_header))
    OptionSwitch(stringResource(R.string.generator_phrase_append_number), state.appendNumber, state::changeAppendNumber)
    PtCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.generator_phrase_separator), fontSize = 14.sp, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                GeneratorState.SEPARATORS.forEachIndexed { index, separator ->
                    SegmentedButton(
                        selected = state.separator == separator,
                        onClick = { state.changeSeparator(separator) },
                        shape = SegmentedButtonDefaults.itemShape(index, GeneratorState.SEPARATORS.size),
                        icon = {},
                    ) { Text(if (separator == " ") "␣" else separator) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Hint(Icons.Outlined.Lightbulb, stringResource(R.string.generator_phrase_hint))
}

@Composable
private fun OptionsHeader(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    PtCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
