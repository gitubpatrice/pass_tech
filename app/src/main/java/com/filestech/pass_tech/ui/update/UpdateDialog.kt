package com.filestech.pass_tech.ui.update

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.update.Release

/**
 * "Version X is out." The notes as they were published, and the checksum beside them so the owner
 * can check what they download against what the release claims.
 *
 * **Nothing downloads itself, and the button opens the release PAGE**, not the file: the owner
 * lands where the notes and the checksums are, and decides. An app that fetches and installs its
 * own successor is a way in for whoever manages to publish one.
 */
@Composable
fun UpdateDialog(release: Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null) },
        title = { Text(stringResource(R.string.update_title, release.version)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.update_body), fontSize = 13.sp)
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_notes_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(release.notes, fontSize = 12.sp)
                }
                release.sha256?.let {
                    Spacer(Modifier.height(12.dp))
                    // Named, not described: the checksum was matched on that file's name, and a hint
                    // naming a file the release does not carry sends the owner to check nothing.
                    Text(
                        text = release.apkName
                            ?.let { name -> stringResource(R.string.update_sha_label_named, name) }
                            ?: stringResource(R.string.update_sha_label),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.update_open)) }
        },
    )
}

/** The page, never the file. */
private const val RELEASES = "https://github.com/gitubpatrice/pass_tech/releases/latest"
