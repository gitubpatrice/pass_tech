package com.filestech.pass_tech.ui.entries

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.totp.Totp
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.theme.DestructiveRed
import com.filestech.pass_tech.ui.theme.FavoriteAmber
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

/** Copies a value; the label names it in the "copied" message. */
typealias CopyAction = (value: String, label: Int) -> Unit

/**
 * An entry's detail (2.7.1, `entry_detail_screen.dart`): favourite, edit and delete in the title bar,
 * the fields of its type with their copy buttons, the secrets masked until asked, the dates at the end.
 */
@Composable
fun EntryDetailScreen(
    entry: Entry,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (value: String, label: Int, site: String?) -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val copy: CopyAction = { value, label ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onCopy(value, label, null)
    }

    /**
     * The two values an impostor site is after. They are the only ones checked against the browser,
     * as in 2.7.1: a card number has no site to be compared with, and a note is not typed into a
     * login form. The entry's own URL is what the check compares against, so an entry that names no
     * site copies like any other.
     */
    val copyForSite: CopyAction = { value, label ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onCopy(value, label, entry.url)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (entry.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.entry_detail_favorite_tooltip),
                            tint = if (entry.isFavorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.entry_detail_edit_tooltip))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.entry_detail_delete_tooltip),
                            tint = DestructiveRed,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(entry)
            Spacer(Modifier.height(14.dp))
            when (entry.type) {
                EntryType.PASSWORD -> PasswordView(entry, copy, copyForSite)
                EntryType.NOTE -> NoteView(entry, copy)
                EntryType.CARD -> CardView(entry, copy)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Dates(entry)
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            title = entry.title,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            },
        )
    }
}

@Composable
private fun Header(entry: Entry) {
    val color = EntryLook.categoryColor(entry.category)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EntryLook.icon(entry), contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(stringResource(EntryLook.typeLabel(entry.type)), color)
            Badge(
                EntryLook.categoryLabel(entry.category)?.let { stringResource(it) } ?: entry.category,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun PasswordView(entry: Entry, copy: CopyAction, copyForSite: CopyAction) {
    val usernameLabel = R.string.entry_detail_field_username
    Field(
        label = stringResource(usernameLabel),
        value = entry.username.ifEmpty { "—" },
        onCopy = if (entry.username.isEmpty()) null else ({ copy(entry.username, usernameLabel) }),
    )
    MaskableField(
        label = stringResource(R.string.entry_detail_field_password),
        shown = entry.password,
        // The real length between 8 and 24, never the exact one (2.7.1, QW11 v2.4.0).
        masked = "•".repeat(entry.password.length.coerceIn(MASK_MIN, MASK_MAX)),
        maskedSpacing = 2,
        onCopy = { copyForSite(entry.password, R.string.entry_detail_field_password) },
        monospace = true,
    )
    if (entry.totpSecret.isNotEmpty()) {
        TotpCard(secret = entry.totpSecret, onCopy = { copyForSite(it, R.string.entry_detail_field_2fa_code) })
    }
    if (entry.url.isNotEmpty()) {
        Field(stringResource(R.string.entry_detail_field_url), entry.url, onCopy = { copy(entry.url, R.string.entry_detail_field_url) })
    }
    if (entry.notes.isNotEmpty()) Field(stringResource(R.string.entry_detail_field_notes), entry.notes)
}

@Composable
private fun NoteView(entry: Entry, copy: CopyAction) {
    PtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.entry_detail_field_content), Modifier.weight(1f))
                IconButton(
                    onClick = { copy(entry.notes, R.string.entry_detail_field_content) },
                    enabled = entry.notes.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.entry_detail_copy_notes_tooltip), Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = entry.notes.ifEmpty { stringResource(R.string.entry_detail_note_empty) },
                    fontSize = 14.sp,
                    lineHeight = 19.6.sp,
                )
            }
        }
    }
}

@Composable
private fun CardView(entry: Entry, copy: CopyAction) {
    var numberShown by remember { mutableStateOf(false) }
    CardVisual(entry, numberShown)
    Spacer(Modifier.height(2.dp))
    if (entry.cardholderName.isNotEmpty()) {
        Field(
            stringResource(R.string.entry_detail_field_holder),
            entry.cardholderName,
            onCopy = { copy(entry.cardholderName, R.string.entry_detail_field_holder) },
        )
    }
    MaskableField(
        label = stringResource(R.string.entry_detail_field_number),
        shown = EntryLook.groupCardNumber(entry.cardNumber),
        masked = EntryLook.maskCardNumber(entry.cardNumber),
        onCopy = { copy(entry.cardNumber, R.string.entry_detail_field_card_number_copy) },
        monospace = true,
        visible = numberShown,
        onToggle = { numberShown = !numberShown },
    )
    if (entry.cardExpiry.isNotEmpty() || entry.cardCvv.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entry.cardExpiry.isNotEmpty()) {
                Box(Modifier.weight(1f)) {
                    Field(
                        stringResource(R.string.entry_detail_field_expiry),
                        entry.cardExpiry,
                        onCopy = { copy(entry.cardExpiry, R.string.entry_detail_field_expiry) },
                    )
                }
            }
            if (entry.cardCvv.isNotEmpty()) {
                Box(Modifier.weight(1f)) {
                    MaskableField(
                        label = stringResource(R.string.entry_detail_field_cvv),
                        shown = entry.cardCvv,
                        masked = "•".repeat(entry.cardCvv.length),
                        onCopy = { copy(entry.cardCvv, R.string.entry_detail_field_cvv) },
                    )
                }
            }
        }
    }
    if (entry.cardPin.isNotEmpty()) {
        MaskableField(
            label = stringResource(R.string.entry_detail_field_pin),
            shown = entry.cardPin,
            masked = "•".repeat(entry.cardPin.length),
            onCopy = { copy(entry.cardPin, R.string.entry_detail_field_pin_copy) },
        )
    }
    if (entry.cardIssuer.isNotEmpty()) Field(stringResource(R.string.entry_detail_field_issuer), entry.cardIssuer)
    if (entry.notes.isNotEmpty()) Field(stringResource(R.string.entry_detail_field_notes), entry.notes)
}

