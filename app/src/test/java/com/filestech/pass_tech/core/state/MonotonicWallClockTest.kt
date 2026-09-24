package com.filestech.pass_tech.core.state

import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The clock the heir's countdown runs on. It had no test at all until the audit of 2026-09-24, and
 * the heir's own tests could not have caught this: `FakeClock.advance` moves BOTH clocks, so every
 * one of them passed identically in a world where days go by and in a world where somebody moved the
 * date. These set the two apart on purpose.
 */
class MonotonicWallClockTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private lateinit var clock: FakeClock
    private lateinit var monotonic: MonotonicWallClock

    private val day = 24L * 60 * 60 * 1000

    @BeforeEach
    fun setUp() {
        clock = FakeClock()
        monotonic = MonotonicWallClock(StateStore(File(dir, StateStore.FILE_NAME), keystore), clock)
    }

    @Test
    fun `time that really passes is counted in full`() {
        val start = monotonic.nowMillis()
        clock.advance(30 * day)
        assertThat(monotonic.nowMillis()).isEqualTo(start + 30 * day)
    }

    @Test
    fun `a date moved back keeps the time already counted`() {
        val start = monotonic.nowMillis()
        clock.advance(30 * day)
        monotonic.nowMillis()
        clock.wall -= 60 * day
        assertThat(monotonic.nowMillis()).isEqualTo(start + 30 * day)
    }

    /**
     * The constat of 2026-09-24: two minutes with an unlocked phone, Settings › Date, two hundred
     * days forward. The boot clock did not move, so no time is credited — the phone has been running
     * the whole time and cannot have missed anything.
     */
    @Test
    fun `a date moved forward inside one session buys nothing`() {
        val start = monotonic.nowMillis()
        clock.wall += 200 * day
        assertThat(monotonic.nowMillis()).isEqualTo(start)
    }

    @Test
    fun `after a jump, time is still credited at the pace of the boot clock`() {
        val start = monotonic.nowMillis()
        clock.wall += 200 * day
        monotonic.nowMillis()
        // A real hour goes by. One hour is credited, not two hundred days and one hour.
        clock.advance(60 * 60 * 1000)
        assertThat(monotonic.nowMillis()).isEqualTo(start + 60 * 60 * 1000)
    }

    /**
     * The drawer case, which the rule must not break: a phone switched off for months says nothing
     * with its boot clock, so the wall clock is the only witness and is believed. Coming back from a
     * restart needs the device's own credential, which is what keeps this from being a way round.
     */
    @Test
    fun `a phone that was off comes back with its months counted`() {
        val start = monotonic.nowMillis()
        clock.reboot()
        clock.wall += 200 * day
        assertThat(monotonic.nowMillis()).isEqualTo(start + 200 * day)
    }

    @Test
    fun `a restart re-anchors, so the session after it is measured again`() {
        monotonic.nowMillis()
        clock.reboot()
        val afterReboot = monotonic.nowMillis()
        // Still inside the new session: a jump buys nothing here either.
        clock.wall += 90 * day
        assertThat(monotonic.nowMillis()).isEqualTo(afterReboot)
    }

    @Test
    fun `reading the time twice in a row does not move it`() {
        val first = monotonic.nowMillis()
        assertThat(monotonic.nowMillis()).isEqualTo(first)
    }
}
