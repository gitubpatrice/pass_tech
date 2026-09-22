package com.filestech.pass_tech.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.model.EntryQuery
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The home's filter, search and sort. The sort is a setting and outlives the lock; the filter and the
 * search start over at every opening, as the home of 2.7.1 did.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    vault: VaultManager,
    private val preferences: AppPreferences,
) : ViewModel() {

    data class UiState(val query: EntryQuery = EntryQuery(), val searchOpen: Boolean = false)

    private val filter = MutableStateFlow<EntryQuery.Filter>(EntryQuery.Filter.All)
    private val search = MutableStateFlow("")
    private val searchOpen = MutableStateFlow(false)

    val state: StateFlow<UiState> =
        combine(filter, search, searchOpen, preferences.sortMode) { filter, search, open, sort ->
            UiState(EntryQuery(filter, search, sort), open)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            vault.state.collect {
                if (it == VaultManager.State.Locked) {
                    filter.value = EntryQuery.Filter.All
                    closeSearch()
                }
            }
        }
    }

    fun setFilter(value: EntryQuery.Filter) {
        filter.value = value
    }

    fun openSearch() {
        searchOpen.value = true
    }

    /** Closing the search also clears it (2.7.1). */
    fun closeSearch() {
        searchOpen.value = false
        search.value = ""
    }

    fun setSearch(value: String) {
        search.value = value
    }

    fun setSort(value: EntryQuery.Sort) {
        viewModelScope.launch { preferences.setSortMode(value) }
    }
}