@Composable
private fun Field(label: String, value: String, onCopy: (() -> Unit)? = null) {
    PtCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FieldLabel(label)
                Spacer(Modifier.height(3.dp))
                SelectionContainer { Text(value, fontSize = 14.sp) }
            }
            if (onCopy != null) CopyButton(onCopy)
        }
    }
}

/**
 * A secret field: masked until the eye is tapped. [visible] and [onToggle] let the caller own the
 * state, when something else shows the same value (the card number on the card visual).
 */
@Composable
private fun MaskableField(
    label: String,
    shown: String,
    masked: String,
    onCopy: () -> Unit,
    monospace: Boolean = false,
    maskedSpacing: Int = 0,
    visible: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    var ownVisible by remember { mutableStateOf(false) }
    val isVisible = visible ?: ownVisible
    PtCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FieldLabel(label)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (isVisible) shown else masked,
                    fontSize = 14.sp,
                    fontFamily = if (monospace && isVisible) FontFamily.Monospace else null,
                    letterSpacing = if (!isVisible && maskedSpacing > 0) maskedSpacing.sp else 0.5.sp,
                )
            }
            IconButton(onClick = onToggle ?: { ownVisible = !ownVisible }) {
                Icon(
                    if (isVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(R.string.entry_detail_toggle_visibility),
                    modifier = Modifier.size(18.dp),
                )
            }
            CopyButton(onCopy)
        }
    }
}

/**
 * The current 2FA code, masked by default (2.7.1, F7 v2.4.4), with a 30-second ring that turns red in
 * the last 5 seconds. Computed again only when the step changes.
 */
@Composable
private fun TotpCard(secret: String, onCopy: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / MILLIS_PER_SECOND) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MILLIS_PER_SECOND - System.currentTimeMillis() % MILLIS_PER_SECOND)
            now = System.currentTimeMillis() / MILLIS_PER_SECOND
        }
    }
    val step = now / Totp.PERIOD_SECONDS
    val code = remember(secret, step) { Totp.code(secret, now) }
    val remaining = Totp.secondsRemaining(now)
    var visible by remember { mutableStateOf(false) }
    val ring = if (remaining <= URGENT_SECONDS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    PtCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { remaining / Totp.PERIOD_SECONDS.toFloat() },
                    modifier = Modifier.size(42.dp),
                    color = ring,
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                )
                Text("$remaining", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ring)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.entry_detail_field_2fa_totp))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (visible) code else "••• •••",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color = if (visible) ring else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(R.string.entry_detail_toggle_visibility),
                    modifier = Modifier.size(18.dp),
                )
            }
            CopyButton { onCopy(code.replace(" ", "")) }
        }
    }
}

/** The card drawn as a bank card: a flat blue-to-purple gradient, kept in both themes (2.7.1). */
@Composable
private fun CardVisual(entry: Entry, numberShown: Boolean) {
    val clean = entry.cardNumber.replace(" ", "")
    val number = when {
        numberShown && clean.isNotEmpty() -> EntryLook.groupCardNumber(clean)
        clean.length > CARD_LAST_DIGITS -> "•••• •••• •••• " + clean.takeLast(CARD_LAST_DIGITS)
        else -> "••••"
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .shadow(12.dp, shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1F6FEB), Color(0xFF7B1FA2))), shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.cardIssuer.ifEmpty { stringResource(R.string.entry_detail_card_label) }.uppercase(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
        }
        Text(
            text = number,
            color = Color.White,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            CardCaption(stringResource(R.string.entry_detail_card_holder_label), entry.cardholderName.ifEmpty { "—" }.uppercase())
            CardCaption(stringResource(R.string.entry_detail_card_expiry_label), entry.cardExpiry.ifEmpty { "—" })
        }
    }
}

@Composable
private fun CardCaption(caption: String, value: String) {
    Column {
        // 85 % white: the 60 % of the first versions failed the 4.5:1 contrast on the gradient (2.7.1, P0 v2.4.0).
        Text(caption, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun Dates(entry: Entry) {
    val locale = LocalConfiguration.current.locales[0]
    // 2.7.1: `DateFormat.yMd(locale).add_Hm()`, the date in the reader's order and a 24-hour time.
    val format = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMd") + " HH:mm", locale) }
    val style = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(stringResource(R.string.entry_detail_created_at, format.format(entry.createdAt.fields)), fontSize = 11.sp, color = style)
        Text(stringResource(R.string.entry_detail_updated_at, format.format(entry.updatedAt.fields)), fontSize = 11.sp, color = style)
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CopyButton(onCopy: () -> Unit) {
    IconButton(onClick = onCopy) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.entry_detail_copy_tooltip),
            modifier = Modifier.size(18.dp),
        )
    }
}

private const val MASK_MIN = 8
private const val MASK_MAX = 24
private const val URGENT_SECONDS = 5
private const val CARD_LAST_DIGITS = 4
private const val MILLIS_PER_SECOND = 1_000L
