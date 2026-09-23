package com.filestech.pass_tech.ui.about

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPasteOff
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Diversity1
import androidx.compose.material.icons.outlined.Emergency
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.about.AppFacts
import com.filestech.pass_tech.ui.components.countdownText

/** One line of the list: what it is called, and what it does, in the language of the app. */
internal data class Feature(val icon: ImageVector, val label: String, val description: String)

/** A handful of features under one heading. 2.7.1 showed twenty-five rows with no heading at all. */
internal data class FeatureGroup(@StringRes val title: Int, val features: List<Feature>)

/** One card of the quick help: a title, and its steps in order. */
internal data class HelpCard(@StringRes val title: Int, val steps: List<String>)

/**
 * Everything the About screen claims the app does, in five groups.
 *
 * **Every figure in here comes from [AppFacts]**, which reads it from the code that enforces it: the
 * number of free attempts is `BruteForceGuard`'s own, the delays go through the same formatter as
 * every other delay the app shows, and the size of the word list is the size of the list. A sentence
 * on this screen cannot say something the app has stopped doing — which is the one failure this
 * screen has a history of.
 */
@Composable
internal fun aboutGroups(): List<FeatureGroup> = listOf(
    FeatureGroup(R.string.about_group_vault, vaultFeatures()),
    FeatureGroup(R.string.about_group_forced, forcedFeatures()),
    FeatureGroup(R.string.about_group_content, contentFeatures()),
    FeatureGroup(R.string.about_group_watch, watchFeatures()),
    FeatureGroup(R.string.about_group_data, dataFeatures()),
)

@Composable
private fun vaultFeatures(): List<Feature> = listOf(
    Feature(
        icon = Icons.Outlined.Key,
        label = stringResource(R.string.about_feature_vault),
        description = stringResource(R.string.about_feature_vault_desc, AppFacts.argonMemoryMiB, AppFacts.argonIterations),
    ),
    Feature(
        icon = Icons.Outlined.Fingerprint,
        label = stringResource(R.string.about_feature_biometric),
        description = stringResource(R.string.about_feature_biometric_desc),
    ),
    Feature(
        icon = Icons.Outlined.Timer,
        label = stringResource(R.string.about_feature_bruteforce),
        description = stringResource(
            R.string.about_feature_bruteforce_desc,
            pluralStringResource(R.plurals.about_attempts, AppFacts.freeAttempts, AppFacts.freeAttempts),
            countdownText(AppFacts.firstLockMillis),
            countdownText(AppFacts.lastLockMillis),
        ),
    ),
    Feature(
        icon = Icons.Outlined.Timer,
        label = stringResource(R.string.about_feature_autolock),
        description = stringResource(R.string.about_feature_autolock_desc, countdownText(AppFacts.autoLockMaxMillis)),
    ),
    Feature(
        icon = Icons.Outlined.NoPhotography,
        label = stringResource(R.string.about_feature_screenshot),
        description = stringResource(R.string.about_feature_screenshot_desc),
    ),
    Feature(
        icon = Icons.Outlined.CloudOff,
        label = stringResource(R.string.about_feature_backup_off),
        description = stringResource(R.string.about_feature_backup_off_desc),
    ),
    Feature(
        icon = Icons.Outlined.PhoneAndroid,
        label = stringResource(R.string.about_feature_integrity),
        description = stringResource(R.string.about_feature_integrity_desc),
    ),
)

@Composable
private fun forcedFeatures(): List<Feature> = listOf(
    Feature(
        icon = Icons.Outlined.ShieldMoon,
        label = stringResource(R.string.about_feature_decoy),
        description = stringResource(R.string.about_feature_decoy_desc),
    ),
    Feature(
        icon = Icons.Outlined.Emergency,
        label = stringResource(R.string.about_feature_panic),
        description = stringResource(R.string.about_feature_panic_desc),
    ),
    Feature(
        icon = Icons.Outlined.Diversity1,
        label = stringResource(R.string.about_feature_heir),
        description = stringResource(R.string.about_feature_heir_desc),
    ),
)

