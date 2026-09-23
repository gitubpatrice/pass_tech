package com.filestech.pass_tech.ui.entry

import com.filestech.pass_tech.core.biometric.StoredBiometricBinding
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.core.vault.VaultRepository.EntryMode
import com.filestech.pass_tech.testing.FakeBiometricKeys
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FixedDomain
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.filestech.pass_tech.ui.components.PromptResult
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
    private val bioKeys = FakeBiometricKeys()

    /** Whether the phone can authenticate with a biometric. */
    private var biometricHardware = true

    /** A view model over a fresh vault, with Main and the uptime clock both on the test scheduler. */
    private suspend fun TestScope.entry(block: suspend TestScope.(EntryViewModel, VaultManager) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val clock = object : Clock {
                override fun elapsedMillis() = testScheduler.currentTime

                override fun wallMillis() = testScheduler.currentTime
            }
            val keystore = InMemorySlotKeystore()
            val state = StateStore(File(dir, StateStore.FILE_NAME), keystore)
            val guard = BruteForceGuard.forVault(state, clock)
            val biometrics = StoredBiometricBinding(state, bioKeys)
            // The vault on the test scheduler too: no write left on a real thread to come back to Main after the test.
            val io = StandardTestDispatcher(testScheduler)
            val heir = heirRepository(dir, keystore, state, clock, fastParams)
            val vault = VaultManager(VaultRepository(VaultFiles(dir), keystore, guard, heir, biometrics, fastParams), FixedDomain(), io)
            val viewModel = EntryViewModel(vault, clock) { biometricHardware }
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
            val recreated = EntryViewModel(vault, FakeClock()) { biometricHardware }
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

    /** Creates the vault, arms the fingerprint on it, then locks: the unlock form follows. */
    @Test
    fun `heir access is offered on every phone, and refuses in the same words whatever is wrong`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            // A phone where nothing at all is set up: the offer is there, and opens nothing.
            viewModel.openHeirPrompt()
            assertThat(viewModel.state.value.heirPrompt).isTrue()
            viewModel.unlockAsHeir("une phrase quelconque")
            testScheduler.advanceUntilIdle()
            assertThat(viewModel.state.value.problem).isEqualTo(EntryViewModel.Problem.HEIR_REFUSED)
            assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)

            viewModel.cancelHeirPrompt()
            assertThat(viewModel.state.value.heirPrompt).isFalse()
        }
    }

    /**
     * The whole heir path from the screen: set up, waited out, read, and closed — and the dialog does
     * not come back on its own over the unlock form once the reading is over (seen on the emulator).
     */
    @Test
    fun `an heir reads the snapshot once the vault has been silent, and the dialog does not linger`() = runTest {
        entry { viewModel, vault ->
            viewModel.settled()
            viewModel.create(owner, owner)
            viewModel.state.first { it.backupReminder }
            vault.updateEntries { it + heirEntry() }
            assertThat(vault.configureHeir("phrasedelheritier2026".encodeToByteArray(), 30))
                .isEqualTo(VaultRepository.HeirConfigureResult.Done)
            vault.lock()
            viewModel.state.first { it.mode == EntryMode.UNLOCK }

            advanceTimeBy((30L + 7) * 24 * 60 * 60 * 1000)
            viewModel.openHeirPrompt()
            viewModel.unlockAsHeir("phrasedelheritier2026")
            testScheduler.advanceUntilIdle()
            val heirState = vault.state.value as VaultManager.State.Heir
            assertThat(heirState.entries.map { it.title }).containsExactly("bank")

            // What the auto-lock does at the next return to the app.
            vault.lock()
            testScheduler.advanceUntilIdle()
            assertThat(viewModel.state.value.heirPrompt).isFalse()
        }
    }

    private fun heirEntry() = com.filestech.pass_tech.core.model.Entry(
        id = "bank",
        title = "bank",
        category = "Web",
        password = "secret",
        createdAt = com.filestech.pass_tech.core.model.DartDateTime.nowLocal(),
        updatedAt = com.filestech.pass_tech.core.model.DartDateTime.nowLocal(),
    )

    private suspend fun TestScope.armedThenLocked(viewModel: EntryViewModel, vault: VaultManager) {
        viewModel.settled()
        viewModel.create(owner, owner)
        viewModel.state.first { it.backupReminder }
        val start = vault.startArmingBiometrics() as VaultManager.ArmStart.Ready
        assertThat(vault.armBiometrics(start.cipher)).isEqualTo(VaultManager.ArmOutcome.Armed)
        vault.lock()
        viewModel.state.first { it.mode == EntryMode.UNLOCK }
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `an armed fingerprint is offered, prompted once on its own, and opens the vault`() = runTest {
        entry { viewModel, vault ->
            armedThenLocked(viewModel, vault)
            assertThat(viewModel.state.value.biometric).isTrue()
            assertThat(viewModel.state.value.biometricAutoPrompt).isTrue()
            viewModel.startBiometric()
            assertThat(viewModel.state.value.biometricAutoPrompt).isFalse()
            val cipher = viewModel.prompts.first()
            viewModel.biometricResult(PromptResult.Authenticated(cipher))
            vault.state.first { it is VaultManager.State.Open }
        }
    }

    @Test
    fun `a cancelled prompt says nothing, stays offered, and does not come back on its own`() = runTest {
        entry { viewModel, vault ->
            armedThenLocked(viewModel, vault)
            viewModel.startBiometric()
            viewModel.prompts.first()
            viewModel.biometricResult(PromptResult.Canceled)
            val after = viewModel.settled()
            assertThat(after.problem).isNull()
            assertThat(after.biometric).isTrue()
            assertThat(after.biometricAutoPrompt).isFalse()
            assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
        }
    }

    @Test
    fun `a fingerprint enrolled since arming disarms, says so, and hides the button`() = runTest {
        entry { viewModel, vault ->
            armedThenLocked(viewModel, vault)
            bioKeys.enrollFingerprint()
            viewModel.startBiometric()
            val after = viewModel.state.first { !it.busy && it.problem != null }
            assertThat(after.problem).isEqualTo(Problem.BIOMETRIC_INVALIDATED)
            assertThat(after.biometric).isFalse()
            assertThat(vault.biometricsArmed()).isFalse()
        }
    }

    @Test
    fun `no fingerprint offered on a phone that cannot authenticate, even armed`() = runTest {
        entry { viewModel, vault ->
            biometricHardware = false
            armedThenLocked(viewModel, vault)
            assertThat(viewModel.state.value.biometric).isFalse()
            viewModel.startBiometric()
            assertThat(viewModel.state.value.busy).isFalse()
        }
    }

    private companion object {
        const val FREE_ATTEMPTS = 5
        const val FIRST_LOCK_MILLIS = 30_000L
    }
}
