package com.filestech.pass_tech.ui.heir

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost

/**
 * What an heir sees (2.7.1, `heir_view_screen.dart`): a snapshot of the vault, read only, and no way
 * back into anything else. Going back is disabled and the one button closes the app — from here
 * there is no vault to return to, since none is open.
 *
 * **Never the card fields, never the 2FA secrets.** Those open doors of their own — a card number is
 * money, a TOTP seed is a second factor that the heir would then hold for good — and an heir needs
 * neither to wind up an estate.
 */
@Composable
fun HeirScreen(entries: List<Entry>, snackbar: SnackbarHostState, onCopy: (String, Int) -> Unit) {
    // The way out is the button, not the gesture: a back press here used to land on whatever was
    // behind, and there is nothing behind.
    BackHandler {}
    val activity = LocalActivity.current
    val resources = LocalResources.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.heir_view_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Banner(
                    text = if (entries.isEmpty()) {
                        stringResource(R.string.heir_view_banner_none)
                    } else {
                        resources.getQuantityString(R.plurals.heir_view_banner, entries.size, entries.size)
                    },
                )
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.heir_view_empty), Modifier.padding(16.dp)) }
            }
            items(entries, key = { it.id }) { entry -> HeirEntry(entry, onCopy) }
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { activity?.finishAndRemoveTask() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text(stringResource(R.string.heir_view_close)) }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    PtCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One entry, closed until it is tapped, as 2.7.1's expansion tiles are. */
@Composable
private fun HeirEntry(entry: Entry, onCopy: (String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PtCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(entry.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (open) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    if (entry.username.isNotEmpty()) {
                        CopyableField(R.string.heir_view_field_username, entry.username, onCopy)
                    }
                    if (entry.password.isNotEmpty()) {
                        SecretField(entry.password, onCopy)
                    }
                    if (entry.url.isNotEmpty()) PlainField(R.string.heir_view_field_url, entry.url)
                    if (entry.notes.isNotEmpty()) PlainField(R.string.heir_view_field_notes, entry.notes)
                }
            }
        }
    }
}

@Composable
private fun CopyableField(@StringRes label: Int, value: String, onCopy: (String, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FieldLabel(label)
            SelectionContainer { Text(value, fontSize = 14.sp) }
        }
        IconButton(onClick = { onCopy(value, label) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(label), modifier = Modifier.size(18.dp))
        }
    }
}

/** The password: hidden until the eye is tapped, and copied without ever being shown if need be. */
@Composable
private fun SecretField(value: String, onCopy: (String, Int) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FieldLabel(R.string.heir_view_field_password)
            Text(
                text = if (shown) value else "•".repeat(value.length.coerceAtMost(MASK_LENGTH)),
                fontSize = 14.sp,
                fontFamily = if (shown) FontFamily.Monospace else FontFamily.Default,
            )
        }
        IconButton(onClick = { shown = !shown }) {
            Icon(
                imageVector = if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(if (shown) R.string.hide_password else R.string.show_password),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = { onCopy(value, R.string.heir_view_field_password) }) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.heir_view_field_password),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PlainField(@StringRes label: Int, value: String) {
    Column {
        FieldLabel(label)
        SelectionContainer { Text(value, fontSize = 14.sp) }
    }
}

@Composable
private fun FieldLabel(@StringRes label: Int) {
    Text(stringResource(label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MASK_LENGTH = 12
