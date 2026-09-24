package com.filestech.pass_tech.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.about.AboutScreen
import com.filestech.pass_tech.ui.about.AboutViewModel
import com.filestech.pass_tech.ui.audit.AuditScreen
import com.filestech.pass_tech.ui.audit.AuditViewModel
import com.filestech.pass_tech.ui.components.PrivateKeyboard
import com.filestech.pass_tech.ui.entries.EntriesViewModel
import com.filestech.pass_tech.ui.entries.EntriesViewModel.Message
import com.filestech.pass_tech.ui.entries.EntriesViewModel.Screen
import com.filestech.pass_tech.ui.entries.EntryDetailScreen
import com.filestech.pass_tech.ui.entries.EntryEditScreen
import com.filestech.pass_tech.ui.entries.PhishingDialog
import com.filestech.pass_tech.ui.entry.EntryScreen
import com.filestech.pass_tech.ui.entry.EntryViewModel
import com.filestech.pass_tech.ui.entry.IntegrityDialog
import com.filestech.pass_tech.ui.generator.GeneratorScreen
import com.filestech.pass_tech.ui.heir.HeirScreen
import com.filestech.pass_tech.ui.home.HomeScreen
import com.filestech.pass_tech.ui.home.HomeViewModel
import com.filestech.pass_tech.ui.legal.LegalScreen
import com.filestech.pass_tech.ui.settings.SettingsScreen
import com.filestech.pass_tech.ui.settings.SettingsViewModel
import com.filestech.pass_tech.ui.splash.SplashScreen
import com.filestech.pass_tech.ui.splash.SplashViewModel
import com.filestech.pass_tech.ui.update.UpdateDialog
import com.filestech.pass_tech.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

/**
 * The root: the vault state picks the screen, the first-launch splash covers it, and the backup
 * reminder follows a creation over whichever screen is up (the home, by then).
 */
@Composable
fun PassTechApp(
    app: AppViewModel,
    entry: EntryViewModel,
    entries: EntriesViewModel,
    home: HomeViewModel,
    settings: SettingsViewModel,
    audit: AuditViewModel,
    about: AboutViewModel,
    update: UpdateViewModel,
    splash: SplashViewModel,
) {
    val vaultState by app.vaultState.collectAsStateWithLifecycle()
    val locking by app.locking.collectAsStateWithLifecycle()
    val entryState by entry.state.collectAsStateWithLifecycle()
    val showSplash by splash.shouldShow.collectAsStateWithLifecycle()
    var splashDismissed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    Messages(entries, snackbar)

    PrivateKeyboard {
        Box(Modifier.fillMaxSize()) {
            val state = vaultState
            when {
                // The auto-lock found the delay over on the way back: nothing of the vault, not even for a frame.
                locking -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                state is VaultManager.State.Open ->
                    OpenVault(state, Screens(entries, home, settings, audit, about), snackbar, onLock = app::lock)
                // Read only, and nothing of this app around it: no vault is open behind this screen.
                state is VaultManager.State.Heir -> HeirScreen(state.entries, snackbar, onCopy = entries::copy)
                else -> EntryScreen(entry)
            }
            if (showSplash == true && !splashDismissed) {
                SplashScreen(
                    onDismiss = {
                        splashDismissed = true
                        splash.markShown()
                    },
                )
            }
        }
    }
    if (entryState.backupReminder) BackupReminderDialog(onDismiss = entry::backupReminderSeen)
    // Over the unlock form, before anything is typed into it.
    if (entryState.integrity.isNotEmpty()) IntegrityDialog(entryState.integrity, onDismiss = entry::integritySeen)

    val newRelease by update.available.collectAsStateWithLifecycle()
    newRelease?.let { UpdateDialog(it, onDismiss = update::dismiss) }

    // Over whichever screen raised it, and gone with the lock: the copy it holds back is a password.
    val domainAlert by entries.domainAlert.collectAsStateWithLifecycle()
    domainAlert?.let {
        PhishingDialog(
            alert = it,
            onClose = entries::dismissDomainAlert,
            onCopyAnyway = entries::copyAnyway,
            onDeclareDomain = entries::openToDeclareDomain,
        )
    }
}

