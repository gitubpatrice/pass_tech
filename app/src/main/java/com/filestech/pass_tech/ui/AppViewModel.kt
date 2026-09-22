package com.filestech.pass_tech.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which screen the app shows follows the vault, and nothing else: locked, the entry form; open, the home. */
@HiltViewModel
class AppViewModel @Inject constructor(private val vault: VaultManager) : ViewModel() {

    val vaultState: StateFlow<VaultManager.State> = vault.state

    fun lock() {
        viewModelScope.launch { vault.lock() }
    }
}
