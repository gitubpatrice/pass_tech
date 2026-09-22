package com.filestech.pass_tech.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ContentPasteOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import com.filestech.pass_tech.ui.components.PasswordField
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.components.authenticate
import com.filestech.pass_tech.ui.components.countdownText
import com.filestech.pass_tech.ui.settings.SettingsViewModel.ChangeProblem
import com.filestech.pass_tech.ui.settings.SettingsViewModel.Message
import com.filestech.pass_tech.ui.theme.DestructiveRed

/** Which dialog is up: one at a time. */
private enum class SettingsDialog { THEME, CLIPBOARD, AUTO_LOCK, SCREENSHOTS_OFF, CHANGE_PASSWORD, DELETE_ALL, DELETE_REAUTH }

/**
 * The settings (2.7.1, `settings_screen.dart`): each setting on its own card under a section title.
 * The rest of 2.7.1's settings (decoy, panic, heir, data) come with their features.
 */
@Composable
fun SettingsScreen(settings: SettingsViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by settings.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val haptics = LocalHapticFeedback.current
    val biometrics by settings.biometrics.collectAsStateWithLifecycle()
    Messages(settings, snackbar)
    ArmingPrompts(settings)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionTitle(R.string.settings_section_appearance) }
            item {
                Tile(Icons.Outlined.BrightnessMedium, stringResource(R.string.settings_theme_title), stringResource(themeLabel(ui.theme))) {
                    dialog = SettingsDialog.THEME
                }
            }
            item { SectionTitle(R.string.settings_section_clipboard) }
            item {
                Tile(
                    Icons.Outlined.ContentPasteOff,
                    stringResource(R.string.settings_clipboard_title),
                    stringResource(clipboardLabel(ui.clipboardSeconds)),
                ) { dialog = SettingsDialog.CLIPBOARD }
            }
            item { SectionTitle(R.string.settings_section_security) }
            item {
                ScreenshotTile(ui.screenshotProtection) { on ->
                    if (on) settings.setScreenshotProtection(true) else dialog = SettingsDialog.SCREENSHOTS_OFF
                }
            }
            // Offered when the phone can authenticate, and kept while THIS vault is armed, to turn it off.
            // Never for a vault armed elsewhere: its tile would differ from a phone where nothing is armed.
            if (biometrics.available || biometrics.status == BiometricStatus.THIS_VAULT) {
                item {
                    BiometricTile(
                        status = biometrics.status,
                        onEnable = settings::enableBiometrics,
                        onDisable = settings::disableBiometrics,
                    )
                }
            }
            item {
                Tile(Icons.Outlined.Key, stringResource(R.string.settings_change_master_title)) {
                    dialog = SettingsDialog.CHANGE_PASSWORD
                }
            }
            item {
                Tile(
                    Icons.Outlined.Timer,
                    stringResource(R.string.settings_auto_lock_title),
                    stringResource(autoLockLabel(ui.autoLockSeconds)),
                ) { dialog = SettingsDialog.AUTO_LOCK }
            }
            item {
                Tile(Icons.Outlined.Lock, stringResource(R.string.settings_lock_now), chevron = false) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    settings.lockNow()
                }
            }
            item { SectionTitle(R.string.settings_section_danger) }
            item {
                Tile(
                    icon = Icons.Outlined.DeleteForever,
                    title = stringResource(R.string.settings_delete_all_title),
                    subtitle = stringResource(R.string.settings_delete_all_subtitle),
                    chevron = false,
                    danger = true,
                ) { dialog = SettingsDialog.DELETE_ALL }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        SettingsDialog.THEME -> ChoiceDialog(
            title = R.string.settings_theme_choose_title,
            choices = AppPreferences.Theme.entries,
            selected = ui.theme,
            label = ::themeLabel,
            icon = ::themeIcon,
            onChoose = {
                settings.setTheme(it)
                close()
            },
            onDismiss = close,
        )
        SettingsDialog.CLIPBOARD -> ChoiceDialog(
            title = R.string.settings_clipboard_dialog_title,
            choices = AppPreferences.CLIPBOARD_CHOICES,
            selected = ui.clipboardSeconds,
            label = ::clipboardLabel,
            onChoose = {
                settings.setClipboard(it)
                close()
            },
            onDismiss = close,
        )
        SettingsDialog.AUTO_LOCK -> ChoiceDialog(
            title = R.string.settings_auto_lock_dialog_title,
            choices = AppPreferences.AUTO_LOCK_CHOICES,
            selected = ui.autoLockSeconds,
            label = ::autoLockLabel,
            onChoose = {
                settings.setAutoLock(it)
                close()
            },
            onDismiss = close,
        )
        SettingsDialog.SCREENSHOTS_OFF -> ConfirmDialog(
            title = R.string.settings_screenshot_protection_confirm_off_title,
            body = R.string.settings_screenshot_protection_confirm_off_body,
            confirm = R.string.action_disable,
            onConfirm = {
                settings.setScreenshotProtection(false)
                close()
            },
            onDismiss = close,
        )
        SettingsDialog.CHANGE_PASSWORD -> ChangePasswordDialog(
            onChange = { current, new ->
                close()
                settings.changePassword(current, new)
            },
            onDismiss = close,
        )
        SettingsDialog.DELETE_ALL -> ConfirmDialog(
            title = R.string.settings_delete_all_dialog_title,
            body = R.string.settings_delete_all_dialog_body,
            confirm = R.string.settings_delete_all_confirm,
            onConfirm = { dialog = SettingsDialog.DELETE_REAUTH },
            onDismiss = close,
        )
        SettingsDialog.DELETE_REAUTH -> ReauthDialog(
            onConfirm = { password ->
                close()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                settings.deleteAll(password)
            },
            onDismiss = close,
        )
        null -> Unit
    }
    if (ui.busy) BusyDialog()
}

