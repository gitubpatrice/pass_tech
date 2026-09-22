package com.filestech.pass_tech.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.password.PasswordPolicy
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultManager.CreateOutcome
import com.filestech.pass_tech.core.vault.VaultManager.UnlockOutcome
import com.filestech.pass_tech.core.vault.VaultRepository.EntryMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The entry screen: the creation form or the unlock form, and nothing that tells which vaults exist
 * (design v2 §5, oracle E). Every time the vault locks, the form is read again: after a deletion it
 * becomes the creation form, whichever vault was deleted.
 */
@HiltViewModel
class EntryViewModel @Inject constructor(
    private val vault: VaultManager,
    private val clock: Clock,
) : ViewModel() {

    enum class Problem { TOO_SHORT, TOO_WEAK, MISMATCH, WRONG_PASSWORD, IMPOSSIBLE, KEYSTORE_UNAVAILABLE }

    data class UiState(
        /** `null` until read from the vault files. */
        val mode: EntryMode? = null,
        val busy: Boolean = false,
        val problem: Problem? = null,
        /** Above 0, the lockout is running: the form gives way to a countdown. */
        val lockedForMillis: Long = 0,
        /** A vault was just created: the backup reminder is due (2.7.1 shows it after the creation). */
        val backupReminder: Boolean = false,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            // A view model created while the vault is open (the activity recreated in the background)
            // would otherwise wait for the next lock to read the form, and the system splash waits for
            // it (2026-09-22). A locked vault is read by the collection below.
            if (vault.state.value != VaultManager.State.Locked) refresh()
            vault.state.filter { it == VaultManager.State.Locked }.collect { refresh() }
        }
    }

    /**
     * The creation form. The password rule is checked first, then the confirmation. A password that
     * opens an existing vault opens it (design v2 §5): that is how an owner gets the real vault back.
     */
    fun create(password: String, confirmation: String) {
        val problem = when (PasswordPolicy.check(password)) {
            PasswordPolicy.Rejection.TOO_SHORT -> Problem.TOO_SHORT
            PasswordPolicy.Rejection.TOO_WEAK -> Problem.TOO_WEAK
            null -> if (password != confirmation) Problem.MISMATCH else null
        }
        if (problem != null) {
            mutableState.update { it.copy(problem = problem) }
        } else {
            submit {
                when (val outcome = vault.openOrCreate(password.encodeToByteArray())) {
                    CreateOutcome.Created -> {
                        mutableState.update { it.copy(backupReminder = true) }
                        null
                    }
                    CreateOutcome.Opened -> null
                    CreateOutcome.Impossible -> Problem.IMPOSSIBLE
                    is CreateOutcome.Locked -> lockedFor(outcome.remainingMillis)
                    CreateOutcome.KeystoreUnavailable -> Problem.KEYSTORE_UNAVAILABLE
                }
            }
        }
    }

    fun unlock(password: String) {
        if (password.isEmpty()) return
        submit {
            when (val outcome = vault.unlock(password.encodeToByteArray())) {
                UnlockOutcome.Opened -> null
                UnlockOutcome.WrongPassword -> Problem.WRONG_PASSWORD
                is UnlockOutcome.Locked -> lockedFor(outcome.remainingMillis)
                UnlockOutcome.KeystoreUnavailable -> Problem.KEYSTORE_UNAVAILABLE
            }
        }
    }

    fun backupReminderSeen() {
        mutableState.update { it.copy(backupReminder = false) }
    }

    private suspend fun refresh() {
        mutableState.update { it.copy(mode = vault.entryMode(), problem = null) }
        lockedFor(vault.lockoutRemainingMillis())
    }

    /**
     * One attempt at a time: a second tap while the first derives is ignored. After a failure the
     * lockout is read again, as 2.7.1 does: the failure that starts a lockout shows the countdown at
     * once, not only at the next attempt.
     */
    private fun submit(attempt: suspend () -> Problem?) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            try {
                val problem = attempt()
                if (problem != null) lockedFor(vault.lockoutRemainingMillis())
                mutableState.update { it.copy(problem = problem) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Starts the countdown, on uptime like the lockout itself. Always `null`: a lockout is not a problem to show. */
    private fun lockedFor(millis: Long): Problem? {
        countdown?.cancel()
        mutableState.update { it.copy(lockedForMillis = millis.coerceAtLeast(0)) }
        if (millis > 0) {
            val end = clock.elapsedMillis() + millis
            countdown = viewModelScope.launch {
                var left = millis
                while (left > 0) {
                    delay(left % TICK_MILLIS + if (left % TICK_MILLIS == 0L) TICK_MILLIS else 0)
                    left = (end - clock.elapsedMillis()).coerceAtLeast(0)
                    mutableState.update { it.copy(lockedForMillis = left) }
                }
            }
        }
        return null
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