@Composable
private fun contentFeatures(): List<Feature> = listOf(
    Feature(
        icon = Icons.Outlined.VpnKey,
        label = pluralStringResource(R.plurals.about_entry_types, AppFacts.entryTypes, AppFacts.entryTypes),
        description = stringResource(R.string.about_feature_types_desc),
    ),
    Feature(
        icon = Icons.Outlined.VerifiedUser,
        label = stringResource(R.string.about_feature_totp),
        description = stringResource(R.string.about_feature_totp_desc),
    ),
    Feature(
        icon = Icons.Outlined.Link,
        label = stringResource(R.string.about_feature_otpauth),
        description = stringResource(R.string.about_feature_otpauth_desc),
    ),
    Feature(
        icon = Icons.Outlined.CreditCard,
        label = stringResource(R.string.about_feature_cards),
        description = stringResource(R.string.about_feature_cards_desc),
    ),
    Feature(
        icon = Icons.AutoMirrored.Outlined.StickyNote2,
        label = stringResource(R.string.about_feature_notes),
        description = stringResource(R.string.about_feature_notes_desc),
    ),
    Feature(
        icon = Icons.AutoMirrored.Outlined.Sort,
        label = stringResource(R.string.about_feature_search),
        description = stringResource(R.string.about_feature_search_desc),
    ),
    Feature(
        icon = Icons.Outlined.Password,
        label = stringResource(R.string.about_feature_generator),
        description = stringResource(
            R.string.about_feature_generator_desc,
            AppFacts.generatorMinLength,
            AppFacts.generatorMaxLength,
            pluralStringResource(R.plurals.about_words, AppFacts.dicewareWords, AppFacts.dicewareWords),
        ),
    ),
)

@Composable
private fun watchFeatures(): List<Feature> = listOf(
    Feature(
        icon = Icons.Outlined.Policy,
        label = stringResource(R.string.about_feature_audit),
        description = stringResource(R.string.about_feature_audit_desc),
    ),
    Feature(
        icon = Icons.Outlined.TravelExplore,
        label = stringResource(R.string.about_feature_breach),
        description = stringResource(R.string.about_feature_breach_desc),
    ),
    Feature(
        icon = Icons.Outlined.VerifiedUser,
        label = stringResource(R.string.about_feature_phishing),
        description = stringResource(R.string.about_feature_phishing_desc),
    ),
    Feature(
        icon = Icons.Outlined.ContentPasteOff,
        label = stringResource(R.string.about_feature_clipboard),
        description = stringResource(
            R.string.about_feature_clipboard_desc,
            countdownText(AppFacts.clipboardMinMillis),
            countdownText(AppFacts.clipboardMaxMillis),
        ),
    ),
    Feature(
        icon = Icons.Outlined.SystemUpdateAlt,
        label = stringResource(R.string.about_feature_update),
        description = stringResource(R.string.about_feature_update_desc),
    ),
)

@Composable
private fun dataFeatures(): List<Feature> = listOf(
    Feature(
        icon = Icons.Outlined.Backup,
        label = stringResource(R.string.about_feature_backup),
        description = stringResource(R.string.about_feature_backup_desc),
    ),
    Feature(
        icon = Icons.Outlined.FileOpen,
        label = stringResource(R.string.about_feature_import),
        description = stringResource(R.string.about_feature_import_desc),
    ),
    Feature(
        icon = Icons.Outlined.BrightnessMedium,
        label = stringResource(R.string.about_feature_theme),
        description = stringResource(R.string.about_feature_theme_desc),
    ),
)

/** The three things someone asks first. The update card says where the check really happens. */
@Composable
internal fun aboutHelp(): List<HelpCard> = listOf(
    HelpCard(
        title = R.string.about_help_first_title,
        steps = listOf(stringResource(R.string.about_help_first_1), stringResource(R.string.about_help_first_2)),
    ),
    HelpCard(
        title = R.string.about_help_add_title,
        steps = listOf(stringResource(R.string.about_help_add_1), stringResource(R.string.about_help_add_2)),
    ),
    HelpCard(
        title = R.string.about_help_update_title,
        steps = listOf(
            stringResource(R.string.about_help_update_1, AppFacts.updateChecksPerDay),
            stringResource(R.string.about_help_update_2),
        ),
    ),
)
