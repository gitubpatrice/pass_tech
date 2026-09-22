package com.filestech.pass_tech.ui.entry

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.core.vault.VaultRepository.EntryMode
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.ui.entry.EntryViewModel.Problem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The entry screen's logic on a real vault (in-memory Keystore, temporary files): which form shows,
 * what each answer of the vault becomes, and the lockout countdown, on the test scheduler's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryViewModelTest {

    @TempDir
    lateinit var dir: File

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val owner = "renardclochesoleil2026"

    /** A view model over a fresh vault, with Main and the uptime clock both on the test scheduler. */
    private suspend fun TestScope.entry(block: suspend TestScope.(EntryViewModel, VaultManager) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val clock = object : Clock {
                override fun elapsedMillis() = testScheduler.currentTime

                override fun wallMillis() = testScheduler.currentTime
            }
            val keystore = InMemorySlotKeystore()
            val guard = BruteForceGuard.forVault(StateStore(File(dir, StateStore.FILE_NAME), keystore), clock)
            val vault = VaultManager(VaultRepository(VaultFiles(dir), keystore, guard, params = fastParams), Dispatchers.IO)
            val viewModel = EntryViewModel(vault, clock)
            block(viewModel, vault)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun EntryViewModel.settled(): EntryViewModel.UiState = state.first { it.mode != null && !it.busy }

    @Test
    fun `a fresh install shows the creation form, which applies the password rule before anything`() = runTest {
        entry { viewModel, vault ->
            assertThat(viewModel.settled().mode).isEqualTo(EntryMode.CREATE)
            viewModel.create("court", "court")
            assertThat(viewModel.settled().problem).isEqualTo(Problem.TOO_SHORT)
            viewModel.create("aaaaaaaaaaaa", "aaaaaaaaaaaa")
            assertThat(viewModel.settled().problem).isEqualTo(Problem.TOO_WEAK)
            viewModel.create(owner, owner + "x")
            assertThat(viewModel.settled().problem).isEqualTo(Problem.MISMATCH)
            assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
            assertThat(vault.entryMode()).isEqualTo(EntryMode.CREATE)
        }
    }

    @Test
    fun `a creation opens the vault and asks for the backup reminder, once`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            viewModel.create(owner, owner)
            assertThat(viewModel.state.first { it.backupReminder }.problem).isNull()
            assertThat(vault.state.value).isInstanceOf(VaultManager.State.Open::class.java)
            viewModel.backupReminderSeen()
            assertThat(viewModel.state.value.backupReminder).isFalse()
        }
    }

    @Test
    fun `a view model created while the vault is open still reads the form, for the system splash waits for it`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            assertThat(vault.openOrCreate(owner.encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
            // The activity recreated in the background: a new view model, the vault still open.
            val recreated = EntryViewModel(vault, FakeClock())
            assertThat(recreated.settled().mode).isEqualTo(EntryMode.UNLOCK)
            // The same read goes on with the lockout: let it end before the test does.
            vault.lockoutRemainingMillis()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `once locked, the unlock form shows, and a wrong password says so`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            viewModel.create(owner, owner)
            viewModel.state.first { it.backupReminder }
            vault.lock()
            assertThat(viewModel.state.first { it.mode == EntryMode.UNLOCK }.problem).isNull()
            viewModel.unlock("notthepassword")
            assertThat(viewModel.state.first { it.problem != null }.problem).isEqualTo(Problem.WRONG_PASSWORD)
            viewModel.unlock(owner)
            vault.state.first { it is VaultManager.State.Open }
        }
    }

    @Test
    fun `past the free attempts, the form gives way to a countdown that runs down on uptime`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            viewModel.create(owner, owner)
            viewModel.state.first { it.backupReminder }
            vault.lock()
            viewModel.state.first { it.mode == EntryMode.UNLOCK }
            repeat(FREE_ATTEMPTS) {
                viewModel.unlock("notthepassword")
                viewModel.state.first { it.problem == Problem.WRONG_PASSWORD && !it.busy }
                viewModel.unlock("")
            }
            // The failure that starts the lockout shows the countdown at once (2.7.1), not at the next attempt.
            viewModel.unlock("notthepassword")
            val locked = viewModel.state.first { !it.busy && it.problem != null }
            assertThat(locked.lockedForMillis).isEqualTo(FIRST_LOCK_MILLIS)
            advanceTimeBy(FIRST_LOCK_MILLIS / 2)
            runCurrent()
            assertThat(viewModel.state.value.lockedForMillis).isEqualTo(FIRST_LOCK_MILLIS / 2)
            advanceTimeBy(FIRST_LOCK_MILLIS / 2)
            runCurrent()
            assertThat(viewModel.state.value.lockedForMillis).isEqualTo(0)
        }
    }

    private companion object {
        const val FREE_ATTEMPTS = 5
        const val FIRST_LOCK_MILLIS = 30_000L
    }
}
