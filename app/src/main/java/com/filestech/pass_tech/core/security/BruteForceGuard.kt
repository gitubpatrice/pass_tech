package com.filestech.pass_tech.core.security

import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.StateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Throttles password guesses (design v2 §4).
 *
 * A "debt" of failed attempts, persisted. Every attempt is counted BEFORE the derivation runs, so
 * killing the app during it does not give the attempt back. A success only cancels ITS OWN
 * attempt: it never erases earlier failures.
 *
 * That last rule is the point. In 2.7.1 a success reset the counter, and with the decoy password in
 * hand an attacker could alternate one guess at the real vault with one decoy unlock: never a single
 * delay (found by the GPT 5.6 review of the vault design).
 *
 * The debt decays by one every [decayMillis] of UPTIME. Uptime is monotonic and cannot be advanced
 * from the system settings, unlike the wall clock. A reboot restarts the uptime clock: the decay then
 * starts over from zero, which only ever errs on the side of caution.
 *
 * The lock itself is a remaining duration anchored on uptime and persisted, so a reboot does not
 * shorten it either (same principle as 2.7.1).
 *
 * Every method throws [com.filestech.pass_tech.core.vault.KeystoreUnavailableException] when the state
 * cannot be read because the Keystore did not answer. Nothing is then reset nor written: the caller
 * answers "retry", and the debt is still there next time.
 */
class BruteForceGuard(
    private val store: StateStore,
    private val clock: Clock,
    private val prefix: String,
    private val freeAttempts: Int,
    private val lockSchedule: List<Long>,
    private val decayMillis: Long,
) {

    sealed interface Gate {
        data object Open : Gate

        data class Locked(val remainingMillis: Long) : Gate
    }

    /**
     * Whether an attempt may be made now. Applies the decay, and records how much of the delay is
     * left: after a reboot the delay resumes from there instead of starting over or vanishing.
     */
    fun gate(): Gate {
        var remaining = 0L
        store.update { state ->
            val now = clock.elapsedMillis()
            val decayed = decay(state, now)
            val lockLeft = decayed.long(LOCK_LEFT)
            val lockAnchor = decayed.long(LOCK_ANCHOR)
            remaining = when {
                lockLeft <= 0 -> 0
                // Uptime went backwards: the device rebooted. Nothing has elapsed since the last record.
                now < lockAnchor -> lockLeft
                else -> (lockLeft - (now - lockAnchor)).coerceAtLeast(0)
            }
            decayed.with(LOCK_LEFT to remaining, LOCK_ANCHOR to now)
        }
        return if (remaining > 0) Gate.Locked(remaining) else Gate.Open
    }

    /** Counts an attempt before it runs. Call only after [gate] said [Gate.Open]. */
    fun beginAttempt() {
        store.update { it.with(DEBT to it.int(DEBT) + 1) }
    }

    /** The attempt succeeded: cancel it, and only it. */
    fun succeeded() {
        store.update { it.with(DEBT to (it.int(DEBT) - 1).coerceAtLeast(0)) }
    }

    /** The attempt failed: its count stays, and past the free attempts a delay starts. */
    fun failed() {
        store.update { state ->
            val debt = state.int(DEBT)
            if (debt <= freeAttempts) {
                state
            } else {
                val delay = lockSchedule[(debt - freeAttempts - 1).coerceAtMost(lockSchedule.lastIndex)]
                state.with(LOCK_LEFT to delay, LOCK_ANCHOR to clock.elapsedMillis())
            }
        }
    }

    private fun decay(state: JsonObject, now: Long): JsonObject {
        val anchor = state.long(DECAY_ANCHOR, default = -1)
        return when {
            anchor < 0 || now < anchor -> state.with(DECAY_ANCHOR to now)
            else -> {
                val steps = (now - anchor) / decayMillis
                if (steps == 0L) {
                    state
                } else {
                    val debt = (state.int(DEBT) - steps).coerceAtLeast(0).toInt()
                    state.with(DEBT to debt, DECAY_ANCHOR to anchor + steps * decayMillis)
                }
            }
        }
    }

    private fun JsonObject.int(key: String): Int = this["$prefix.$key"]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.long(key: String, default: Long = 0): Long = this["$prefix.$key"]?.jsonPrimitive?.longOrNull ?: default

    private fun JsonObject.with(vararg values: Pair<String, Number>): JsonObject =
        JsonObject(this + values.map { (key, value) -> "$prefix.$key" to JsonPrimitive(value) })

    companion object {
        private const val DEBT = "debt"
        private const val DECAY_ANCHOR = "decayAnchor"
        private const val LOCK_LEFT = "lockLeft"
        private const val LOCK_ANCHOR = "lockAnchor"

        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE

        /**
         * Master password: 5 free attempts, then 30 s, 1 min, 5 min, 15 min, 30 min (as 2.7.1).
         *
         * Visible because the About screen states these two out loud, and reads them from here rather
         * than from a sentence someone typed: see `core/about/AppFacts`.
         */
        const val VAULT_FREE_ATTEMPTS = 5
        val VAULT_LOCKS = listOf(30 * SECOND, MINUTE, 5 * MINUTE, 15 * MINUTE, 30 * MINUTE)

        /** Heir passphrase: a delay from the first failure, 1 s doubling up to 16 s (as 2.7.1). */
        private val HEIR_LOCKS = listOf(SECOND, 2 * SECOND, 4 * SECOND, 8 * SECOND, 16 * SECOND)

        /** One failure forgiven per two hours of uptime. */
        private const val DECAY = 2 * HOUR

        fun forVault(store: StateStore, clock: Clock) = BruteForceGuard(
            store = store,
            clock = clock,
            prefix = "vault",
            freeAttempts = VAULT_FREE_ATTEMPTS,
            lockSchedule = VAULT_LOCKS,
            decayMillis = DECAY,
        )

        fun forHeir(store: StateStore, clock: Clock) = BruteForceGuard(
            store = store,
            clock = clock,
            prefix = "heir",
            freeAttempts = 0,
            lockSchedule = HEIR_LOCKS,
            decayMillis = DECAY,
        )
    }
}
