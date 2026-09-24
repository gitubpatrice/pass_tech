package com.filestech.pass_tech.core.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wall-clock time that never goes backwards, and never jumps forward faster than the phone has
 * actually run (design v2 §4, "the clock floor").
 *
 * The heir counts days of inactivity, which uptime alone cannot do: a phone left in a drawer for
 * three months counts three months of nothing, and its boot clock is reset by every restart. So it
 * reads the wall clock — and keeps a floor, persisted, that the returned time never falls below. A
 * date moved back, by hand or by a time server, would otherwise lose the inactivity already counted.
 *
 * ## Forward, which is the dangerous direction
 *
 * A date moved FORWARD used to be taken as it came, and the comment that stood here argued it bought
 * an attacker nothing: "the heir passphrase is still needed, and whoever has it does not need the
 * clock to wait". The second half is wrong, and it is wrong about the most obvious attacker there
 * is — **the heir themselves**. They were handed the passphrase; the countdown is the ONLY thing
 * between them and the vault while its owner is alive. Two minutes with an unlocked phone, Settings
 * › Date, two hundred days forward, and the door opened (audit of 2026-09-24).
 *
 * What tells the two cases apart is the boot clock, which nothing in Settings can move:
 *
 * - **Inside one boot session**, no time can have passed that [Clock.elapsedMillis] did not also
 *   count. Time is therefore credited at the pace of the boot clock, and a wall clock that ran
 *   further than that was moved by somebody. The jump is not refused, it is simply not credited: the
 *   heir's silence still has to be waited out in real time.
 * - **Across a restart**, the boot clock goes back to zero and can say nothing about how long the
 *   phone was off. There the wall clock is the only witness, and it is trusted — which is what keeps
 *   the drawer case working. That is not a way round the rule: coming back from a restart needs the
 *   device's own credential, and the attacker this protects against holds an unlocked phone without
 *   knowing it. Someone who does know it has persistent access and can simply wait.
 *
 * The residual that remains, and it is stated in THREAT_MODEL rather than implied away: a phone
 * whose owner's screen lock is known can still be restarted with the date moved.
 *
 * ## What it does not remember
 *
 * It only remembers what it has READ. Days that pass while the app never runs are not in the floor,
 * so a date moved back before the app ever looks does lose them. That delays the heir, which is the
 * harmless direction.
 *
 * The floor is written when it has moved by more than [FLOOR_STEP_MILLIS], so that reading the time
 * is not a file write every time — and always on a restart, or the boot anchor would stay ahead of
 * the boot clock for ever and every later reading would count as one more restart.
 */
class MonotonicWallClock(private val store: StateStore, private val clock: Clock) {

    fun nowMillis(): Long {
        val state = store.read()
        val floor = state[FLOOR]?.jsonPrimitive?.longOrNull ?: 0L
        val anchor = state[BOOT]?.jsonPrimitive?.longOrNull
        val wall = clock.wallMillis()
        val boot = clock.elapsedMillis()
        // No anchor means either the first reading ever or a state written before this rule existed;
        // a boot clock behind its anchor means the phone restarted. Both are "nothing to compare to".
        val restarted = anchor == null || boot < anchor
        val now = if (restarted) {
            maxOf(wall, floor)
        } else {
            // Never below the floor, never further than the boot clock has come since the anchor.
            maxOf(floor, minOf(wall, floor + (boot - anchor)))
        }
        if (restarted || now - floor > FLOOR_STEP_MILLIS) {
            store.update { JsonObject(it + (FLOOR to JsonPrimitive(now)) + (BOOT to JsonPrimitive(boot))) }
        }
        return now
    }

    private companion object {
        const val FLOOR = "clockFloor"

        /** The boot clock when [FLOOR] was last written. Compared, never shown. */
        const val BOOT = "clockBoot"
        const val FLOOR_STEP_MILLIS = 60L * 60 * 1000
    }
}
