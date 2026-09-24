package com.filestech.pass_tech.core.state

import android.os.SystemClock

/**
 * The two clocks of the app. The lockout delays run on [elapsedMillis], which counts from boot and
 * which nobody can move forward: changing the date in the system settings changes [wallMillis] only.
 */
interface Clock {
    /** Milliseconds since boot, including deep sleep. Restarts from zero at each boot. */
    fun elapsedMillis(): Long

    fun wallMillis(): Long
}

object SystemClockSource : Clock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun wallMillis(): Long = System.currentTimeMillis()
}
