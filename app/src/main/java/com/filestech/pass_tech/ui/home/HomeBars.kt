package com.filestech.pass_tech.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.model.EntryQuery.Filter
import com.filestech.pass_tech.core.model.EntryQuery.Sort
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.ui.entries.EntryLook

/**
 * The home's title bar (2.7.1): the logo and the name, or the search field; then search, generator,
 * sort and lock.
 */
@Composable
internal fun HomeTopBar(ui: HomeViewModel.UiState, home: HomeViewModel, onGenerator: () -> Unit, onLock: () -> Unit) {
    TopAppBar(
        title = { if (ui.searchOpen) SearchField(ui.query.search, home::setSearch) else Brand() },
        actions = {
            IconButton(onClick = { if (ui.searchOpen) home.closeSearch() else home.openSearch() }) {
                Icon(
                    if (ui.searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(if (ui.searchOpen) R.string.home_search_close else R.string.home_search_open),
                )
            }
            IconButton(onClick = onGenerator) {
                Icon(Icons.Filled.Password, contentDescription = stringResource(R.string.home_tooltip_generator))
            }
            SortMenu(ui.query.sort, home::setSort)
            IconButton(onClick = onLock) {
                Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.home_tooltip_lock))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.pass_tech_logo_damier),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(10.dp))
        // The actions leave little room on a narrow phone: the name gives way, with an ellipsis
        // (2.7.1 overflowed by 5 px on a Galaxy S9, measured 2026-09-21).
        Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens with the keyboard; the cross clears it (2.7.1, U14 v2.4.3). */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        trailingIcon = if (value.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.home_search_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SortMenu(current: Sort, onSelect: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.home_tooltip_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabel(sort))) },
                    leadingIcon = { Icon(sortIcon(sort), contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (sort == current) ({ Icon(Icons.Filled.Check, null, Modifier.size(14.dp)) }) else null,
                    onClick = {
                        open = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}

/**
 * One row of chips, three families: the scope (All, Favorites), the entry TYPE, the CATEGORY. A thin
 * line between families: "Bank cards" (a type) and "Cards" (a category) read as two unrelated
 * filters (2.7.1, UI 2026-08-04).
 */
@Composable
internal fun FilterRow(selected: Filter, onSelect: (Filter) -> Unit) {
    LazyRow(
        modifier = Modifier.height(46.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(Filter.CHIPS) { index, filter ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0 && Filter.CHIPS[index - 1]::class != filter::class && familyStarts(filter)) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(22.dp)
                            .padding(end = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                val isSelected = filter == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(filter) },
                    label = { Text(filterLabel(filter), fontSize = 12.sp) },
                    leadingIcon = (filter as? Filter.OfType)?.let { type ->
                        {
                            Icon(
                                typeIcon(type.type),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The types start after Favorites, the categories after the last type. */
private fun familyStarts(filter: Filter) = filter is Filter.OfType || filter is Filter.InCategory

@Composable
private fun filterLabel(filter: Filter): String = when (filter) {
    Filter.All -> stringResource(R.string.home_filter_all)
    Filter.Favorites -> stringResource(R.string.home_filter_favorites)
    is Filter.OfType -> stringResource(
        when (filter.type) {
            EntryType.PASSWORD -> R.string.home_filter_passwords
            EntryType.NOTE -> R.string.home_filter_notes
            EntryType.CARD -> R.string.home_filter_cards
        },
    )
    is Filter.InCategory -> EntryLook.categoryLabel(filter.category)?.let { stringResource(it) } ?: filter.category
}

private fun typeIcon(type: EntryType): ImageVector = when (type) {
    EntryType.PASSWORD -> Icons.Filled.Key
    EntryType.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
    EntryType.CARD -> Icons.Filled.CreditCard
}

private fun sortLabel(sort: Sort): Int = when (sort) {
    Sort.RECENT -> R.string.home_sort_recent
    Sort.OLDEST -> R.string.home_sort_oldest
    Sort.ALPHA -> R.string.home_sort_alpha
    Sort.ALPHA_DESC -> R.string.home_sort_alpha_desc
}

private fun sortIcon(sort: Sort): ImageVector = when (sort) {
    Sort.RECENT -> Icons.Filled.Schedule
    Sort.OLDEST -> Icons.Filled.History
    Sort.ALPHA, Sort.ALPHA_DESC -> Icons.Filled.SortByAlpha
}
