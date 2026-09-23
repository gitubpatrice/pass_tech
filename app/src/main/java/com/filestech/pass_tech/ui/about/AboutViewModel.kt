package com.filestech.pass_tech.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.update.UpdateCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * The About screen's only moving parts: the version this build carries, and the check the owner asks
 * for by pressing a button.
 *
 * That check is [UpdateCheck.check] with `force`, so it never answers "too soon" to someone who has
 * just asked. What it cannot do is answer "you are up to date" when it never got to ask — 2.7.1 did,
 * because its screen turned every unsuccessful check into `null` and `null` into
 * "You already have the latest version ✓", tick included. A phone with no connection was told its
 * version was current.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updates: UpdateCheck,
    @Named(UpdateCheck.INSTALLED_VERSION) val installedVersion: String,
) : ViewModel() {

    sealed interface Check {
        /** Nothing asked yet, or the answer has been read and put away. */
        data object Idle : Check

        data object Running : Check

        data class Done(val outcome: UpdateCheck.Outcome) : Check
    }

    private val mutableCheck = MutableStateFlow<Check>(Check.Idle)

    val check: StateFlow<Check> = mutableCheck.asStateFlow()

    /** Ignored while one is already running: the button is disabled, and a second tap must not queue. */
    fun checkNow() {
        if (mutableCheck.value is Check.Running) return
        mutableCheck.value = Check.Running
        viewModelScope.launch {
            // An unreadable answer is an answer: whatever goes wrong down there, the owner is told
            // that nothing was learned, never that everything is fine.
            val outcome = runCatching { updates.check(force = true) }
                .getOrDefault(UpdateCheck.Outcome.Unreachable)
            mutableCheck.value = Check.Done(outcome)
        }
    }

    /** The answer has been read: the line under the button goes, and the dialog with it. */
    fun clear() {
        if (mutableCheck.value is Check.Done) mutableCheck.value = Check.Idle
    }
}
