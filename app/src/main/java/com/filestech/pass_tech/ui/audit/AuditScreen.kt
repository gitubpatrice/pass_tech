package com.filestech.pass_tech.ui.audit

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.audit.VaultAudit
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.theme.DestructiveRed
import com.filestech.pass_tech.ui.theme.FavoriteAmber

/**
 * The security audit (2.7.1, `audit_screen.dart`): a score, what the vault holds, the breach check,
 * and the lists behind each fault. Nothing here is written down; leaving the screen or locking the
 * vault takes it all with it.
 */
@Composable
fun AuditScreen(audit: AuditViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (Entry) -> Unit) {
    BackHandler(onBack = onBack)
    val ui by audit.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(ui.problem) {
        val problem = ui.problem ?: return@LaunchedEffect
        snackbar.showSnackbar(
            resources.getString(
                when (problem) {
                    AuditViewModel.Problem.NETWORK -> R.string.audit_breach_network_error
                    AuditViewModel.Problem.DISGUISED -> R.string.audit_breach_disguised
                },
            ),
        )
        audit.problemSeen()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = audit::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.audit_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ScoreCard(ui.audit) }
            item { StatisticsCard(ui.audit) }
            item { BreachCard(ui, onRun = audit::runBreachCheck, onOpen = onOpen) }
            faults(ui.audit, onOpen)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.faults(result: VaultAudit.Result, onOpen: (Entry) -> Unit) {
    item { FaultSection(R.string.audit_weak, Icons.Outlined.Key, DestructiveRed, result.weak, onOpen) }
    item { FaultSection(R.string.audit_duplicates, Icons.Outlined.ContentCopy, FavoriteAmber, result.duplicates, onOpen) }
    item { FaultSection(R.string.audit_no_second_factor, Icons.Outlined.Shield, FavoriteAmber, result.missingSecondFactor, onOpen) }
    // Shown, never counted: see AuditScore on why age does not lower a score.
    item { FaultSection(R.string.audit_stale, Icons.Outlined.HistoryToggleOff, Color.Unspecified, result.stale, onOpen) }
}

@Composable
private fun ScoreCard(result: VaultAudit.Result) {
    val score = result.score
    val colour = scoreColour(score)
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The icon carries the verdict as well as the colour: a colour alone says nothing to
                // someone who does not separate red from green (2.7.1).
                Icon(scoreIcon(score), contentDescription = null, tint = colour, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = score?.let { "$it/100" } ?: "—",
                    color = colour,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(stringResource(scoreLabel(score)), color = colour, fontWeight = FontWeight.SemiBold)
            if (result.partial) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.audit_score_partial),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatisticsCard(result: VaultAudit.Result) {
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Statistic(R.string.audit_stat_entries, result.entries)
            Statistic(R.string.audit_stat_passwords, result.passwords)
            Statistic(R.string.audit_stat_second_factor, result.withSecondFactor)
            Statistic(R.string.audit_stat_notes, result.notes)
            Statistic(R.string.audit_stat_cards, result.cards)
        }
    }
}

@Composable
private fun Statistic(@StringRes label: Int, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BreachCard(ui: AuditViewModel.UiState, onRun: () -> Unit, onOpen: (Entry) -> Unit) {
    val breached = ui.audit.breached
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.audit_breach_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(R.string.audit_breach_body),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                ui.checking -> {
                    LinearProgressIndicator(
                        progress = { if (ui.total == 0) 0f else ui.done.toFloat() / ui.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${ui.done} / ${ui.total}", fontSize = 12.sp)
                }
                breached == null -> Button(onClick = onRun) { Text(stringResource(R.string.audit_breach_run)) }
                breached.isEmpty() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.audit_breach_none), fontSize = 13.sp)
                    }
                    Button(onClick = onRun) { Text(stringResource(R.string.audit_breach_again)) }
                }
                else -> {
                    Text(
                        text = pluralStringResource(R.plurals.audit_breach_found, breached.size, breached.size),
                        color = DestructiveRed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    breached.forEach { EntryLine(it, onOpen) }
                    Button(onClick = onRun) { Text(stringResource(R.string.audit_breach_again)) }
                }
            }
            if (ui.failed > 0) {
                // Said plainly: the run went through, and these ones are simply not known either way.
                Text(
                    text = pluralStringResource(R.plurals.audit_breach_unanswered, ui.failed, ui.failed),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FaultSection(
    @StringRes title: Int,
    icon: ImageVector,
    colour: Color,
    entries: List<Entry>,
    onOpen: (Entry) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    PtCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = entries.isNotEmpty()) { open = !open }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = if (entries.isEmpty()) Color.Unspecified else colour)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(title), Modifier.weight(1f), fontSize = 14.sp)
                Text("${entries.size}", fontWeight = FontWeight.SemiBold)
                if (entries.isNotEmpty()) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (open) entries.forEach { EntryLine(it, onOpen) }
        }
    }
}

@Composable
private fun EntryLine(entry: Entry, onOpen: (Entry) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry) }
            .padding(start = 42.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
    ) {
        Text(entry.title, fontSize = 13.sp)
    }
}

private fun scoreColour(score: Int?): Color = when {
    score == null -> Color.Unspecified
    score >= GOOD -> ScoreGreen
    score >= FAIR -> FavoriteAmber
    else -> DestructiveRed
}

private fun scoreIcon(score: Int?): ImageVector = when {
    score == null -> Icons.Outlined.Shield
    score >= GOOD -> Icons.Filled.CheckCircle
    score >= FAIR -> Icons.Filled.WarningAmber
    else -> Icons.Filled.ErrorOutline
}

@StringRes
private fun scoreLabel(score: Int?): Int = when {
    score == null -> R.string.audit_score_none
    score >= EXCELLENT -> R.string.audit_score_excellent
    score >= GOOD -> R.string.audit_score_good
    score >= FAIR -> R.string.audit_score_fair
    else -> R.string.audit_score_poor
}

private const val EXCELLENT = 90
private const val GOOD = 70
private const val FAIR = 50
private val ScoreGreen = Color(0xFF2E7D32)
