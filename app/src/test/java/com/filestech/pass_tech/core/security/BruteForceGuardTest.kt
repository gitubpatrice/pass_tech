package com.filestech.pass_tech.core.security

import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BruteForceGuardTest {

    @TempDir
    lateinit var dir: File

    private val clock = FakeClock()
    private val store by lazy { StateStore(File(dir, StateStore.FILE_NAME), InMemorySlotKeystore()) }
    private val guard by lazy { BruteForceGuard.forVault(store, clock) }

    private fun fail() {
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Open)
        guard.beginAttempt()
        guard.failed()
    }

    private fun succeed() {
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Open)
        guard.beginAttempt()
        guard.succeeded()
    }

    @Test
    fun `five failures are free, the sixth locks for 30 seconds`() {
        repeat(5) { fail() }
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Open)
        fail()
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Locked(30_000))
        clock.advance(30_000)
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Open)
    }

    @Test
    fun `the delay grows with each further failure`() {
        repeat(6) { fail() }
        clock.advance(30_000)
        fail()
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Locked(60_000))
    }

    @Test
    fun `a success does not erase earlier failures`() {
        // The attack found by the GPT 5.6 review: alternate a guess at the real vault with an
        // opening of the decoy whose password is known. In 2.7.1 each success reset the counter, so
        // the gate below would still be open.
        repeat(5) {
            fail()
            succeed()
        }
        fail()
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Locked(30_000))
    }

    @Test
    fun `a running delay applies to every password, the decoy's included`() {
        repeat(6) { fail() }
        // Knowing a valid password does not get around the delay: the gate is shut for everyone.
        assertThat(guard.gate()).isInstanceOf(BruteForceGuard.Gate.Locked::class.java)
    }

    @Test
    fun `an attempt killed during its derivation stays counted`() {
        repeat(5) { fail() }
        guard.beginAttempt() // the app dies here, before failed() or succeeded()
        guard.beginAttempt()
        guard.failed()
        assertThat(guard.gate()).isInstanceOf(BruteForceGuard.Gate.Locked::class.java)
    }

    @Test
    fun `failures are forgiven with uptime, not with the wall clock`() {
        repeat(5) { fail() }
        clock.wall += 30L * 24 * 3_600_000 // the attacker moves the date a month ahead
        fail()
        assertThat(guard.gate()).isInstanceOf(BruteForceGuard.Gate.Locked::class.java)

        clock.elapsed += 30_000
        guard.gate()
        clock.elapsed += 2L * 2 * 3_600_000 // four hours of uptime forgive two failures
        repeat(2) { fail() }
        assertThat(guard.gate()).isInstanceOf(BruteForceGuard.Gate.Locked::class.java)
    }

    @Test
    fun `a reboot neither shortens nor voids a running delay`() {
        repeat(6) { fail() }
        clock.advance(10_000)
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Locked(20_000))
        clock.reboot()
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Locked(20_000))
        clock.elapsed += 20_000
        assertThat(guard.gate()).isEqualTo(BruteForceGuard.Gate.Open)
    }

    @Test
    fun `rebooting just before a decay step does not credit the time spent before it`() {
        clock.elapsed = 10L * 3_600_000 // the failures happen after ten hours of uptime
        repeat(5) { fail() }
        clock.advance(2L * 3_600_000 - 60_000) // one minute short of forgiving one failure
        clock.reboot() // uptime restarts below the anchor: nothing can be measured across a reboot
        clock.advance(60_000)
        fail()
        assertThat(guard.gate()).isInstanceOf(BruteForceGuard.Gate.Locked::class.java)
    }
}
