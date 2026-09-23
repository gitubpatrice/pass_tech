package com.filestech.pass_tech.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryQuery
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.ui.components.PtCard
import com.filestech.pass_tech.ui.components.PtSnackbarHost
import com.filestech.pass_tech.ui.entries.DeleteDialog
import com.filestech.pass_tech.ui.entries.EntryLook
import com.filestech.pass_tech.ui.theme.DestructiveRed
import com.filestech.pass_tech.ui.theme.FavoriteAmber
import com.filestech.pass_tech.ui.theme.FavoriteAmberDark

/**
 * The vault's home (2.7.1, `home_screen.dart`): the title bar with the lock button, the entries, most
 * recently modified first, and the Add button with its choice of type. A swipe to the right stars an
 * entry, a swipe to the left deletes it after asking. Search, filters and sort come next.
 */
@Composable
fun HomeScreen(
    entries: List<Entry>,
    home: HomeViewModel,
    snackbar: SnackbarHostState,
    onGenerator: () -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOpen: (Entry) -> Unit,
    onAdd: (EntryType) -> Unit,
    onToggleFavorite: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    var choosingType by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    val ui by home.state.collectAsStateWithLifecycle()
    val shown = remember(entries, ui.query) { ui.query.apply(entries) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { PtSnackbarHost(snackbar) },
        topBar = { HomeTopBar(ui, home, onGenerator, onLock, onSettings, onAbout) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { choosingType = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FilterRow(ui.query.filter, home::setFilter)
            Text(
                text = if (shown.isEmpty()) {
                    stringResource(R.string.home_entry_count_none)
                } else {
                    pluralStringResource(R.plurals.home_entry_count, shown.size, shown.size)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
            )
            if (shown.isEmpty()) {
                EmptyState(
                    searching = ui.query.search.isNotEmpty(),
                    // 2.7.1: the hint and the Add button only on the unfiltered list.
                    offerAdd = ui.query.search.isEmpty() && ui.query.filter == EntryQuery.Filter.All,
                    onAdd = { onAdd(EntryType.PASSWORD) },
                )
            } else {
                EntryList(shown, onOpen, onToggleFavorite, onDelete = { deleting = it })
            }
        }
    }

    if (choosingType) {
        AddSheet(
            onDismiss = { choosingType = false },
            onChoose = {
                choosingType = false
                onAdd(it)
            },
        )
    }
    deleting?.let { entry ->
        DeleteDialog(
            title = entry.title,
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                onDelete(entry)
            },
        )
    }
}

@Composable
private fun EntryList(entries: List<Entry>, onOpen: (Entry) -> Unit, onToggleFavorite: (Entry) -> Unit, onDelete: (Entry) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 80.dp)) {
        items(entries, key = { it.id }) { entry ->
            SwipeableEntry(
                entry = entry,
                onOpen = { onOpen(entry) },
                onToggleFavorite = { onToggleFavorite(entry) },
                onDelete = { onDelete(entry) },
            )
        }
    }
}

/** The swipe only triggers the action: the card always springs back into place. */
@Composable
private fun SwipeableEntry(entry: Entry, onOpen: () -> Unit, onToggleFavorite: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleFavorite()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(bottom = 6.dp),
        backgroundContent = {
            when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeAction(
                    color = FavoriteAmberDark,
                    icon = if (entry.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    label = stringResource(if (entry.isFavorite) R.string.action_remove else R.string.action_favorite),
                    alignment = Alignment.CenterStart,
                )
                SwipeToDismissBoxValue.EndToStart -> SwipeAction(
                    color = DestructiveRed,
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.action_delete),
                    alignment = Alignment.CenterEnd,
                )
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        EntryCard(entry, onOpen)
    }
}

@Composable
private fun SwipeAction(color: Color, icon: ImageVector, label: String, alignment: Alignment) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(label, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, onOpen: () -> Unit) {
    val color = EntryLook.color(entry)
    val categoryColor = EntryLook.categoryColor(entry.category)
    val subtitle = EntryLook.subtitle(entry)
    PtCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EntryLook.icon(entry), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.type == EntryType.PASSWORD && entry.totpSecret.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            if (entry.isFavorite) Icon(Icons.Filled.Star, contentDescription = null, tint = FavoriteAmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = EntryLook.categoryLabel(entry.category)?.let { stringResource(it) } ?: entry.category,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = categoryColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(categoryColor.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onChoose: (EntryType) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
            Text(
                stringResource(R.string.home_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            )
            AddChoice(Icons.Filled.Key, EntryLook.PasswordChoiceColor, R.string.home_add_password, R.string.home_add_password_sub) {
                onChoose(EntryType.PASSWORD)
            }
            AddChoice(Icons.AutoMirrored.Outlined.StickyNote2, EntryLook.NoteColor, R.string.home_add_note, R.string.home_add_note_sub) {
                onChoose(EntryType.NOTE)
            }
            AddChoice(Icons.Filled.CreditCard, EntryLook.CardColor, R.string.home_add_card, R.string.home_add_card_sub) {
                onChoose(EntryType.CARD)
            }
        }
    }
}

@Composable
private fun AddChoice(icon: ImageVector, color: Color, title: Int, subtitle: Int, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color) }
        },
        headlineContent = { Text(stringResource(title), fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(stringResource(subtitle), fontSize = 12.sp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyState(searching: Boolean, offerAdd: Boolean, onAdd: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (searching) R.string.home_empty_no_result else R.string.home_empty_no_entry),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (offerAdd) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.home_empty_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                // 2.7.1 (U12 v2.4.3): an Add button right there, not only the one at the bottom.
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add))
                }
            }
        }
    }
}
