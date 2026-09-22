package com.filestech.pass_tech.ui.entries

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.Categories
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.totp.Totp
import com.filestech.pass_tech.ui.components.PasswordField
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.theme.DestructiveRed
import com.filestech.pass_tech.ui.theme.FavoriteAmber

/**
 * The entry editor (2.7.1, `entry_edit_screen.dart`): the category chips and the Save button at the
 * top, the title, then the fields of the entry's type. Leaving with unsaved changes asks first.
 *
 * No suggestions and no autocorrect on any field: keyboards learn what is typed and offer it again in
 * other apps (2.7.1, SEC 2026-08-03 and 2026-08-04). The app root also turns off the keyboard's
 * personalised learning for every field.
 */
@Composable
fun EntryEditScreen(
    form: EntryForm,
    snackbar: SnackbarHostState,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    onSecretAdded: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (form.hasChanges) confirmLeave = true else onLeave() }
    BackHandler(onBack = leave)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(editorTitle(form))) },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { form.favorite = !form.favorite }) {
                        Icon(
                            imageVector = if (form.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (form.favorite) R.string.entry_edit_remove_favorite else R.string.entry_edit_add_favorite,
                            ),
                            tint = if (form.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.entry_edit_category))
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onSave,
                    enabled = !form.saving,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 38.dp),
                ) {
                    Text(
                        stringResource(if (form.saving) R.string.entry_edit_saving else R.string.entry_edit_save),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            CategoryChips(selected = form.category, onSelect = { form.category = it })
            Spacer(Modifier.height(16.dp))

            LabelledField(stringResource(R.string.entry_edit_field_title_required)) {
                TextInput(
                    value = form.title,
                    onValueChange = { form.title = it },
                    label = stringResource(R.string.entry_edit_field_title_required),
                    placeholder = stringResource(titleHint(form.type)),
                    icon = Icons.Filled.Title,
                    capitalization = KeyboardCapitalization.Sentences,
                )
            }
            when (form.type) {
                EntryType.PASSWORD -> PasswordFields(form, onSecretAdded)
                EntryType.NOTE -> NoteFields(form)
                EntryType.CARD -> CardFields(form)
            }
        }
    }

    if (confirmLeave) {
        DiscardDialog(
            onKeep = { confirmLeave = false },
            onDiscard = {
                confirmLeave = false
                onLeave()
            },
        )
    }
}

@Composable
private fun PasswordFields(form: EntryForm, onSecretAdded: () -> Unit) {
    LabelledField(stringResource(R.string.entry_edit_field_username)) {
        TextInput(
            value = form.username,
            onValueChange = { form.username = it },
            label = stringResource(R.string.entry_edit_field_username),
            placeholder = stringResource(R.string.entry_edit_hint_username),
            icon = Icons.Outlined.PersonOutline,
            keyboardType = KeyboardType.Email,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_password)) {
        PasswordField(
            value = form.password,
            onValueChange = { form.password = it },
            label = stringResource(R.string.entry_edit_field_password),
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_url_optional)) {
        TextInput(
            value = form.url,
            onValueChange = { form.url = it },
            label = stringResource(R.string.entry_edit_field_url_optional),
            placeholder = stringResource(R.string.entry_edit_hint_url),
            icon = Icons.Filled.Link,
            keyboardType = KeyboardType.Uri,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_2fa_optional)) {
        PasswordField(
            value = form.totpSecret,
            onValueChange = { if (form.changeTotpSecret(it)) onSecretAdded() },
            label = stringResource(R.string.entry_edit_field_2fa_optional),
            supportingText = form.totpError?.let { stringResource(totpErrorLabel(it)) } ?: stringResource(R.string.entry_edit_helper_2fa),
            isError = form.totpError != null,
            leadingIcon = Icons.Outlined.Shield,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_notes_optional)) {
        TextInput(
            value = form.notes,
            onValueChange = { form.notes = it },
            label = stringResource(R.string.entry_edit_field_notes_optional),
            placeholder = stringResource(R.string.entry_edit_hint_notes),
            icon = Icons.AutoMirrored.Filled.Notes,
            minLines = 3,
        )
    }
}

@Composable
private fun NoteFields(form: EntryForm) {
    LabelledField(stringResource(R.string.entry_edit_field_content)) {
        TextInput(
            value = form.notes,
            onValueChange = { form.notes = it },
            label = stringResource(R.string.entry_edit_field_content),
            placeholder = stringResource(R.string.entry_edit_hint_note_content),
            capitalization = KeyboardCapitalization.Sentences,
            minLines = 6,
        )
    }
}

