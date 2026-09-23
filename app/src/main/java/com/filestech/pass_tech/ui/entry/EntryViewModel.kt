package com.filestech.pass_tech.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.biometric.BiometricSupport
import com.filestech.pass_tech.core.integrity.IntegrityIssue
import com.filestech.pass_tech.core.integrity.IntegrityWarning
import com.filestech.pass_tech.core.password.PasswordPolicy
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultManager.BiometricOutcome
import com.filestech.pass_tech.core.vault.VaultManager.BiometricStart
import com.filestech.pass_tech.core.vault.VaultManager.CreateOutcome
import com.filestech.pass_tech.core.vault.VaultManager.UnlockOutcome
import com.filestech.pass_tech.core.vault.VaultRepository.EntryMode
import com.filestech.pass_tech.ui.components.PromptResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
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
    private val biometricSupport: BiometricSupport,
    private val integrity: IntegrityWarning,
) : ViewModel() {

    enum class Problem {
        TOO_SHORT,
        TOO_WEAK,
        MISMATCH,
        WRONG_PASSWORD,
        IMPOSSIBLE,
        KEYSTORE_UNAVAILABLE,
        BIOMETRIC_FAILED,
        BIOMETRIC_INVALIDATED,
        BIOMETRIC_DISARMED,

        /** A wrong heir passphrase, or a vault that still answers: the same words for both. */
        HEIR_REFUSED,
    }

    data class UiState(
        /** `null` until read from the vault files. */
        val mode: EntryMode? = null,
        val busy: Boolean = false,
        val problem: Problem? = null,
        /** Above 0, the lockout is running: the form gives way to a countdown. */
        val lockedForMillis: Long = 0,
        /** A vault was just created: the backup reminder is due (2.7.1 shows it after the creation). */
        val backupReminder: Boolean = false,
        /** The unlock form offers the fingerprint: armed, and the phone can authenticate. */
        val biometric: Boolean = false,
        /**
         * The prompt is due on its own, once per lock (2.7.1, UX 2026-08-03): a cancelled prompt never
         * comes back by itself, the fingerprint button is there for that.
         */
        val biometricAutoPrompt: Boolean = false,
        /** The heir passphrase is being asked for. */
        val heirPrompt: Boolean = false,
        /** Above 0, the heir field is waiting out its own delay, which is not the vault's. */
        val heirLockedForMillis: Long = 0,
        /**
         * What this phone looks like, when it is worth saying. Shown BEFORE the master password is
         * typed: afterwards is too late to be told the phone can be read over your shoulder.
         */
        val integrity: Set<IntegrityIssue> = emptySet(),
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var countdown: Job? = null

    private val promptChannel = Channel<Cipher>(Channel.CONFLATED)

    /** The ciphers the screen hands to the system prompt; the result comes back to [biometricResult]. */
    val prompts: Flow<Cipher> = promptChannel.receiveAsFlow()

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

    /**
     * The fingerprint, from its button or on its own. Busy until the prompt answers: the password form
     * waits, one attempt at a time.
     */
    fun startBiometric() {
        val current = mutableState.value
        mutableState.update { it.copy(biometricAutoPrompt = false) }
        if (current.busy || current.lockedForMillis > 0 || !current.biometric) return
        mutableState.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            when (val start = vault.startBiometricUnlock()) {
                is BiometricStart.Ready -> promptChannel.send(start.cipher)
                BiometricStart.NotArmed -> mutableState.update { it.copy(busy = false, biometric = false) }
                BiometricStart.Invalidated -> mutableState.update {
                    it.copy(busy = false, biometric = false, problem = Problem.BIOMETRIC_INVALIDATED)
                }
                BiometricStart.KeystoreUnavailable -> mutableState.update { it.copy(busy = false, problem = Problem.KEYSTORE_UNAVAILABLE) }
            }
        }
    }

    /** What the system prompt answered. A cancel is silent, as in 2.7.1: the password form is right there. */
    fun biometricResult(result: PromptResult) {
        when (result) {
            is PromptResult.Authenticated -> viewModelScope.launch {
                val problem = when (val outcome = vault.unlockWithBiometrics(result.cipher)) {
                    BiometricOutcome.Opened -> null
                    is BiometricOutcome.Locked -> lockedFor(outcome.remainingMillis)
                    BiometricOutcome.Disarmed -> Problem.BIOMETRIC_DISARMED
                    BiometricOutcome.KeystoreUnavailable -> Problem.KEYSTORE_UNAVAILABLE
                }
                mutableState.update {
                    it.copy(busy = false, problem = problem, biometric = it.biometric && problem != Problem.BIOMETRIC_DISARMED)
                }
            }
            PromptResult.Canceled -> mutableState.update { it.copy(busy = false, problem = null) }
            PromptResult.Failed -> mutableState.update { it.copy(busy = false, problem = Problem.BIOMETRIC_FAILED) }
        }
    }

    /**
     * Heir access, offered on the unlock form of EVERY phone, whether an heir is set up or not
     * (design v2 §8). The offer is what would otherwise be the oracle: "the button is here, therefore
     * someone is waiting for you to die". The refusal says nothing either — a wrong passphrase and a
     * vault whose owner is still answering read exactly the same.
     */
    fun openHeirPrompt() {
        mutableState.update { it.copy(heirPrompt = true, problem = null, heirLockedForMillis = 0) }
    }

    fun cancelHeirPrompt() {
        mutableState.update { it.copy(heirPrompt = false, problem = null, heirLockedForMillis = 0) }
    }

    /** On success the state becomes the heir view and this screen goes with it. */
    fun unlockAsHeir(passphrase: String) {
        if (passphrase.isEmpty() || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, problem = null, heirLockedForMillis = 0) }
        viewModelScope.launch {
            try {
                answer(vault.unlockAsHeir(passphrase.encodeToByteArray()))
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun answer(outcome: VaultManager.HeirUnlockOutcome) {
        when (outcome) {
            // The screen is about to be replaced by the heir view: nothing to say here.
            VaultManager.HeirUnlockOutcome.Opened -> Unit
            VaultManager.HeirUnlockOutcome.Refused -> mutableState.update { it.copy(problem = Problem.HEIR_REFUSED) }
            is VaultManager.HeirUnlockOutcome.Locked -> mutableState.update { it.copy(heirLockedForMillis = outcome.remainingMillis) }
            VaultManager.HeirUnlockOutcome.KeystoreUnavailable -> mutableState.update { it.copy(problem = Problem.KEYSTORE_UNAVAILABLE) }
        }
    }

    fun backupReminderSeen() {
        mutableState.update { it.copy(backupReminder = false) }
    }

    /** Read once: the same phone stays quiet until something about it changes. */
    fun integritySeen() {
        val issues = mutableState.value.integrity
        mutableState.update { it.copy(integrity = emptySet()) }
        integrity.seen(issues)
    }

    private suspend fun refresh() {
        val mode = vault.entryMode()
        val biometric = mode == EntryMode.UNLOCK && biometricSupport.available() && vault.biometricsArmed()
        mutableState.update {
            it.copy(
                mode = mode,
                problem = null,
                biometric = biometric,
                biometricAutoPrompt = biometric,
                // The heir reading that just closed left its dialog open behind it, over the unlock
                // form nobody had asked to hide (seen on the emulator, 2026-09-22).
                heirPrompt = false,
                heirLockedForMillis = 0,
                integrity = runCatching { integrity.due() }.getOrDefault(emptySet()),
            )
        }
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
