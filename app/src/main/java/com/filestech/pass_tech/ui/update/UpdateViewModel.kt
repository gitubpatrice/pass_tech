package com.filestech.pass_tech.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.update.Release
import com.filestech.pass_tech.core.update.UpdateCheck
import com.filestech.pass_tech.core.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Telling the owner that a newer version exists — which is the part 2.7.1 left out (see [UpdateCheck]).
 *
 * The check is made **once the vault is open**, once per run of the app, and what it finds is shown
 * over the vault until the owner puts it away. A locked vault shows nothing: someone holding the
 * phone without the password learns nothing from it, not even that this app is behind.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val vault: VaultManager,
    private val updates: UpdateCheck,
) : ViewModel() {

    private val mutableState = MutableStateFlow<Release?>(null)

    /** The release to announce, or `null`: nothing found, vault not open, or already put away. */
    val available: StateFlow<Release?> = mutableState.asStateFlow()

    private var found: Release? = null
    private var asked = false
    private var dismissed = false

    init {
        viewModelScope.launch {
            vault.state.collect { state ->
                if (state is VaultManager.State.Open && !asked) {
                    asked = true
                    found = runCatching { updates.newerRelease() }.getOrNull()
                }
                publish(state)
            }
        }
    }

    fun dismiss() {
        dismissed = true
        mutableState.value = null
    }

    private fun publish(state: VaultManager.State) {
        mutableState.value = found?.takeIf { !dismissed && state is VaultManager.State.Open }
    }
}
