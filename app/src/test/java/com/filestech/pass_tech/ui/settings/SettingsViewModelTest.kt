package com.filestech.pass_tech.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.ui.settings.SettingsViewModel.ChangeProblem
import com.filestech.pass_tech.ui.settings.SettingsViewModel.Message
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The settings on a real vault (in-memory Keystore, temporary files) and a real DataStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @TempDir
    lateinit var dir: File

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val owner = "renardclochesoleil2026"
    private val next = "abricotmarteaunuage2027"

    private suspend fun TestScope.opened(block: suspend TestScope.(SettingsViewModel, VaultManager, AppPreferences) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // The view model lives in a store, cleared at the end like a screen that goes away: its
        // collection of the settings stops before Main is reset.
        val viewModels = ViewModelStore()
        try {
            val keystore = InMemorySlotKeystore()
            val guard = BruteForceGuard.forVault(StateStore(File(dir, StateStore.FILE_NAME), keystore), FakeClock())
            val files = VaultFiles(File(dir, "vault").apply { mkdirs() })
            val vault = VaultManager(VaultRepository(files, keystore, guard, params = fastParams), Dispatchers.IO)
            assertThat(vault.openOrCreate(owner.encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
            val store = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(dir, "settings.preferences_pb") })
            val preferences = AppPreferences(store)
            val settings = ViewModelProvider.create(
                viewModels,
                viewModelFactory { initializer { SettingsViewModel(vault, preferences) } },
            )[SettingsViewModel::class]
            block(settings, vault, preferences)
        } finally {
            viewModels.clear()
            testScheduler.advanceUntilIdle()
            Dispatchers.resetMain()
            storeScope.cancel()
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
            settings.state.first { it.busy }
            settings.idle()
            assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
            assertThat(vault.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
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
