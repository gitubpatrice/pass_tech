package com.filestech.pass_tech.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {

    /**
     * `null` until the setting is read. The system splash stays up meanwhile (MainActivity), so a user
     * who already saw the splash never gets a flash of it: unlike an optimistic `true`, which would.
     */
    val shouldShow: StateFlow<Boolean?> = preferences.splashShown
        .map { shown -> !shown }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    fun markShown() {
        viewModelScope.launch { preferences.markSplashShown() }
    }
}
