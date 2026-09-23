package com.filestech.pass_tech.ui.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.phishing.DomainMatch

/**
 * The copy is held: the browser in front is not on the site the entry belongs to.
 *
 * A look-alike domain can be a false alarm — an owner who knows their site keeps a way through. A
 * plainly different one has none, and the dialog cannot be dismissed by tapping beside it either:
 * the one action left is to read the two domains.
 */
@Composable
fun PhishingDialog(alert: EntriesViewModel.DomainAlert, onClose: () -> Unit, onCopyAnyway: () -> Unit) {
    val lookAlike = alert.check.verdict == DomainMatch.Verdict.TYPOSQUATTING
    AlertDialog(
        onDismissRequest = { if (lookAlike) onClose() },
        icon = {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(ICON_SIZE),
            )
        },
        title = { Text(stringResource(if (lookAlike) R.string.phishing_typo_title else R.string.phishing_mismatch_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(if (lookAlike) R.string.phishing_typo_body else R.string.phishing_mismatch_body),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                DomainRow(stringResource(R.string.phishing_expected), alert.check.expected, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                DomainRow(stringResource(R.string.phishing_active), alert.check.active, MaterialTheme.colorScheme.error)
                alert.check.distance?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.phishing_distance, it),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(if (lookAlike) R.string.action_cancel else R.string.phishing_close))
            }
        },
        confirmButton = {
            if (lookAlike) {
                TextButton(onClick = onCopyAnyway) {
                    Text(stringResource(R.string.phishing_copy_anyway), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun DomainRow(label: String, domain: String?, color: Color) {
    Row {
        Text(
            text = label,
            modifier = Modifier.width(DOMAIN_LABEL_WIDTH),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = domain ?: "—",
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DOMAIN_LABEL_WIDTH = 92.dp
private val ICON_SIZE = 40.dp
