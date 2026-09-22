package com.filestech.pass_tech.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.vault.VaultRepository.EntryMode
import com.filestech.pass_tech.ui.components.HeaderBadge
import com.filestech.pass_tech.ui.components.PasswordField
import com.filestech.pass_tech.ui.components.StrengthGauge
import com.filestech.pass_tech.ui.entry.EntryViewModel.Problem

/** The creation or unlock form, laid out as the 2.7.1 setup and unlock screens. */
@Composable
fun EntryScreen(viewModel: EntryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode = state.mode ?: return
    val locked = state.lockedForMillis > 0
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge to edge, the window no longer shrinks for the keyboard: without this, the keyboard
                // covered the confirmation field and the button (seen on the Galaxy S9, 2026-09-22).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val colors = MaterialTheme.colorScheme
            when {
                locked -> HeaderBadge(Icons.Outlined.LockClock, colors.error.copy(alpha = 0.15f), colors.error)
                mode == EntryMode.CREATE -> HeaderBadge(Icons.Outlined.Lock, colors.primaryContainer, colors.primary)
                else -> HeaderBadge(Icons.Filled.Lock, colors.primaryContainer, colors.primary)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(if (mode == EntryMode.CREATE) R.string.setup_title else R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(if (mode == EntryMode.CREATE) 8.dp else 6.dp))
            Text(
                text = stringResource(
                    when {
                        locked -> R.string.unlock_too_many_attempts
                        mode == EntryMode.CREATE -> R.string.setup_subtitle
                        else -> R.string.unlock_enter_master
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            when {
                locked -> LockedBox(state.lockedForMillis)
                mode == EntryMode.CREATE -> CreateForm(state, onCreate = viewModel::create)
                else -> UnlockForm(state, onUnlock = viewModel::unlock)
            }
        }
    }
}

@Composable
private fun CreateForm(state: EntryViewModel.UiState, onCreate: (String, String) -> Unit) {
    // `remember`, never `rememberSaveable`: a password must not end up in the saved instance state.
    var master by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    PasswordField(
        value = master,
        onValueChange = { master = it },
        label = stringResource(R.string.setup_master_label),
        supportingText = stringResource(R.string.setup_master_helper),
        imeAction = ImeAction.Next,
        readOnly = state.busy,
    )
    if (master.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        StrengthGauge(master)
    }
    Spacer(Modifier.height(16.dp))
    PasswordField(
        value = confirmation,
        onValueChange = { confirmation = it },
        label = stringResource(R.string.setup_confirm_label),
        onImeAction = { onCreate(master, confirmation) },
        readOnly = state.busy,
    )
    ProblemText(state.problem)
    Spacer(Modifier.height(24.dp))
    if (state.busy) {
        Busy(stringResource(R.string.setup_encrypting))
    } else {
        Button(
            onClick = { onCreate(master, confirmation) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_create_cta))
        }
    }
    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.setup_warning), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnlockForm(state: EntryViewModel.UiState, onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // 2.7.1 empties the field after every attempt; the focus comes back to it for the next one.
    LaunchedEffect(state.problem) {
        if (state.problem != null) {
            password = ""
            focus.requestFocus()
        }
    }
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.unlock_master_label),
        onImeAction = { onUnlock(password) },
        readOnly = state.busy,
        modifier = Modifier.focusRequester(focus),
    )
    ProblemText(state.problem)
    Spacer(Modifier.height(24.dp))
    if (state.busy) {
        Busy(stringResource(R.string.unlock_decrypting))
    } else {
        Button(
            onClick = { onUnlock(password) },
            enabled = password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.unlock_cta))
        }
    }
}

@Composable
private fun ProblemText(problem: Problem?) {
    val text = when (problem) {
        null -> return
        Problem.TOO_SHORT -> R.string.setup_error_min
        Problem.TOO_WEAK -> R.string.password_too_weak
        Problem.MISMATCH -> R.string.setup_error_mismatch
        Problem.WRONG_PASSWORD -> R.string.unlock_wrong_password
        Problem.IMPOSSIBLE -> R.string.setup_error_impossible
        Problem.KEYSTORE_UNAVAILABLE -> R.string.keystore_unavailable
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(text), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Busy(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

/** 2.7.1: the form gives way to a red box with a countdown in large monospaced digits. */
@Composable
private fun LockedBox(millis: Long) {
    val error = MaterialTheme.colorScheme.error
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(error.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, error.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(stringResource(R.string.unlock_try_again_in), color = error, fontSize = 12.sp)
        Box(Modifier.height(4.dp))
        Text(
            text = countdownText(millis),
            color = error,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 2.7.1 format: under a minute in seconds, round minutes alone, otherwise minutes and seconds. */
@Composable
private fun countdownText(millis: Long): String {
    val total = ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    val minutes = total / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return when {
        minutes == 0 -> stringResource(R.string.unit_seconds_short, seconds)
        seconds == 0 -> stringResource(R.string.unit_minutes_short, minutes)
        else -> stringResource(R.string.unit_minutes_seconds_short, minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
