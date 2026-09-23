package com.filestech.pass_tech.ui.about

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.about.AppFacts
import com.filestech.pass_tech.core.update.UpdateCheck
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.update.UpdateDialog
import kotlinx.coroutines.launch

/**
 * What this app is, what it does, and what it never does (2.7.1, `about_screen.dart`).
 *
 * It is the app's own description of itself, so everything on it has been checked against the code
 * rather than carried across. Three of 2.7.1's claims had outlived what they described:
 *
 * - it announced a **"Keystore-bound KEK (StrongBox/TEE)"**, a key design v2.2 removed. What replaced
 *   it is stronger and had to be said differently: the chip now takes part in every attempt;
 * - it said the app **"checks for updates automatically at launch"**. It did reach the network at
 *   launch, and threw the answer away (design §18). The check now happens once the vault is open,
 *   and what it finds is shown;
 * - its verification hint read `sha256sum app-arm64-v8a-release.apk` — a file no release of this
 *   project has ever published. Anyone following it would have checked nothing.
 *
 * A fourth thing was missing rather than wrong: six badges said no tracker, no collection, no
 * sharing, and **nothing on the screen said the app uses the network at all**. It does, twice, and
 * the privacy policy says so; the screen an owner actually reads did not.
 *
 * Reachable only from an open vault, as in 2.7.1. It says nothing about the vault that is open, so
 * it reads the same from the decoy as from the real one.
 */
@Composable
fun AboutScreen(about: AboutViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val check by about.check.collectAsStateWithLifecycle()
    val groups = aboutGroups()
    val help = aboutHelp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cannotOpen = stringResource(R.string.about_link_failed)
    val open: (Intent) -> Unit = { intent ->
        // A phone with no browser, or no mail app, answers by throwing: it is told, not ignored.
        val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!started) scope.launch { snackbar.showSnackbar(cannotOpen) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Header(about.installedVersion, check, about::checkNow) }
            privacySection()
            featureSections(groups)
            verifySection()
            helpSection(help)
            authorSection()
            linksSection(about.installedVersion, open)
        }
    }

    // The one answer with somewhere better to go than a line under a button.
    ((check as? AboutViewModel.Check.Done)?.outcome as? UpdateCheck.Outcome.Newer)?.let {
        UpdateDialog(it.release, onDismiss = about::clear)
    }
}

/** The icon, the name, the version this build carries, and the check the owner can ask for. */
@Composable
private fun Header(version: String, check: AboutViewModel.Check, onCheck: () -> Unit) {
    val running = check is AboutViewModel.Check.Running
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.pass_tech_logo),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        // Read at run time from the build, so no constant in the code can fall behind the release.
        Text(
            text = stringResource(R.string.about_version, version),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCheck, enabled = !running) {
            if (running) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = stringResource(if (running) R.string.about_checking else R.string.about_check_updates),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // "Up to date" and "could not ask" are two different things, and 2.7.1 answered the first to both.
        checkedLine(check)?.let {
            Text(
                text = stringResource(it),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
        }
    }
}

/**
 * What a finished check has to say under the button. A newer version has its own dialog, so it says
 * nothing here; "too soon" never comes back from a check the owner asked for, and is folded into the
 * same honest answer rather than being given a line that cannot appear.
 */
@StringRes
private fun checkedLine(check: AboutViewModel.Check): Int? = when ((check as? AboutViewModel.Check.Done)?.outcome) {
    UpdateCheck.Outcome.UpToDate -> R.string.about_check_up_to_date
    UpdateCheck.Outcome.Unreachable, UpdateCheck.Outcome.TooSoon -> R.string.about_check_unreachable
    UpdateCheck.Outcome.Disguised -> R.string.about_check_disguised
    UpdateCheck.Outcome.UnreadableVersion -> R.string.about_check_unreadable_version
    is UpdateCheck.Outcome.Newer, null -> null
}

