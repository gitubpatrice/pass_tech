package com.filestech.pass_tech.core.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wall-clock time that never goes backwards (design v2 §4, "the clock floor").
 *
 * The heir counts days of inactivity, which uptime cannot do: a phone left in a drawer for three
 * months counts three months of nothing. So it reads the wall clock — and keeps a floor, persisted,
 * that the returned time never falls below. A date moved back, by hand or by a time server, would
 * otherwise lose the inactivity already counted.
 *
 * Two limits, both stated as residuals:
 * - it does not stop a date moved FORWARD, and nothing inside the phone can. What that buys an
 *   attacker is nothing on its own: the heir passphrase is still needed, and whoever has it does not
 *   need the clock to wait;
 * - it only remembers what it has READ. Days that pass while the app never runs are not in the
 *   floor, so a date moved back before the app ever looks does lose them. That delays the heir,
 *   which is the harmless direction.
 *
 * The floor is written only when it has moved by more than [FLOOR_STEP_MILLIS], so that reading the
 * time is not a file write every time.
 */
class MonotonicWallClock(private val store: StateStore, private val clock: Clock) {

    fun nowMillis(): Long {
        val floor = store.read()[FLOOR]?.jsonPrimitive?.longOrNull ?: 0L
        val now = maxOf(clock.wallMillis(), floor)
        if (now - floor > FLOOR_STEP_MILLIS) {
            store.update { JsonObject(it + (FLOOR to JsonPrimitive(now))) }
        }
        return now
    }

    private companion object {
        const val FLOOR = "clockFloor"
        const val FLOOR_STEP_MILLIS = 60L * 60 * 1000
    }
}