@Composable
private fun Messages(settings: SettingsViewModel, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(settings, snackbar) {
        settings.messages.collect { message ->
            val text = when (message) {
                Message.PasswordChanged -> resources.getString(R.string.change_password_done)
                Message.PasswordChangedBiometricsReset -> resources.getString(R.string.change_password_done_biometric_reset)
                Message.BiometricsEnabled -> resources.getString(R.string.settings_biometric_enabled)
                Message.BiometricsDisabled -> resources.getString(R.string.settings_biometric_disabled)
                Message.BiometricsCanceled -> resources.getString(R.string.settings_biometric_enable_canceled)
                Message.BiometricsFailed -> resources.getString(R.string.settings_biometric_enable_failed)
                Message.BiometricsRefused -> resources.getString(R.string.settings_biometric_decoy_conflict)
                Message.WrongPassword -> resources.getString(R.string.change_password_error_wrong_current)
                Message.PasswordRefused -> resources.getString(R.string.change_password_refused)
                is Message.Locked -> resources.getString(R.string.settings_locked_retry, countdownText(resources, message.remainingMillis))
                Message.KeystoreUnavailable -> resources.getString(R.string.keystore_unavailable)
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
}

/** Reads the phone's biometric support each time the screen shows, and hands arming ciphers to the system prompt. */
@Composable
private fun ArmingPrompts(settings: SettingsViewModel) {
    LaunchedEffect(settings) { settings.refreshBiometricSupport() }
    val activity = LocalActivity.current as? FragmentActivity ?: return
    LaunchedEffect(settings, activity) {
        settings.prompts.collect { cipher -> settings.armResult(activity.authenticate(cipher)) }
    }
}

/**
 * On when a fingerprint opens THIS vault, off in every other case, and the screen says nothing more.
 *
 * Design v2 §9 had this tile warn the owner that a fingerprint opens ANOTHER vault. Patrice dropped that
 * warning on 2026-09-22, after both reviewers called it an oracle: a vault must never tell anything about
 * a vault that is not itself, even to someone who knows this one's password. Turning the switch on arms
 * this vault instead, which is the only thing the owner needs here.
 *
 * The note under it is 2.7.1's, for phones that do not invalidate the key at a new enrolment.
 */
@Composable
private fun BiometricTile(status: BiometricStatus, onEnable: () -> Unit, onDisable: () -> Unit) {
    val on = status == BiometricStatus.THIS_VAULT
    val change = { checked: Boolean -> if (checked) onEnable() else onDisable() }
    PtCard(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = iconTint()) },
                headlineContent = { Text(stringResource(R.string.settings_biometric_title)) },
                supportingContent = { Text(stringResource(R.string.settings_biometric_subtitle)) },
                trailingContent = { Switch(checked = on, onCheckedChange = change) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { change(!on) },
            )
            Text(
                text = stringResource(R.string.settings_biometric_new_enrollment_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** 2.7.1: 16 sp, the brand colour (`cs.primary`, which keeps its contrast in both themes). */
@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 20.dp, end = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    chevron: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    PtCard(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = if (danger) DestructiveRed else iconTint()) },
            headlineContent = { Text(title, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = if (chevron) {
                { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun iconTint(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Off shows the shield in the error colour, as 2.7.1 does. */
@Composable
private fun ScreenshotTile(enabled: Boolean, onChange: (Boolean) -> Unit) {
    PtCard(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = if (enabled) iconTint() else MaterialTheme.colorScheme.error,
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_screenshot_protection_title)) },
            supportingContent = { Text(stringResource(R.string.settings_screenshot_protection_subtitle), fontSize = 12.sp) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = onChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onChange(!enabled) },
        )
    }
}

@Composable
private fun <T> ChoiceDialog(
    @StringRes title: Int,
    choices: List<T>,
    selected: T,
    label: (T) -> Int,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
    icon: ((T) -> ImageVector)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                choices.forEach { choice ->
                    ListItem(
                        leadingContent = icon?.let { { Icon(it(choice), contentDescription = null, modifier = Modifier.size(20.dp)) } },
                        headlineContent = { Text(stringResource(label(choice))) },
                        trailingContent = if (choice == selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onChoose(choice) },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White),
            ) { Text(stringResource(confirm)) }
        },
    )
}

/** Current, new, confirmation; checked in 2.7.1's order before anything reaches the vault. */
@Composable
private fun ChangePasswordDialog(onChange: (current: String, new: String) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<ChangeProblem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(current, { current = it }, stringResource(R.string.change_password_current_label), leadingIcon = null)
                PasswordField(new, { new = it }, stringResource(R.string.change_password_new_label), leadingIcon = null)
                PasswordField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.change_password_confirm_label),
                    leadingIcon = null,
                )
                problem?.let { Text(stringResource(changeProblemLabel(it)), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            Button(onClick = {
                problem = SettingsViewModel.checkChange(current, new, confirmation)
                if (problem == null) onChange(current, new)
            }) { Text(stringResource(R.string.change_password_cta)) }
        },
    )
}

@Composable
private fun ReauthDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reauth_title)) },
        text = {
            Column {
                Text(stringResource(R.string.reauth_body), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.change_password_current_label),
                    leadingIcon = null,
                    onImeAction = { if (password.isNotEmpty()) onConfirm(password) },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.action_continue)) }
        },
    )
}