/** The six promises, then the two exceptions to them, which this screen owes its reader. */
private fun LazyListScope.privacySection() {
    item { SectionTitle(R.string.about_section_privacy) }
    item { PrivacyBadges() }
    item { NetworkCard() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyBadges() {
    PtCard(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            PRIVACY_BADGES.forEach { (icon, label) -> Badge(icon, label) }
        }
    }
}

/**
 * The two network uses, named. An app that lists six things it never does, and says nothing of the
 * two it does, has told the truth six times and left the reader with a false picture.
 */
@Composable
private fun NetworkCard() {
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(stringResource(R.string.about_privacy_network_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(stringResource(R.string.about_privacy_network_body, AppFacts.updateChecksPerDay), fontSize = 12.sp)
        }
    }
}

/** Five headings rather than the one list of twenty-five rows that 2.7.1 showed. */
private fun LazyListScope.featureSections(groups: List<FeatureGroup>) {
    groups.forEach { group ->
        item { SectionTitle(group.title) }
        group.features.forEach { feature -> item { FeatureRow(feature) } }
    }
}

@Composable
private fun FeatureRow(feature: Feature) {
    PtCard(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(
                    feature.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            headlineContent = { Text(feature.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
            supportingContent = { Text(feature.description, fontSize = 12.sp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** How to tell that what was downloaded is what was published. It names no file it cannot see. */
private fun LazyListScope.verifySection() {
    item { SectionTitle(R.string.about_section_verify) }
    item {
        PtCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.about_verify_body), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
        }
    }
}

private fun LazyListScope.helpSection(help: List<HelpCard>) {
    item { SectionTitle(R.string.about_section_help) }
    help.forEach { card -> item { HelpCardView(card) } }
}

@Composable
private fun HelpCardView(card: HelpCard) {
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(card.title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            card.steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${index + 1}.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(step, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun LazyListScope.authorSection() {
    item { SectionTitle(R.string.about_section_author) }
    item {
        PtCard(Modifier.fillMaxWidth()) {
            ListItem(
                leadingContent = {
                    Icon(Icons.Outlined.PersonOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(stringResource(R.string.about_author_name)) },
                supportingContent = { Text(stringResource(R.string.about_author_role)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/**
 * Where to go, and how to say that something is wrong.
 *
 * The report carries the version and the phone, because those are the two answers every report needs
 * and the two an owner should not have to go looking for. It carries nothing else, and the mail app
 * shows the whole of it before anything is sent.
 */
private fun LazyListScope.linksSection(version: String, open: (Intent) -> Unit) {
    item { SectionTitle(R.string.about_section_links) }
    item { Link(Icons.Outlined.Public, R.string.about_link_website) { open(view(WEBSITE)) } }
    item { Link(Icons.Outlined.Link, R.string.about_link_source) { open(view(SOURCE)) } }
    item { ReportLink(version, open) }
    item { LicenceRow() }
}

@Composable
private fun ReportLink(version: String, open: (Intent) -> Unit) {
    val subject = stringResource(R.string.about_report_subject, version)
    val body = stringResource(R.string.about_report_body, version, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE)
    Link(Icons.Outlined.WarningAmber, R.string.about_link_report, R.string.about_link_report_subtitle) {
        open(report(subject, body))
    }
}

@Composable
private fun LicenceRow() {
    PtCard(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Policy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(stringResource(R.string.about_link_licence)) },
            supportingContent = { Text(stringResource(R.string.about_link_licence_value)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun Link(icon: ImageVector, @StringRes title: Int, @StringRes subtitle: Int? = null, onClick: () -> Unit) {
    PtCard(Modifier.fillMaxWidth(), onClick = onClick) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(stringResource(title)) },
            supportingContent = subtitle?.let { { Text(stringResource(it), fontSize = 12.sp) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

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
private fun Badge(icon: ImageVector, @StringRes label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Text(stringResource(label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val PRIVACY_BADGES = listOf(
    Icons.Outlined.Block to R.string.about_privacy_no_ads,
    Icons.Outlined.QueryStats to R.string.about_privacy_no_tracker,
    Icons.Outlined.CloudOff to R.string.about_privacy_offline,
    Icons.Outlined.VisibilityOff to R.string.about_privacy_no_collect,
    Icons.Outlined.Lock to R.string.about_privacy_no_share,
    Icons.Outlined.Storefront to R.string.about_privacy_no_store,
)

private const val WEBSITE = "https://www.files-tech.com"
private const val SOURCE = "https://github.com/gitubpatrice/pass_tech"
private const val CONTACT = "contact@files-tech.com"

private fun view(url: String) = Intent(Intent.ACTION_VIEW, url.toUri())

/**
 * `ACTION_SENDTO` on a `mailto:` address, which only a mail app answers. `ACTION_SEND` would offer
 * every app that takes text — including, on a phone that has one, a messaging app that would carry
 * the report somewhere nobody meant it to go.
 */
private fun report(subject: String, body: String) =
    Intent(Intent.ACTION_SENDTO, "mailto:$CONTACT".toUri())
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