@Composable
private fun CardFields(form: EntryForm) {
    LabelledField(stringResource(R.string.entry_edit_field_cardholder)) {
        TextInput(
            value = form.cardholder,
            onValueChange = { form.cardholder = it },
            label = stringResource(R.string.entry_edit_field_cardholder),
            placeholder = stringResource(R.string.entry_edit_hint_cardholder),
            icon = Icons.Outlined.PersonOutline,
            capitalization = KeyboardCapitalization.Words,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_card_number)) {
        FormattedInput(
            value = form.cardNumber,
            onValueChange = form::changeCardNumber,
            label = stringResource(R.string.entry_edit_field_card_number),
            placeholder = stringResource(R.string.entry_edit_hint_card_number),
            icon = Icons.Filled.CreditCard,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            LabelledField(stringResource(R.string.entry_edit_field_expiry)) {
                FormattedInput(
                    value = form.cardExpiry,
                    onValueChange = form::changeCardExpiry,
                    label = stringResource(R.string.entry_edit_field_expiry),
                    placeholder = stringResource(R.string.entry_edit_hint_expiry),
                    icon = Icons.Filled.CalendarToday,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            LabelledField(stringResource(R.string.entry_edit_field_cvv)) {
                PasswordField(
                    value = form.cardCvv,
                    onValueChange = form::changeCardCvv,
                    label = stringResource(R.string.entry_edit_field_cvv),
                    keyboardType = KeyboardType.NumberPassword,
                    leadingIcon = null,
                )
            }
        }
    }
    LabelledField(stringResource(R.string.entry_edit_field_pin_optional)) {
        PasswordField(
            value = form.cardPin,
            onValueChange = form::changeCardPin,
            label = stringResource(R.string.entry_edit_field_pin_optional),
            keyboardType = KeyboardType.NumberPassword,
            leadingIcon = Icons.Outlined.Pin,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_issuer_optional)) {
        TextInput(
            value = form.cardIssuer,
            onValueChange = { form.cardIssuer = it },
            label = stringResource(R.string.entry_edit_field_issuer_optional),
            placeholder = stringResource(R.string.entry_edit_hint_issuer),
            icon = Icons.Outlined.AccountBalance,
        )
    }
    LabelledField(stringResource(R.string.entry_edit_field_notes_optional)) {
        TextInput(
            value = form.notes,
            onValueChange = { form.notes = it },
            label = stringResource(R.string.entry_edit_field_notes_optional),
            placeholder = stringResource(R.string.entry_edit_hint_card_notes),
            minLines = 2,
        )
    }
}

@Composable
private fun CategoryChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(Categories.ALL) { category ->
            val color = EntryLook.categoryColor(category)
            val isSelected = category == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(category) },
                label = { Text(EntryLook.categoryLabel(category)?.let { stringResource(it) } ?: category, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        EntryLook.categoryIcon(category),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else color,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/** 2.7.1 puts a small bold label above each field, then the field with its own floating label. */
@Composable
private fun LabelledField(label: String, field: @Composable () -> Unit) {
    SectionLabel(label)
    Spacer(Modifier.height(6.dp))
    field()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, keyboardType = keyboardType),
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormattedInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(R.string.entry_edit_discard_title)) },
        text = { Text(stringResource(R.string.entry_edit_discard_body), fontSize = 13.sp) },
        // "Keep editing" first and harmless: the way out when Back was pressed by mistake.
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.entry_edit_discard_keep)) } },
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White),
            ) { Text(stringResource(R.string.entry_edit_discard_confirm)) }
        },
    )
}

private fun editorTitle(form: EntryForm): Int = when {
    form.isEdit -> R.string.entry_edit_title_edit
    form.type == EntryType.PASSWORD -> R.string.entry_edit_title_new_password
    form.type == EntryType.NOTE -> R.string.entry_edit_title_new_note
    else -> R.string.entry_edit_title_new_card
}

private fun titleHint(type: EntryType): Int = when (type) {
    EntryType.PASSWORD -> R.string.entry_edit_hint_title_password
    EntryType.NOTE -> R.string.entry_edit_hint_title_note
    EntryType.CARD -> R.string.entry_edit_hint_title_card
}

private fun totpErrorLabel(error: Totp.SecretError): Int = when (error) {
    Totp.SecretError.EMPTY -> R.string.totp_error_empty
    Totp.SecretError.INVALID_CHARACTERS -> R.string.totp_error_invalid_chars
    Totp.SecretError.TOO_SHORT -> R.string.totp_error_too_short
}