/** An Argon2id derivation runs: nothing to tap until it is over (2.7.1's spinner). */
@Composable
private fun BusyDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun themeLabel(theme: AppPreferences.Theme): Int = when (theme) {
    AppPreferences.Theme.SYSTEM -> R.string.settings_theme_system
    AppPreferences.Theme.LIGHT -> R.string.settings_theme_light
    AppPreferences.Theme.DARK -> R.string.settings_theme_dark
}

private fun themeIcon(theme: AppPreferences.Theme): ImageVector = when (theme) {
    AppPreferences.Theme.SYSTEM -> Icons.Outlined.SettingsBrightness
    AppPreferences.Theme.LIGHT -> Icons.Outlined.LightMode
    AppPreferences.Theme.DARK -> Icons.Outlined.DarkMode
}

/** The labels of [AppPreferences.CLIPBOARD_CHOICES], in the same order. */
private val CLIPBOARD_LABELS = AppPreferences.CLIPBOARD_CHOICES.zip(
    listOf(
        R.string.settings_clipboard_15s,
        R.string.settings_clipboard_30s,
        R.string.settings_clipboard_60s,
        R.string.settings_clipboard_never,
    ),
).toMap()

/** The labels of [AppPreferences.AUTO_LOCK_CHOICES], in the same order. */
private val AUTO_LOCK_LABELS = AppPreferences.AUTO_LOCK_CHOICES.zip(
    listOf(
        R.string.settings_auto_lock_immediate,
        R.string.settings_auto_lock_1min,
        R.string.settings_auto_lock_5min,
        R.string.settings_auto_lock_15min,
        R.string.settings_auto_lock_30min,
        R.string.settings_auto_lock_never,
    ),
).toMap()

private fun clipboardLabel(seconds: Int): Int = CLIPBOARD_LABELS.getValue(seconds)

private fun autoLockLabel(seconds: Int): Int = AUTO_LOCK_LABELS.getValue(seconds)

private fun changeProblemLabel(problem: ChangeProblem): Int = when (problem) {
    ChangeProblem.CURRENT_REQUIRED -> R.string.change_password_error_current_required
    ChangeProblem.TOO_SHORT -> R.string.setup_error_min
    ChangeProblem.TOO_WEAK -> R.string.password_too_weak
    ChangeProblem.MISMATCH -> R.string.setup_error_mismatch
}
