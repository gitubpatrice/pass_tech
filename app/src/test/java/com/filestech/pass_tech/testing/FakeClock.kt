package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.state.Clock

/** A clock the test moves by hand. [reboot] restarts the uptime clock, as a real reboot does. */
class FakeClock(var elapsed: Long = 1_000_000L, var wall: Long = 1_700_000_000_000L) : Clock {
    override fun elapsedMillis(): Long = elapsed

    override fun wallMillis(): Long = wall

    fun advance(millis: Long) {
        elapsed += millis
        wall += millis
    }

    fun reboot(uptimeAfterBoot: Long = 10_000L) {
        elapsed = uptimeAfterBoot
    }
}
