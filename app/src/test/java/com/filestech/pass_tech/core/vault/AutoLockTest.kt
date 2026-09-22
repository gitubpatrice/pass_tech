package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The auto-lock on an open vault (in-memory Keystore, temporary files). Two clocks, as on a phone: the
 * boot clock ([Clock.elapsedMillis], which counts deep sleep) moves by hand, and the coroutine delays
 * run on the test scheduler, which does not count it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockTest {

    @TempDir
    lateinit var dir: File

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)

    private class BootClock(var now: Long = 5_000_000L) : Clock {
        override fun elapsedMillis() = now

        override fun wallMillis() = now
    }

    private data class Setup(val vault: VaultManager, val autoLock: AutoLock, val clock: BootClock, val delay: MutableStateFlow<Int>)

    /** An open vault, the auto-lock watching it, and a delay of [seconds]. */
    private suspend fun TestScope.opened(seconds: Int, block: suspend TestScope.(Setup) -> Unit) {
        val clock = BootClock()
        val keystore = InMemorySlotKeystore()
        val guard = BruteForceGuard.forVault(StateStore(File(dir, StateStore.FILE_NAME), keystore), clock)
        val vault = VaultManager(VaultRepository(VaultFiles(dir), keystore, guard, params = fastParams), Dispatchers.IO)
        assertThat(vault.openOrCreate("renardclochesoleil2026".encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
        val delay = MutableStateFlow(seconds)
        block(Setup(vault, AutoLock(vault, delay, clock, backgroundScope), clock, delay))
    }

    /** Runs what is due on the scheduler, then waits for any vault operation it started. */
    private suspend fun TestScope.settled(vault: VaultManager): VaultManager.State {
        runCurrent()
        vault.entryMode()
        return vault.state.value
    }

    private fun VaultManager.State.isOpen() = this is VaultManager.State.Open

    @Test
    fun `back before the delay, the vault is still open`() = runTest {
        opened(seconds = 300) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            clock.now += 299_000
            advanceTimeBy(299_000)
            autoLock.cameToForeground()
            assertThat(settled(vault).isOpen()).isTrue()
            assertThat(autoLock.locking.value).isFalse()
        }
    }

    @Test
    fun `the vault locks in the background once the delay is over, without coming back`() = runTest {
        opened(seconds = 60) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            clock.now += 59_000
            advanceTimeBy(59_000)
            assertThat(settled(vault).isOpen()).isTrue()
            clock.now += 16_000
            advanceTimeBy(16_000)
            assertThat(settled(vault)).isEqualTo(VaultManager.State.Locked)
        }
    }

    @Test
    fun `deep sleep counts, and the timer catches up at the next check`() = runTest {
        opened(seconds = 300) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            runCurrent()
            // Two hours of deep sleep: the boot clock moves, the coroutine clock does not.
            clock.now += 2 * 3_600_000
            assertThat(settled(vault).isOpen()).isTrue()
            advanceTimeBy(15_001)
            assertThat(settled(vault)).isEqualTo(VaultManager.State.Locked)
        }
    }

    @Test
    fun `on the way back, an overdue vault locks even if the timer never ran, and the screens hide it meanwhile`() =
        runTest {
            opened(seconds = 60) { (vault, autoLock, clock) ->
                autoLock.wentToBackground()
                // Frozen app: the time passed, the timer did not run.
                clock.now += 61_000
                autoLock.cameToForeground()
                assertThat(autoLock.locking.value).isTrue()
                assertThat(settled(vault)).isEqualTo(VaultManager.State.Locked)
                assertThat(autoLock.locking.value).isFalse()
            }
        }

    @Test
    fun `immediately locks as soon as the app is left`() = runTest {
        opened(seconds = 0) { (vault, autoLock) ->
            autoLock.wentToBackground()
            assertThat(settled(vault)).isEqualTo(VaultManager.State.Locked)
        }
    }

    @Test
    fun `never keeps the vault open, in the background and on the way back`() = runTest {
        opened(seconds = AppPreferences.NEVER) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            clock.now += 24 * 3_600_000
            advanceTimeBy(24 * 3_600_000)
            autoLock.cameToForeground()
            assertThat(settled(vault).isOpen()).isTrue()
        }
    }

    @Test
    fun `coming back stops the timer, and the next departure starts from zero`() = runTest {
        opened(seconds = 60) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            clock.now += 50_000
            advanceTimeBy(50_000)
            autoLock.cameToForeground()
            // In use for a minute: the timer of the first departure must not lock under the user's eyes.
            clock.now += 60_000
            advanceTimeBy(60_000)
            assertThat(settled(vault).isOpen()).isTrue()
            autoLock.wentToBackground()
            clock.now += 50_000
            advanceTimeBy(50_000)
            assertThat(settled(vault).isOpen()).isTrue()
        }
    }

    @Test
    fun `a boot clock that seems to go back locks`() = runTest {
        opened(seconds = 300) { (vault, autoLock, clock) ->
            autoLock.wentToBackground()
            clock.now -= 1_000
            autoLock.cameToForeground()
            assertThat(settled(vault)).isEqualTo(VaultManager.State.Locked)
        }
    }

    @Test
    fun `the delay chosen when leaving applies to the timer`() = runTest {
        opened(seconds = 1800) { setup ->
            setup.delay.value = 60
            setup.autoLock.wentToBackground()
            setup.clock.now += 61_000
            advanceTimeBy(61_000)
            assertThat(settled(setup.vault)).isEqualTo(VaultManager.State.Locked)
        }
    }
}
