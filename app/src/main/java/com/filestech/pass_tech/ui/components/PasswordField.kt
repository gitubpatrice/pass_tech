package com.filestech.pass_tech.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.password.PasswordStrength
import com.filestech.pass_tech.ui.theme.StrengthColors

/**
 * The password field of every screen (2.7.1, `widgets/password_text_field.dart`): masked by default,
 * an eye to show it, no suggestions and no autocorrect. Its state is never saved into the activity's
 * saved state: a password there could be written to disk.
 *
 * [readOnly] while an attempt runs, never disabled: a disabled field loses the focus, which moves to
 * the eye next to it, and the keyboard's Enter then SHOWED the password instead of submitting it
 * (seen on the Galaxy S9, 2026-09-22).
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    readOnly: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        singleLine = true,
        readOnly = readOnly,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The strength bar under a new password (2.7.1 setup screen): 6 dp high, colour and label by level. */
@Composable
fun StrengthGauge(password: String, modifier: Modifier = Modifier) {
    val score = PasswordStrength.score(password)
    val (color, label) = when (PasswordStrength.level(score)) {
        PasswordStrength.Level.WEAK -> StrengthColors.Weak to R.string.strength_weak
        PasswordStrength.Level.MEDIUM -> StrengthColors.Medium to R.string.strength_medium
        PasswordStrength.Level.STRONG -> StrengthColors.Strong to R.string.strength_strong
        PasswordStrength.Level.VERY_STRONG -> StrengthColors.VeryStrong to R.string.strength_very_strong
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(score.toFloat())
                    .fillMaxHeight()
                    .background(color),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text = stringResource(label), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A small rounded square with an icon, at the top of the entry screens (2.7.1: 80 × 80, corners 20). */
@Composable
fun HeaderBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(44.dp))
    }
}
