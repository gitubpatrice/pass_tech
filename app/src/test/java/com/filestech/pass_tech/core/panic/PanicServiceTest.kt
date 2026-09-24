package com.filestech.pass_tech.core.panic

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.phishing.AntiPhishing
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.BiometricBinding
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.testing.FakeClipboard
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FakeLauncherDisguise
import com.filestech.pass_tech.testing.FakePhishingComponent
import com.filestech.pass_tech.testing.FixedDomain
import com.filestech.pass_tech.testing.InMemoryPreferences
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Panic mode: what it must do, and what it must leave alone. Its steps are independent on purpose —
 * there is no second chance and no screen left to report a failure to.
 */
class PanicServiceTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val clipboard = FakeClipboard()
    private val disguise = FakeLauncherDisguise()
    private val preferences = AppPreferences(InMemoryPreferences())

    /** Listed and granted at the start of every test: what a phone whose owner asked for it looks like. */
    private val phishing = FakePhishingComponent(listed = true, granted = true)
    private val domain = FixedDomain("mabanque.fr")
    private val biometrics = CountingBiometrics()
    private lateinit var vault: VaultManager
    private lateinit var guard: BruteForceGuard
    private lateinit var panic: PanicService

    private val owner = "owner password"

    /** Counts the purges, and can play a Keystore that does not answer. */
    private class CountingBiometrics : BiometricBinding by BiometricBinding.NONE {
        var purges = 0
        var unavailable = false

        override fun purge() {
            purges++
            if (unavailable) throw KeystoreUnavailableException()
        }
    }

    @BeforeEach
    fun setUp() {
        val clock = FakeClock()
        val state = StateStore(File(dir, StateStore.FILE_NAME), keystore)
        guard = BruteForceGuard.forVault(state, clock)
        val files = VaultFiles(File(dir, "vault").apply { mkdirs() })
        val heir = heirRepository(dir, keystore, state, clock, fastParams)
        val repository = VaultRepository(files, keystore, guard, heir, biometrics, fastParams)
        vault = VaultManager(repository, domain, clipboard, Dispatchers.Unconfined)
        panic = PanicService(vault, clipboard, AntiPhishing(preferences, phishing, domain), disguise)
    }

    private suspend fun open() {
        assertThat(vault.openOrCreate(owner.encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
    }

    @Test
    fun `it locks, empties the clipboard, disarms the fingerprint and disguises the launcher`() = runTest {
        open()
        clipboard.copy("a password")

        panic.panic()

        assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
        assertThat(clipboard.content).isNull()
        assertThat(clipboard.clears).isEqualTo(1)
        assertThat(biometrics.purges).isAtLeast(1)
        assertThat(panic.disguised()).isTrue()
    }

    @Test
    fun `nothing is deleted, and the master password still opens the vault`() = runTest {
        open()
        panic.panic()
        assertThat(vault.unlock(owner.encodeToByteArray())).isEqualTo(VaultManager.UnlockOutcome.Opened)
        assertThat(vault.entryMode()).isEqualTo(VaultRepository.EntryMode.UNLOCK)
    }

    /**
     * 2.7.1 cleared the failed attempts here, so that the state after a panic looked like a fresh
     * boot. Anyone holding a decoy could then clear a running lockout at will, which is the flaw the
     * design records against 2.7.1 (§12), reopened by another door.
     */
    @Test
    fun `a running lockout survives, so panic is not a way to clear it`() = runTest {
        open()
        repeat(LOCKOUT_ATTEMPTS) { vault.unlock("wrong".encodeToByteArray()) }
        val before = vault.lockoutRemainingMillis()
        assertThat(before).isGreaterThan(0L)

        panic.panic()

        assertThat(vault.lockoutRemainingMillis()).isAtLeast(before - SLACK_MILLIS)
    }

    @Test
    fun `a step that fails does not stop the ones after it`() = runTest {
        open()
        clipboard.copy("a password")
        biometrics.unavailable = true

        panic.panic()

        assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
        assertThat(clipboard.content).isNull()
        // The fingerprint refused to be disarmed, and the launcher is disguised all the same.
        assertThat(panic.disguised()).isTrue()
    }

    @Test
    fun `a launcher that will not answer is reported as unknown, never as not disguised`() = runTest {
        open()
        disguise.answers = false
        panic.panic()
        assertThat(panic.disguised()).isNull()
        assertThat(panic.reveal()).isFalse()
    }

    @Test
    fun `revealing puts the name back`() = runTest {
        open()
        panic.panic()
        assertThat(panic.reveal()).isTrue()
        assertThat(panic.disguised()).isFalse()
    }

    @Test
    fun `it works with no vault open, which is the state it aims for`() = runTest {
        panic.panic()
        assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
        assertThat(panic.disguised()).isTrue()
    }

    @Test
    fun `no slot file is written`() = runTest {
        open()
        val before = Slot.entries.map { File(dir, "vault/${it.vaultFileName}").readText() }
        panic.panic()
        assertThat(Slot.entries.map { File(dir, "vault/${it.vaultFileName}").readText() }).isEqualTo(before)
    }

    @Test
    fun `it withdraws the anti-phishing service, which carries the app's name`() = runTest {
        open()
        preferences.setAntiPhishing(true)
        panic.panic()
        // Settings > Accessibility would otherwise say "Pass Tech" on a phone whose launcher has
        // just been made to say "Calculator".
        assertThat(phishing.listed).isFalse()
        assertThat(phishing.granted).isFalse()
        assertThat(preferences.antiPhishing.first()).isFalse()
    }

    @Test
    fun `the site the browser was on is forgotten`() = runTest {
        open()
        panic.panic()
        assertThat(domain.host).isNull()
    }

    @Test
    fun `a system that refuses to withdraw the service still leaves the launcher disguised`() = runTest {
        open()
        preferences.setAntiPhishing(true)
        phishing.answers = false
        panic.panic()
        assertThat(panic.disguised()).isTrue()
        assertThat(vault.state.value).isEqualTo(VaultManager.State.Locked)
    }

    @Test
    fun `revealing does not put the anti-phishing service back`() = runTest {
        open()
        preferences.setAntiPhishing(true)
        panic.panic()
        panic.reveal()
        // The system dropped its grant with the component: listing it again would put the app's name
        // back under the accessibility settings while nothing was watching anything.
        assertThat(phishing.listed).isFalse()
        assertThat(preferences.antiPhishing.first()).isFalse()
    }

    private companion object {
        /** Enough wrong passwords to start a lockout, whatever the exact threshold is. */
        const val LOCKOUT_ATTEMPTS = 8
        const val SLACK_MILLIS = 1_000L
    }
}
