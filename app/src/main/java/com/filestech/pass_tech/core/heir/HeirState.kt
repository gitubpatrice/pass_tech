package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.state.MonotonicWallClock
import com.filestech.pass_tech.core.state.StateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The dead man's switch, **one per vault incarnation** (design v2 §8): whether an heir is set up, how
 * many days of silence open the door, and when that vault was last opened.
 *
 * Per generation, never global. 2.7.1 kept these five values global, and its settings screen showed
 * them: from a decoy session the screen therefore described the REAL vault, which gave three
 * distinct oracles — a refusal that named the main vault, an inactivity count that was always 0 on
 * the main vault and anything else on the decoy, and an update that overwrote the real vault's
 * snapshot with a passphrase just handed to an adversary. Scoping the state removes all three at
 * once: there is nothing left to refuse, and nothing of another vault to overwrite.
 *
 * A lost state store disarms the heir, like the fingerprint: never a vault lost, only a feature to
 * set up again.
 */
class HeirState(private val store: StateStore, private val clock: MonotonicWallClock) {

    /**
     * [inactivityDays] counts from the last opening of THIS vault; [due] is what actually opens the
     * heir door, and adds the grace days to the threshold.
     */
    data class Status(val enabled: Boolean, val thresholdDays: Int, val inactivityDays: Int, val due: Boolean)

    fun status(generation: String): Status {
        val state = store.read()
        val enabled = state.bool(generation, ENABLED)
        val threshold = state.int(generation, THRESHOLD) ?: DEFAULT_THRESHOLD_DAYS
        val inactivity = inactivityDays(state, generation)
        return Status(
            enabled = enabled,
            thresholdDays = threshold,
            inactivityDays = inactivity,
            due = enabled && inactivity >= threshold + GRACE_DAYS,
        )
    }

    /** Turns it on, sets the threshold, and starts the count from now. */
    fun enable(generation: String, thresholdDays: Int) {
        require(thresholdDays in THRESHOLD_CHOICES) { "threshold out of range" }
        store.update {
            it.with(
                generation,
                ENABLED to JsonPrimitive(true),
                THRESHOLD to JsonPrimitive(thresholdDays),
                SEEN to JsonPrimitive(clock.nowMillis()),
            )
        }
    }

    fun setThreshold(generation: String, thresholdDays: Int) {
        require(thresholdDays in THRESHOLD_CHOICES) { "threshold out of range" }
        store.update { it.with(generation, THRESHOLD to JsonPrimitive(thresholdDays)) }
    }

    /** Forgets everything about [generation], which is also what a deletion of that vault does. */
    fun forget(generation: String) {
        store.update { state -> JsonObject(state.filterKeys { !it.startsWith(prefix(generation)) }) }
    }

    /** Forgets every generation: a root vault erasing every slot erases vaults it knows nothing about. */
    fun forgetAll() {
        store.update { state -> JsonObject(state.filterKeys { !it.startsWith(PREFIX) }) }
    }

    /** This vault was just opened: the count starts again. Never called by the heir's own reading. */
    fun markActive(generation: String) {
        store.update { it.with(generation, SEEN to JsonPrimitive(clock.nowMillis())) }
    }

    private fun inactivityDays(state: JsonObject, generation: String): Int {
        val seen = state.long(generation, SEEN) ?: return 0
        val elapsed = clock.nowMillis() - seen
        return if (elapsed <= 0) 0 else (elapsed / DAY_MILLIS).toInt()
    }

    private fun JsonObject.bool(generation: String, key: String): Boolean =
        this[prefix(generation) + key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.int(generation: String, key: String): Int? = this[prefix(generation) + key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(generation: String, key: String): Long? = this[prefix(generation) + key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.with(generation: String, vararg values: Pair<String, JsonPrimitive>): JsonObject =
        JsonObject(this + values.map { (key, value) -> prefix(generation) + key to value })

    private fun prefix(generation: String) = "$PREFIX$generation."

    companion object {
        /** 2.7.1's choices, and its default. */
        val THRESHOLD_CHOICES = listOf(30, 60, 90, 180)
        const val DEFAULT_THRESHOLD_DAYS = 90

        /**
         * The days that pass after the threshold before the door opens. The owner who comes back
         * during them resets everything by simply unlocking their vault.
         */
        const val GRACE_DAYS = 7

        private const val PREFIX = "heir."
        private const val ENABLED = "on"
        private const val THRESHOLD = "days"
        private const val SEEN = "seen"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
