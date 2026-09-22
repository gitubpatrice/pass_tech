package com.filestech.pass_tech.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.entry.EntryScreen
import com.filestech.pass_tech.ui.entry.EntryViewModel
import com.filestech.pass_tech.ui.home.HomeScreen
import com.filestech.pass_tech.ui.splash.SplashScreen
import com.filestech.pass_tech.ui.splash.SplashViewModel

/**
 * The root: the vault state picks the screen, the first-launch splash covers it, and the backup
 * reminder follows a creation over whichever screen is up (the home, by then).
 */
@Composable
fun PassTechApp(app: AppViewModel, entry: EntryViewModel, splash: SplashViewModel) {
    val vaultState by app.vaultState.collectAsStateWithLifecycle()
    val locking by app.locking.collectAsStateWithLifecycle()
    val entryState by entry.state.collectAsStateWithLifecycle()
    val showSplash by splash.shouldShow.collectAsStateWithLifecycle()
    var splashDismissed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        val state = vaultState
        when {
            // The auto-lock found the delay over on the way back: nothing of the vault, not even for a frame.
            locking -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            state is VaultManager.State.Open -> HomeScreen(entries = state.entries, onLock = app::lock)
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
    if (entryState.backupReminder) BackupReminderDialog(onDismiss = entry::backupReminderSeen)
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
