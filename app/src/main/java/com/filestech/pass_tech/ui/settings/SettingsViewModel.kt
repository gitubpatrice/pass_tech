package com.filestech.pass_tech.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.biometric.BiometricSupport
import com.filestech.pass_tech.core.password.PasswordPolicy
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultManager.ArmOutcome
import com.filestech.pass_tech.core.vault.VaultManager.ArmStart
import com.filestech.pass_tech.core.vault.VaultManager.ChangeOutcome
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import com.filestech.pass_tech.core.vault.VaultRepository.CheckResult
import com.filestech.pass_tech.ui.components.PromptResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * The settings screen (2.7.1, `settings_screen.dart`), its first part: appearance, clipboard, security
 * (biometric unlock included) and the danger zone. Passwords typed into its dialogs are wiped by the
 * vault once used.
 */
// One function per action of the screen: they share its busy state and its messages.
@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vault: VaultManager,
    private val preferences: AppPreferences,
    private val biometricSupport: BiometricSupport,
) : ViewModel() {

    data class UiState(
        val theme: AppPreferences.Theme = AppPreferences.Theme.SYSTEM,
        val autoLockSeconds: Int = AppPreferences.AUTO_LOCK_DEFAULT,
        val clipboardSeconds: Int = AppPreferences.CLIPBOARD_DEFAULT,
        val screenshotProtection: Boolean = true,
        /** A vault operation runs: the screen waits, as 2.7.1's spinner did. */
        val busy: Boolean = false,
    )

    data class BiometricUi(
        /** The phone can authenticate with a strong biometric right now. */
        val available: Boolean = false,
        val status: BiometricStatus = BiometricStatus.OFF,
    )

    /** What the change dialog refuses before anything reaches the vault (2.7.1's order). */
    enum class ChangeProblem { CURRENT_REQUIRED, TOO_SHORT, TOO_WEAK, MISMATCH }

    sealed interface Message {
        data object PasswordChanged : Message

        /** Changed, and the fingerprint that opened this vault no longer does (2.7.1 says so). */
        data object PasswordChangedBiometricsReset : Message

        data object BiometricsEnabled : Message

        data object BiometricsDisabled : Message

        data object BiometricsCanceled : Message

        data object BiometricsFailed : Message

        /** This vault has a decoy, or cannot rule one out: the same words from every vault (2.7.1). */
        data object BiometricsRefused : Message

        data object WrongPassword : Message

        /** The new password opens an existing vault, the current one included: never said which. */
        data object PasswordRefused : Message

        data class Locked(val remainingMillis: Long) : Message

        data object KeystoreUnavailable : Message
    }

    private val busy = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        preferences.theme,
        preferences.autoLockSeconds,
        preferences.clipboardClearSeconds,
        preferences.screenshotProtection,
        busy,
    ) { theme, autoLock, clipboard, screenshots, busy -> UiState(theme, autoLock, clipboard, screenshots, busy) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val messageChannel = Channel<Message>(Channel.BUFFERED)
    val messages: Flow<Message> = messageChannel.receiveAsFlow()

    private val biometricAvailable = MutableStateFlow(false)

    val biometrics: StateFlow<BiometricUi> = combine(biometricAvailable, vault.state) { available, vaultState ->
        BiometricUi(available, (vaultState as? VaultManager.State.Open)?.biometrics ?: BiometricStatus.OFF)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BiometricUi())

    private val promptChannel = Channel<Cipher>(Channel.CONFLATED)

    /** The ciphers the screen hands to the system prompt; the result comes back to [armResult]. */
    val prompts: Flow<Cipher> = promptChannel.receiveAsFlow()

    /** Read each time the screen shows: a fingerprint may have been enrolled or removed in between. */
    fun refreshBiometricSupport() {
        biometricAvailable.value = biometricSupport.available()
    }

    /** Disarms whatever was armed, then the prompt: arming seals this vault's key once it is authenticated. */
    fun enableBiometrics() = operate {
        when (val start = vault.startArmingBiometrics()) {
            is ArmStart.Ready -> {
                promptChannel.send(start.cipher)
                null
            }
            ArmStart.Refused -> Message.BiometricsRefused
            ArmStart.KeystoreUnavailable -> Message.KeystoreUnavailable
            null -> null
        }
    }

    fun armResult(result: PromptResult) {
        when (result) {
            is PromptResult.Authenticated -> operate {
                when (vault.armBiometrics(result.cipher)) {
                    ArmOutcome.Armed -> Message.BiometricsEnabled
                    ArmOutcome.Refused -> Message.BiometricsRefused
                    ArmOutcome.KeystoreUnavailable -> Message.KeystoreUnavailable
                    null -> null
                }
            }
            PromptResult.Canceled -> messageChannel.trySend(Message.BiometricsCanceled)
            PromptResult.Failed -> messageChannel.trySend(Message.BiometricsFailed)
        }
    }

    fun disableBiometrics() = operate {
        try {
            vault.disarmBiometrics()
            Message.BiometricsDisabled
        } catch (_: KeystoreUnavailableException) {
            Message.KeystoreUnavailable
        }
    }

    fun setTheme(theme: AppPreferences.Theme) = save { preferences.setTheme(theme) }

    fun setAutoLock(seconds: Int) = save { preferences.setAutoLockSeconds(seconds) }

    fun setClipboard(seconds: Int) = save { preferences.setClipboardClearSeconds(seconds) }

    fun setScreenshotProtection(enabled: Boolean) = save { preferences.setScreenshotProtection(enabled) }

    fun lockNow() {
        viewModelScope.launch { vault.lock() }
    }

    fun changePassword(current: String, new: String) = operate {
        when (val outcome = vault.changePassword(current.encodeToByteArray(), new.encodeToByteArray())) {
            is ChangeOutcome.Changed -> if (outcome.biometricsDisarmed) Message.PasswordChangedBiometricsReset else Message.PasswordChanged
            ChangeOutcome.WrongCurrentPassword -> Message.WrongPassword
            ChangeOutcome.PasswordRefused -> Message.PasswordRefused
            is ChangeOutcome.Locked -> Message.Locked(outcome.remainingMillis)
            ChangeOutcome.KeystoreUnavailable -> Message.KeystoreUnavailable
            // Locked in the meantime: the screen is gone with the vault.
            null -> null
        }
    }

    /**
     * "Delete all data", once the master password is confirmed (2.7.1, SEC 2026-08-03: a moment's
     * access to an open session must not be enough). The vault locks; the entry screen follows.
     */
    fun deleteAll(password: String) = operate {
        when (val check = vault.verifyPassword(password.encodeToByteArray())) {
            CheckResult.Correct -> {
                try {
                    vault.deleteData()
                    null
                } catch (_: KeystoreUnavailableException) {
                    Message.KeystoreUnavailable
                }
            }
            CheckResult.Wrong -> Message.WrongPassword
            is CheckResult.Locked -> Message.Locked(check.remainingMillis)
            CheckResult.KeystoreUnavailable -> Message.KeystoreUnavailable
            null -> null
        }
    }

    private fun save(write: suspend () -> Unit) {
        viewModelScope.launch { write() }
    }

    /** One vault operation at a time; a second tap while it runs is ignored. */
    private fun operate(operation: suspend () -> Message?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                operation()?.let(messageChannel::trySend)
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        /** The change dialog's checks, in 2.7.1's order: current given, the rule on the new one, the confirmation. */
        fun checkChange(current: String, new: String, confirmation: String): ChangeProblem? = when {
            current.isEmpty() -> ChangeProblem.CURRENT_REQUIRED
            else -> when (PasswordPolicy.check(new)) {
                PasswordPolicy.Rejection.TOO_SHORT -> ChangeProblem.TOO_SHORT
                PasswordPolicy.Rejection.TOO_WEAK -> ChangeProblem.TOO_WEAK
                null -> if (new != confirmation) ChangeProblem.MISMATCH else null
            }
        }
    }
}
