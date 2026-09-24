package com.filestech.pass_tech.ui.entry

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.integrity.IntegrityIssue

/**
 * What the app noticed about the phone, said **before** the master password is typed. Afterwards is
 * too late to learn that the phone can be read over your shoulder.
 *
 * It closes with one button and stays quiet until the phone CHANGES. It never blocks anything:
 * refusing to open would lock people out of their own vault on their own phone, and these checks are
 * easy to hide from — the last line says so, because a warning that presents itself as a guarantee
 * is worse than none.
 */
@Composable
fun IntegrityDialog(issues: Set<IntegrityIssue>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PhonelinkLock, contentDescription = null) },
        title = { Text(stringResource(R.string.integrity_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.integrity_body), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                // In the order of the enum, so the same phone always reads the same way.
                IntegrityIssue.entries.filter { it in issues }.forEach {
                    Text("• " + stringResource(label(it)), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.integrity_best_effort),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.integrity_understood)) } },
    )
}

@StringRes
private fun label(issue: IntegrityIssue): Int = when (issue) {
    IntegrityIssue.ROOTED -> R.string.integrity_rooted
    IntegrityIssue.DEBUGGER_ATTACHED -> R.string.integrity_debugger
    IntegrityIssue.DEBUGGABLE_BUILD -> R.string.integrity_debuggable
    IntegrityIssue.EMULATOR -> R.string.integrity_emulator
}