/** The home, or the screen on top of it: an entry's detail or the editor. */
@Composable
private fun OpenVault(state: VaultManager.State.Open, screens: Screens, snackbar: SnackbarHostState, onLock: () -> Unit) {
    val entries = screens.entries
    val home = screens.home
    val settings = screens.settings
    val audit = screens.audit
    val stack by entries.stack.collectAsStateWithLifecycle()
    when (val top = stack.lastOrNull()) {
        null -> HomeScreen(
            entries = state.entries,
            home = home,
            snackbar = snackbar,
            onGenerator = { entries.openGenerator() },
            onLock = onLock,
            onSettings = entries::openSettings,
            onAbout = entries::openAbout,
            onOpen = { entries.openDetail(it.id) },
            onAdd = entries::openNew,
            onToggleFavorite = { entries.toggleFavorite(it.id) },
            onDelete = entries::delete,
        )
        is Screen.Detail -> {
            val shown = state.entries.firstOrNull { it.id == top.id }
            if (shown == null) {
                // Deleted in the meantime.
                LaunchedEffect(top) { entries.close(top) }
            } else {
                EntryDetailScreen(
                    entry = shown,
                    snackbar = snackbar,
                    onBack = { entries.close(top) },
                    onToggleFavorite = { entries.toggleFavorite(shown.id) },
                    onEdit = { entries.openEditor(shown) },
                    onDelete = { entries.delete(shown) },
                    onCopy = entries::copy,
                )
            }
        }
        is Screen.Edit -> EntryEditScreen(
            form = top.form,
            snackbar = snackbar,
            onSave = { entries.save(top) },
            onLeave = { entries.close(top) },
            onSecretAdded = entries::secretAdded,
            onGenerate = { entries.openGenerator(top.form) },
        )
        is Screen.Generator -> GeneratorScreen(
            state = top.state,
            snackbar = snackbar,
            onBack = { entries.close(top) },
            onUse = if (top.target != null) ({ entries.useGenerated(top) }) else null,
            onCopy = entries::copyGenerated,
        )
        Screen.Settings -> SettingsScreen(
            settings = settings,
            snackbar = snackbar,
            onBack = { entries.close(Screen.Settings) },
            onAudit = entries::openAudit,
        )
        Screen.Audit -> AuditScreen(
            audit = audit,
            snackbar = snackbar,
            onBack = { entries.close(Screen.Audit) },
            onOpen = { entries.openDetail(it.id) },
        )
        Screen.About -> AboutScreen(
            about = screens.about,
            snackbar = snackbar,
            onBack = { entries.close(Screen.About) },
            onLegal = entries::openLegal,
        )
        is Screen.Legal -> LegalScreen(document = top.document, onBack = { entries.close(top) })
    }
}

/** The view models of the screens of an open vault. */
private data class Screens(
    val entries: EntriesViewModel,
    val home: HomeViewModel,
    val settings: SettingsViewModel,
    val audit: AuditViewModel,
    val about: AboutViewModel,
)

/** One message at a time: a new one replaces the one showing, as 2.7.1's snack bars do. */
@Composable
private fun Messages(entries: EntriesViewModel, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(entries, snackbar) {
        entries.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            // Only the unchecked-domain warning carries a button, and it stays up longer: it is the
            // one message that asks the owner to go and do something.
            val action = if (message == Message.DomainUnchecked) resources.getString(R.string.phishing_unchecked_action) else null
            launch {
                val result = snackbar.showSnackbar(
                    message = message.text(resources),
                    actionLabel = action,
                    duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) entries.openAccessibilitySettings()
            }
        }
    }
}

private fun Message.text(resources: Resources): String = when (this) {
    is Message.Copied -> {
        val label = resources.getString(label)
        if (clearedInSeconds == null) {
            resources.getString(R.string.entry_detail_copied_snack_kept, label)
        } else {
            resources.getString(R.string.entry_detail_copied_snack, label, clearedInSeconds)
        }
    }
    is Message.Deleted -> resources.getString(R.string.home_deleted_snack, title)
    Message.TitleRequired -> resources.getString(R.string.entry_edit_title_required)
    Message.SecretAdded -> resources.getString(R.string.entry_edit_secret_added)
    Message.GeneratedCopied -> resources.getString(R.string.generator_copied_snack)
    Message.KeystoreUnavailable -> resources.getString(R.string.keystore_unavailable)
    Message.DomainUnchecked -> resources.getString(R.string.phishing_unchecked_snack)
}

/** 2.7.1: shown once, right after the creation, one button. */
@Composable
private fun BackupReminderDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_reminder_title)) },
        text = {
            Text(
                text = stringResource(R.string.backup_reminder_body),
                fontSize = 13.sp,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
