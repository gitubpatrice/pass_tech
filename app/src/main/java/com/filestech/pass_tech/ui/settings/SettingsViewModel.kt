package com.filestech.pass_tech.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.backup.DocumentStore
import com.filestech.pass_tech.core.backup.ImportParser
import com.filestech.pass_tech.core.backup.PtbakCodec
import com.filestech.pass_tech.core.biometric.BiometricSupport
import com.filestech.pass_tech.core.di.IoDispatcher
import com.filestech.pass_tech.core.heir.HeirState
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.panic.PanicService
import com.filestech.pass_tech.core.password.PasswordPolicy
import com.filestech.pass_tech.core.phishing.AntiPhishing
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.AutoLock
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultManager.ArmOutcome
import com.filestech.pass_tech.core.vault.VaultManager.ArmStart
import com.filestech.pass_tech.core.vault.VaultManager.ChangeOutcome
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import com.filestech.pass_tech.core.vault.VaultRepository.CheckResult
import com.filestech.pass_tech.ui.components.PromptResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
    private val documents: DocumentStore,
    private val autoLock: AutoLock,
    private val panicService: PanicService,
    private val antiPhishing: AntiPhishing,
    @IoDispatcher private val io: CoroutineDispatcher,
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

    /**
     * [granted] is Android's answer, not ours: the owner gives it in the accessibility settings and
     * can take it back there. Unknown counts as not granted — the screen never claims a protection
     * is watching when it could not find out.
     */
    data class AntiPhishingUi(val enabled: Boolean = false, val granted: Boolean = false)

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

        data object DecoyCreated : Message

        data object DecoyDeleted : Message

        /** No slot is provably free. The same words a creation gives, and says nothing more. */
        data object DecoyImpossible : Message

        /** The launcher shows Pass Tech again. */
        data object DisguiseRemoved : Message

        data object HeirConfigured : Message

        data object HeirUpdated : Message

        data object HeirDisabled : Message

        /** 2.7.1 refuses a snapshot of an empty vault, and says why. */
        data object HeirVaultEmpty : Message

        /** The heir passphrase is this vault's master password: it is meant for someone else. */
        data object HeirPassphraseRefused : Message

        data object BackupSaved : Message

        data object ExportSaved : Message

        data object FileWriteError : Message

        data object ImportUnreadable : Message

        data object ImportTooLarge : Message

        /** The file was read, and refused: the parser says why, the screen says it in words. */
        class ImportFailed(val problem: ImportParser.Problem) : Message

        data object ImportNoEntry : Message

        /** A wrong passphrase and a damaged backup are the same answer, as in 2.7.1. */
        data object WrongPassphrase : Message

        class Imported(val added: Int, val skipped: Int) : Message

        data object WrongPassword : Message

        /** The new password opens an existing vault, the current one included: never said which. */
        data object PasswordRefused : Message

        data class Locked(val remainingMillis: Long) : Message

        data object KeystoreUnavailable : Message

        /** The system refused to add or withdraw the accessibility service. */
        data object AntiPhishingOnFailed : Message

        data object AntiPhishingOffFailed : Message
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

    /**
     * Whether this vault already has a decoy, or cannot rule one out: the tile then offers to delete
     * it instead of creating one. Read from the open vault, never from a setting: a vault only ever
     * knows about the decoy it made itself (design v2 §3).
     */
    val hasDecoy: StateFlow<Boolean> = vault.state
        .map { (it as? VaultManager.State.Open)?.hasDecoy == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val antiPhishingGranted = MutableStateFlow(false)

    val antiPhishingUi: StateFlow<AntiPhishingUi> = combine(antiPhishing.enabled, antiPhishingGranted) { enabled, granted ->
        AntiPhishingUi(enabled, granted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AntiPhishingUi())

    private val promptChannel = Channel<Cipher>(Channel.CONFLATED)

    /** The ciphers the screen hands to the system prompt; the result comes back to [armResult]. */
    val prompts: Flow<Cipher> = promptChannel.receiveAsFlow()

    /** Read each time the screen shows: a fingerprint may have been enrolled or removed in between. */
    fun refreshBiometricSupport() {
        biometricAvailable.value = biometricSupport.available()
    }

    /**
     * Read each time the app comes back, and not only when the screen is built: the grant is given on
     * an Android screen, so the owner returns to a tile that would otherwise still say it is waiting.
     */
    fun refreshAntiPhishing() {
        antiPhishingGranted.value = antiPhishing.granted() == true
    }

    /** After the consent dialog. Lists the service, then opens the Android screen that grants it. */
    fun enableAntiPhishing() = save {
        if (antiPhishing.turnOn()) antiPhishing.openSystemSettings() else messageChannel.trySend(Message.AntiPhishingOnFailed)
        refreshAntiPhishing()
    }

    /** Takes the grant back with the setting: see [AntiPhishing]. */
    fun disableAntiPhishing() = save {
        if (!antiPhishing.turnOff()) messageChannel.trySend(Message.AntiPhishingOffFailed)
        refreshAntiPhishing()
    }

    fun openAccessibilitySettings() {
        antiPhishing.openSystemSettings()
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
     * Creates the decoy vault (2.7.1, `settings_screen.dart:522-677`). The chosen password is refused
     * if it opens a vault that already exists — the current one included, and never said which.
     */
    fun configureDecoy(password: String) = operate {
        when (val outcome = vault.configureDecoy(password.encodeToByteArray())) {
            VaultManager.DecoyOutcome.Created -> Message.DecoyCreated
            VaultManager.DecoyOutcome.PasswordRefused -> Message.PasswordRefused
            VaultManager.DecoyOutcome.Impossible -> Message.DecoyImpossible
            is VaultManager.DecoyOutcome.Locked -> Message.Locked(outcome.remainingMillis)
            VaultManager.DecoyOutcome.KeystoreUnavailable -> Message.KeystoreUnavailable
            null -> null
        }
    }

    /**
     * The heir of THIS vault: whether one is set up, after how many days of silence, and how many
     * days of silence there are so far. Read when the screen shows and after every change — it comes
     * from the vault, not from a setting, and a decoy has its own (design v2 §8).
     */
    private val mutableHeir = MutableStateFlow<HeirState.Status?>(null)
    val heir: StateFlow<HeirState.Status?> = mutableHeir.asStateFlow()

    fun refreshHeir() {
        viewModelScope.launch { mutableHeir.value = vault.heirStatus() }
    }

    /** Takes or replaces the snapshot. [update] only changes which words the screen says afterwards. */
    fun configureHeir(passphrase: String, update: Boolean) = operate {
        val days = mutableHeir.value?.thresholdDays ?: HeirState.DEFAULT_THRESHOLD_DAYS
        val outcome = vault.configureHeir(passphrase.encodeToByteArray(), days)
        mutableHeir.value = vault.heirStatus()
        when (outcome) {
            VaultRepository.HeirConfigureResult.Done -> if (update) Message.HeirUpdated else Message.HeirConfigured
            VaultRepository.HeirConfigureResult.VaultEmpty -> Message.HeirVaultEmpty
            VaultRepository.HeirConfigureResult.PassphraseRefused -> Message.HeirPassphraseRefused
            is VaultRepository.HeirConfigureResult.Locked -> Message.Locked(outcome.remainingMillis)
            VaultRepository.HeirConfigureResult.KeystoreUnavailable -> Message.KeystoreUnavailable
            null -> null
        }
    }

    fun disableHeir() = operate {
        val done = vault.disableHeir()
        mutableHeir.value = vault.heirStatus()
        if (done) Message.HeirDisabled else null
    }

    fun setHeirThreshold(days: Int) = operate {
        vault.setHeirThreshold(days)
        mutableHeir.value = vault.heirStatus()
        null
    }

    /**
     * Whether the launcher currently shows a calculator. Read each time the screen shows, like the
     * biometric support: the answer lives in the system, not in a setting of ours.
     *
     * `null`, "the system did not answer", counts as NOT disguised for this tile only: the cost of a
     * hidden button is one trip through the settings, while showing "restore the Pass Tech name" on a
     * phone that shows Pass Tech already would be a puzzle.
     */
    private val mutableDisguised = MutableStateFlow(false)
    val disguised: StateFlow<Boolean> = mutableDisguised.asStateFlow()

    fun refreshDisguise() {
        mutableDisguised.value = panicService.disguised() == true
    }

    /**
     * Panic mode (2.7.1, `settings_screen.dart:732-832`): the screen has already asked, and warned
     * about the fingerprint if one opens THIS vault. Not [operate]: the vault locks under it, the
     * screen goes with it, and a spinner left behind would outlive them both.
     */
    fun panic() {
        viewModelScope.launch {
            panicService.panic()
            mutableDisguised.value = true
        }
    }

    fun reveal() = operate {
        val done = panicService.reveal()
        refreshDisguise()
        if (done) Message.DisguiseRemoved else null
    }

    /** Deletes it, with no re-authentication, as 2.7.1 does: the vault is open and nothing of it is lost. */
    fun deleteDecoy() = operate {
        when (vault.deleteDecoy()) {
            VaultManager.DecoyDeleteOutcome.Deleted -> Message.DecoyDeleted
            // The tile already went back to "set up": nothing to say.
            VaultManager.DecoyDeleteOutcome.NotConfigured -> null
            VaultManager.DecoyDeleteOutcome.KeystoreUnavailable -> Message.KeystoreUnavailable
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

    /** A file the owner picked, waiting for something only they can give. */
    sealed interface Pending {
        /** An encrypted backup: its passphrase is needed before anything can be read. */
        class Passphrase(val content: String) : Pending

        /** What a file holds, waiting for the owner to confirm the merge. Cleared when the vault locks. */
        class Confirm(val entries: List<Entry>, val format: ImportParser.Format?) : Pending
    }

    /** Where a file is to be written: the system asks the owner, the app never chooses a folder. */
    class SaveRequest(val kind: Kind, val suggestedName: String, val mimeType: String)

    enum class Kind { BACKUP, PLAIN }

    private val mutablePending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = mutablePending.asStateFlow()

    private val saveChannel = Channel<SaveRequest>(Channel.CONFLATED)

    /** Where the screen must ask the system to create a document. */
    val saveRequests: Flow<SaveRequest> = saveChannel.receiveAsFlow()

    /** Kept only between the passphrase dialog and the moment the file is written. */
    private var backupPassphrase: String? = null

    // After the fields it touches, never before: on a phone this collection starts inside the
    // constructor (Main is immediate there), and it crashed on a field that did not exist yet.
    init {
        // A file read but not yet merged never outlives the vault it was going into.
        viewModelScope.launch {
            vault.state.collect { state ->
                if (state == VaultManager.State.Locked) {
                    mutablePending.value = null
                    backupPassphrase = null
                }
            }
        }
    }

    /** The passphrase of a backup, then the system asks where to write it. */
    fun startBackup(passphrase: String) {
        backupPassphrase = passphrase
        saveChannel.trySend(SaveRequest(Kind.BACKUP, "pass_tech_${today()}.ptbak", BACKUP_MIME))
    }

    /**
     * The master password, then the system asks where to write the plain export (2.7.1: the warning
     * first, then this). A failed check counts against the lockout, like every other check.
     */
    fun startPlainExport(master: String) = operate {
        when (val check = vault.verifyPassword(master.encodeToByteArray())) {
            CheckResult.Correct -> {
                saveChannel.trySend(SaveRequest(Kind.PLAIN, "pass_tech_export.json", PLAIN_MIME))
                null
            }
            CheckResult.Wrong -> Message.WrongPassword
            is CheckResult.Locked -> Message.Locked(check.remainingMillis)
            CheckResult.KeystoreUnavailable -> Message.KeystoreUnavailable
            null -> null
        }
    }

    /** Writes into the document the owner chose. The passphrase is forgotten either way. */
    fun saveTo(kind: Kind, uri: Uri) = operate {
        val passphrase = backupPassphrase
        backupPassphrase = null
        val content = when (kind) {
            Kind.BACKUP -> passphrase?.let { vault.exportBackup(it) }
            Kind.PLAIN -> vault.exportPlain()
        }
        when {
            // Locked in the meantime: the screen is gone with the vault.
            content == null -> null
            !withContext(io) { documents.write(uri, content) } -> Message.FileWriteError
            kind == Kind.BACKUP -> Message.BackupSaved
            else -> Message.ExportSaved
        }
    }

    /**
     * Reads the file the owner picked. [untitled] is the title given to an entry that has none, in the
     * language the app speaks now: it is written into the vault (2.7.1 v2.7.0).
     */
    fun importFrom(uri: Uri, untitled: String) = operate {
        when (val read = withContext(io) { documents.read(uri, ImportParser.MAX_FILE_CHARS.toLong()) }) {
            is DocumentStore.Read.TooLarge -> Message.ImportTooLarge
            DocumentStore.Read.Unreadable -> Message.ImportUnreadable
            is DocumentStore.Read.Text -> readFile(read.name, read.content, untitled)
        }
    }

    /** What a file holds, once it is read: the half of the import that does not touch storage. */
    internal suspend fun readFile(name: String, content: String, untitled: String): Message? {
        if (PtbakCodec.looksLikeBackup(name, content)) {
            mutablePending.value = Pending.Passphrase(content)
            return null
        }
        val result = withContext(io) { ImportParser.parse(content, untitled) }
        return when {
            result.problem != null -> Message.ImportFailed(result.problem)
            result.entries.isEmpty() -> Message.ImportNoEntry
            else -> {
                mutablePending.value = Pending.Confirm(result.entries, result.format)
                null
            }
        }
    }

    /** Opens the backup the owner picked. A wrong passphrase and a damaged file answer the same way. */
    fun openBackup(passphrase: String) = operate {
        val content = (mutablePending.value as? Pending.Passphrase)?.content ?: return@operate null
        mutablePending.value = null
        val imported = withContext(io) { PtbakCodec.import(content, passphrase) }
        when {
            imported == null -> Message.WrongPassphrase
            imported.entries.isEmpty() -> Message.ImportNoEntry
            else -> {
                mutablePending.value = Pending.Confirm(imported.entries, null)
                null
            }
        }
    }

    /** Merges what was read, once the owner has seen how many entries and in which format. */
    fun confirmImport() = operate {
        val entries = (mutablePending.value as? Pending.Confirm)?.entries ?: return@operate null
        mutablePending.value = null
        try {
            vault.importEntries(entries)?.let { Message.Imported(it.added, it.skipped) }
        } catch (_: KeystoreUnavailableException) {
            Message.KeystoreUnavailable
        }
    }

    fun cancelImport() {
        mutablePending.value = null
    }

    /** Announced before the system picker opens, so that the trip out of the app does not lock the vault. */
    fun expectFilePicker() = autoLock.systemScreenExpected()

    private fun today(): String = LocalDate.now().toString()

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
        private const val BACKUP_MIME = "application/octet-stream"
        private const val PLAIN_MIME = "application/json"

        /** The change dialog's checks, in 2.7.1's order: current given, the rule on the new one, the confirmation. */
        fun checkChange(current: String, new: String, confirmation: String): ChangeProblem? =
            if (current.isEmpty()) ChangeProblem.CURRENT_REQUIRED else checkNewPassword(new, confirmation)

        /** The same rule everywhere a password is chosen: the creation form, the change dialog, the decoy. */
        fun checkNewPassword(new: String, confirmation: String): ChangeProblem? = when (PasswordPolicy.check(new)) {
            PasswordPolicy.Rejection.TOO_SHORT -> ChangeProblem.TOO_SHORT
            PasswordPolicy.Rejection.TOO_WEAK -> ChangeProblem.TOO_WEAK
            null -> if (new != confirmation) ChangeProblem.MISMATCH else null
        }
    }
}
