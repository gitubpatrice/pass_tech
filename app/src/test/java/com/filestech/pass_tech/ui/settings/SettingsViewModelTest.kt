package com.filestech.pass_tech.ui.settings

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.filestech.pass_tech.core.backup.DocumentStore
import com.filestech.pass_tech.core.backup.ImportParser
import com.filestech.pass_tech.core.biometric.StoredBiometricBinding
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.AutoLock
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import com.filestech.pass_tech.testing.FakeBiometricKeys
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.ui.components.PromptResult
import com.filestech.pass_tech.ui.settings.SettingsViewModel.ChangeProblem
import com.filestech.pass_tech.ui.settings.SettingsViewModel.Message
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.Cipher

/** The settings on a real vault (in-memory Keystore, temporary files) and a real DataStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @TempDir
    lateinit var dir: File

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val owner = "renardclochesoleil2026"
    private val next = "abricotmarteaunuage2027"
    private val untitled = "Sans titre"
    private val bioKeys = FakeBiometricKeys()

    private fun entry(title: String) = com.filestech.pass_tech.core.model.Entry(
        id = title,
        title = title,
        category = "Web",
        password = "secret-" + title,
        createdAt = com.filestech.pass_tech.core.model.DartDateTime.nowLocal(),
        updatedAt = com.filestech.pass_tech.core.model.DartDateTime.nowLocal(),
    )

    /** The screen's file access, which these tests go around: they hand the content over themselves. */
    private object NoDocuments : DocumentStore {
        override fun read(uri: Uri, maxBytes: Long) = DocumentStore.Read.Unreadable

        override fun write(uri: Uri, content: String) = false
    }

    private suspend fun TestScope.opened(block: suspend TestScope.(SettingsViewModel, VaultManager, AppPreferences) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The settings store on the test scheduler too: nothing of it is left running on a real thread,
        // where it would come back to a Main that no longer exists (the CI caught that twice today).
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        // The view model lives in a store, cleared at the end like a screen that goes away: its
        // collection of the settings stops before Main is reset.
        val viewModels = ViewModelStore()
        try {
            val keystore = InMemorySlotKeystore()
            val state = StateStore(File(dir, StateStore.FILE_NAME), keystore)
            val guard = BruteForceGuard.forVault(state, FakeClock())
            val files = VaultFiles(File(dir, "vault").apply { mkdirs() })
            // The vault on the test scheduler too: no write left on a real thread to come back to Main after the test.
            val io = StandardTestDispatcher(testScheduler)
            val repository = VaultRepository(files, keystore, guard, StoredBiometricBinding(state, bioKeys), fastParams)
            val vault = VaultManager(repository, io)
            assertThat(vault.openOrCreate(owner.encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
            val store = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(dir, "settings.preferences_pb") })
            val preferences = AppPreferences(store)
            val settings = ViewModelProvider.create(
                viewModels,
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            vault = vault,
                            preferences = preferences,
                            biometricSupport = { true },
                            // Storage is never touched here: the tests hand the file's content straight over.
                            documents = NoDocuments,
                            autoLock = AutoLock(vault, MutableStateFlow(AppPreferences.AUTO_LOCK_DEFAULT), FakeClock(), backgroundScope),
                            io = io,
                        )
                    }
                },
            )[SettingsViewModel::class]
            block(settings, vault, preferences)
        } finally {
            viewModels.clear()
            storeScope.cancel()
            testScheduler.advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private suspend fun SettingsViewModel.idle() = state.first { !it.busy }

    private suspend fun SettingsViewModel.nextMessage(): Message = messages.first()

    @Test
    fun `the change dialog checks in 2_7_1's order`() {
        assertThat(SettingsViewModel.checkChange("", "court", "x")).isEqualTo(ChangeProblem.CURRENT_REQUIRED)
        assertThat(SettingsViewModel.checkChange("old", "court", "court")).isEqualTo(ChangeProblem.TOO_SHORT)
        // The rule speaks before the confirmation: a short password that does not match is "too short".
        assertThat(SettingsViewModel.checkChange("old", "court", "autre")).isEqualTo(ChangeProblem.TOO_SHORT)
        assertThat(SettingsViewModel.checkChange("old", "aaaaaaaaaaaa", "aaaaaaaaaaaa")).isEqualTo(ChangeProblem.TOO_WEAK)
        assertThat(SettingsViewModel.checkChange("old", next, next + "x")).isEqualTo(ChangeProblem.MISMATCH)
        assertThat(SettingsViewModel.checkChange("old", next, next)).isNull()
    }

    @Test
    fun `a change of master password, then the new one opens the vault and the old one no longer does`() = runTest {
        opened { settings, vault, _ ->
            settings.changePassword(owner, next)
            assertThat(settings.nextMessage()).isEqualTo(Message.PasswordChanged)
            settings.idle()
            vault.lock()
            assertThat(vault.unlock(owner.encodeToByteArray())).isEqualTo(VaultManager.UnlockOutcome.WrongPassword)
            assertThat(vault.unlock(next.encodeToByteArray())).isEqualTo(VaultManager.UnlockOutcome.Opened)
        }
    }

    @Test
    fun `a wrong current password changes nothing`() = runTest {
        opened { settings, vault, _ ->
            settings.changePassword("pas le bon mot de passe", next)
            assertThat(settings.nextMessage()).isEqualTo(Message.WrongPassword)
            settings.idle()
            vault.lock()
            assertThat(vault.unlock(owner.encodeToByteArray())).isEqualTo(VaultManager.UnlockOutcome.Opened)
        }
    }

    @Test
    fun `a new password that opens a vault is refused, with words that do not say which`() = runTest {
        opened { settings, _, _ ->
            settings.changePassword(owner, owner)
            assertThat(settings.nextMessage()).isEqualTo(Message.PasswordRefused)
        }
    }

    @Test
    fun `delete all asks for the master password, and only then deletes`() = runTest {
        opened { settings, vault, _ ->
            settings.deleteAll("pas le bon mot de passe")
            assertThat(settings.nextMessage()).isEqualTo(Message.WrongPassword)
            settings.idle()
            assertThat(vault.state.value).isInstanceOf(VaultManager.State.Open::class.java)

            settings.deleteAll(owner)
            // The vault runs on the test scheduler: the deletion is over once it is idle. Waiting to SEE
            // the busy state instead hung when the whole operation ran before the screen state was read.
            testScheduler.advanceUntilIdle()
            assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
            assertThat(vault.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        }
    }

    /** Turns the switch on, and answers the prompt with [result]. */
    private suspend fun SettingsViewModel.enable(result: (Cipher) -> PromptResult) {
        enableBiometrics()
        armResult(result(prompts.first()))
    }

    @Test
    fun `turning biometrics on arms this vault, and a password change then disarms it and says so`() = runTest {
        opened { settings, vault, _ ->
            settings.refreshBiometricSupport()
            settings.enable { PromptResult.Authenticated(it) }
            assertThat(settings.nextMessage()).isEqualTo(Message.BiometricsEnabled)
            assertThat(settings.biometrics.first { it.status == BiometricStatus.THIS_VAULT }.available).isTrue()
            assertThat(vault.biometricsArmed()).isTrue()

            settings.idle()
            settings.changePassword(owner, next)
            assertThat(settings.nextMessage()).isEqualTo(Message.PasswordChangedBiometricsReset)
            settings.biometrics.first { it.status == BiometricStatus.OFF }
        }
    }

    @Test
    fun `a cancelled prompt arms nothing and says so, and turning it off disarms`() = runTest {
        opened { settings, vault, _ ->
            settings.enable { PromptResult.Canceled }
            assertThat(settings.nextMessage()).isEqualTo(Message.BiometricsCanceled)
            assertThat(vault.biometricsArmed()).isFalse()

            settings.idle()
            settings.enable { PromptResult.Authenticated(it) }
            assertThat(settings.nextMessage()).isEqualTo(Message.BiometricsEnabled)
            settings.idle()
            settings.disableBiometrics()
            assertThat(settings.nextMessage()).isEqualTo(Message.BiometricsDisabled)
            assertThat(vault.biometricsArmed()).isFalse()
        }
    }

    @Test
    fun `a vault with a decoy is refused, with 2_7_1's words, and no prompt`() = runTest {
        opened { settings, vault, _ ->
            assertThat(vault.configureDecoy("cerisetambourlune2028".encodeToByteArray())).isEqualTo(VaultManager.DecoyOutcome.Created)
            settings.enableBiometrics()
            assertThat(settings.nextMessage()).isEqualTo(Message.BiometricsRefused)
            assertThat(bioKeys.created).isEqualTo(0)
        }
    }

    /**
     * The phone's main thread runs what a view model starts in its constructor at once, so anything it
     * touches must already exist. Here Main is unconfined for the same reason: on the test scheduler the
     * collection waited, and a field read before its declaration crashed only on the device (2026-09-22).
     */
    @Test
    fun `the view model can be built on a main thread that runs at once`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val viewModels = ViewModelStore()
        try {
            val keystore = InMemorySlotKeystore()
            val state = StateStore(File(dir, StateStore.FILE_NAME), keystore)
            val guard = BruteForceGuard.forVault(state, FakeClock())
            val files = VaultFiles(File(dir, "vault").apply { mkdirs() })
            val io = StandardTestDispatcher(testScheduler)
            val vault = VaultManager(VaultRepository(files, keystore, guard, params = fastParams), io)
            val store = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(dir, "now.preferences_pb") })
            val settings = ViewModelProvider.create(
                viewModels,
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            vault = vault,
                            preferences = AppPreferences(store),
                            biometricSupport = { true },
                            documents = NoDocuments,
                            autoLock = AutoLock(vault, MutableStateFlow(AppPreferences.AUTO_LOCK_DEFAULT), FakeClock(), backgroundScope),
                            io = io,
                        )
                    }
                },
            )[SettingsViewModel::class]
            assertThat(settings.pending.value).isNull()
        } finally {
            viewModels.clear()
            storeScope.cancel()
            testScheduler.advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a CSV is read, counted, and merged only once confirmed`() = runTest {
        opened { settings, vault, _ ->
            val csv = "name,username,password\nBank,alice,pw\nMail,bob,pw2\n"
            assertThat(settings.readFile("export.csv", csv, untitled)).isNull()
            val pending = settings.pending.value as SettingsViewModel.Pending.Confirm
            assertThat(pending.entries).hasSize(2)
            assertThat(pending.format).isEqualTo(ImportParser.Format.CSV)
            // Nothing has reached the vault yet.
            assertThat((vault.state.value as VaultManager.State.Open).entries).isEmpty()

            settings.confirmImport()
            val message = settings.nextMessage() as Message.Imported
            assertThat(message.added).isEqualTo(2)
            assertThat(message.skipped).isEqualTo(0)
            assertThat(settings.pending.value).isNull()
            assertThat((vault.state.value as VaultManager.State.Open).entries.map { it.title })
                .containsExactly("Bank", "Mail")
        }
    }

    @Test
    fun `a cancelled import writes nothing and forgets the file`() = runTest {
        opened { settings, vault, _ ->
            settings.readFile("export.csv", "name,password\nBank,pw\n", untitled)
            settings.cancelImport()
            assertThat(settings.pending.value).isNull()
            settings.confirmImport()
            settings.idle()
            assertThat((vault.state.value as VaultManager.State.Open).entries).isEmpty()
        }
    }

    @Test
    fun `a file the reader refuses says why, and nothing waits`() = runTest {
        opened { settings, _, _ ->
            val message = settings.readFile("export.csv", "name,username\nBank,alice\n", untitled)
            assertThat((message as Message.ImportFailed).problem).isEqualTo(ImportParser.Problem.CSV_NO_PASSWORD_COLUMN)
            assertThat(settings.pending.value).isNull()
        }
    }

    @Test
    fun `a file with nothing to import says so`() = runTest {
        opened { settings, _, _ ->
            assertThat(settings.readFile("export.json", "[]", untitled)).isEqualTo(Message.ImportNoEntry)
            assertThat(settings.pending.value).isNull()
        }
    }

    @Test
    fun `a backup asks for its passphrase, and a wrong one says so without saying which`() = runTest {
        opened { settings, vault, _ ->
            vault.updateEntries { listOf(entry("Bank")) }
            val backup = vault.exportBackup("phrase de sauvegarde")!!
            vault.updateEntries { emptyList() }

            assertThat(settings.readFile("pass_tech_2026-09-22.ptbak", backup, untitled)).isNull()
            assertThat(settings.pending.value).isInstanceOf(SettingsViewModel.Pending.Passphrase::class.java)

            settings.openBackup("pas la bonne phrase")
            assertThat(settings.nextMessage()).isEqualTo(Message.WrongPassphrase)
            assertThat(settings.pending.value).isNull()

            // Again, with the right one.
            settings.idle()
            settings.readFile("pass_tech_2026-09-22.ptbak", backup, untitled)
            settings.openBackup("phrase de sauvegarde")
            // Waiting for the state to arrive, never for a passing "not busy": the work may not have started.
            val pending = settings.pending.first { it is SettingsViewModel.Pending.Confirm }
                as SettingsViewModel.Pending.Confirm
            assertThat(pending.entries.map { it.title }).containsExactly("Bank")
            // No format: it came out of a backup, and the dialog says so.
            assertThat(pending.format).isNull()
        }
    }

    @Test
    fun `a backup is recognised by its own magic word, whatever the file is called`() = runTest {
        opened { settings, vault, _ ->
            val backup = vault.exportBackup("phrase de sauvegarde")!!
            assertThat(settings.readFile("no-extension", backup, untitled)).isNull()
            assertThat(settings.pending.value).isInstanceOf(SettingsViewModel.Pending.Passphrase::class.java)
        }
    }

    @Test
    fun `locking the vault drops the file that was waiting`() = runTest {
        opened { settings, vault, _ ->
            settings.readFile("export.csv", "name,password\nBank,pw\n", untitled)
            assertThat(settings.pending.value).isNotNull()
            vault.lock()
            settings.pending.first { it == null }
        }
    }

    @Test
    fun `the plain export waits for the master password`() = runTest {
        opened { settings, _, _ ->
            settings.startPlainExport("pas le bon mot de passe")
            assertThat(settings.nextMessage()).isEqualTo(Message.WrongPassword)
            settings.idle()

            settings.startPlainExport(owner)
            val request = settings.saveRequests.first()
            assertThat(request.kind).isEqualTo(SettingsViewModel.Kind.PLAIN)
            assertThat(request.suggestedName).isEqualTo("pass_tech_export.json")
        }
    }

    @Test
    fun `a backup asks the system where to write it, under a dated name`() = runTest {
        opened { settings, _, _ ->
            settings.startBackup("phrase de sauvegarde")
            val request = settings.saveRequests.first()
            assertThat(request.kind).isEqualTo(SettingsViewModel.Kind.BACKUP)
            assertThat(request.suggestedName).matches("pass_tech_\\d{4}-\\d{2}-\\d{2}\\.ptbak")
            assertThat(request.mimeType).isEqualTo("application/octet-stream")
        }
    }

    @Test
    fun `defaults, choices, and a damaged theme reading as the system's`() = runTest {
        opened { settings, _, preferences ->
            // Never set: on. Read from the store, not from the screen's default before the first read.
            assertThat(preferences.screenshotProtection.first()).isTrue()
            settings.setScreenshotProtection(false)
            settings.setTheme(AppPreferences.Theme.DARK)
            settings.setAutoLock(60)
            settings.setClipboard(0)
            val chosen = settings.state.first {
                it.theme == AppPreferences.Theme.DARK && !it.screenshotProtection && it.clipboardSeconds == 0
            }
            assertThat(chosen.autoLockSeconds).isEqualTo(60)
            assertThat(preferences.theme.first()).isEqualTo(AppPreferences.Theme.DARK)
        }
    }

    @Test
    fun `a theme value that is not a choice reads as the system's`() = runTest {
        opened { _, _, _ ->
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(dir, "other.preferences_pb") },
            )
            store.edit { it[stringPreferencesKey("theme_mode")] = "purple" }
            assertThat(AppPreferences(store).theme.first()).isEqualTo(AppPreferences.Theme.SYSTEM)
        }
    }
}
