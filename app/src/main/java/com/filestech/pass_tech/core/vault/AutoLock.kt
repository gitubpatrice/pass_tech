package com.filestech.pass_tech.core.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.filestech.pass_tech.core.di.ApplicationScope
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the vault once the app has been left for longer than the chosen delay. Observes the whole
 * process (`ProcessLifecycleOwner`), not one screen.
 *
 * 2.7.1 only checked on the way back, so the key stayed in memory for as long as the app sat in the
 * background. Here the vault also locks **in the background** when the delay is over; the check on the
 * way back stays, and is the one that counts:
 * - The timer may run late, or not at all: coroutine delays do not count deep sleep, and Android
 *   freezes cached apps. It only measures the time on [Clock.elapsedMillis], which counts deep sleep,
 *   and checks again every [CHECK_MILLIS], so it catches up as soon as the phone wakes up.
 * - On the way back, the vault is locked before any screen shows it: [locking] turns on at once, in
 *   `onStart`, before the first frame, and stays on until the vault is locked.
 */
@Singleton
class AutoLock internal constructor(
    private val vault: VaultManager,
    private val delaySeconds: StateFlow<Int>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    /** Until the setting is read, the default applies: a few milliseconds after the start. */
    @Inject
    constructor(
        vault: VaultManager,
        preferences: AppPreferences,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        vault,
        preferences.autoLockSeconds.stateIn(scope, SharingStarted.Eagerly, AppPreferences.AUTO_LOCK_DEFAULT),
        clock,
        scope,
    )

    private val mutableLocking = MutableStateFlow(false)

    /** True while a lock found due on the way back is running: the screens show nothing of the vault. */
    val locking: StateFlow<Boolean> = mutableLocking.asStateFlow()

    /** When the app was left, on [Clock.elapsedMillis]. `null` while in the foreground. */
    private var leftAt: Long? = null
    private var timer: Job? = null

    /** When a system screen was announced, on [Clock.elapsedMillis]; `null` when none is expected. */
    private var systemScreenAt: Long? = null

    /** Whether the trip the app is on was announced. */
    private var systemScreenTrip = false

    /**
     * Announces that the system is about to take the screen, at the owner's request: the file picker.
     * The app leaves the foreground for a moment it asked for, so the vault does not lock at once, as it
     * would with the immediate delay (2.7.1 locks there, and the import is lost with it).
     *
     * It buys a short delay, never an open vault: past [SYSTEM_SCREEN_GRACE_MILLIS] away, a lock that was
     * due happens all the same. And it only covers the very next trip, within [ANNOUNCE_GRACE_MILLIS]:
     * a picker that never opens protects nothing.
     */
    fun systemScreenExpected() {
        systemScreenAt = clock.elapsedMillis()
    }

    override fun onStop(owner: LifecycleOwner) = wentToBackground()

    override fun onStart(owner: LifecycleOwner) = cameToForeground()

    internal fun wentToBackground() {
        // First, and synchronously: a later timestamp would lock later (2.7.1, audit of 2026-08-03).
        val at = clock.elapsedMillis()
        leftAt = at
        timer?.cancel()
        val announced = systemScreenAt
        systemScreenAt = null
        systemScreenTrip = announced != null && at - announced <= ANNOUNCE_GRACE_MILLIS
        // No timer on an announced trip: the picker would otherwise lock the vault behind it.
        if (systemScreenTrip) return
        val seconds = delaySeconds.value
        if (seconds == AppPreferences.NEVER) return
        timer = scope.launch {
            val due = at + seconds * MILLIS_PER_SECOND
            var left = due - clock.elapsedMillis()
            while (left > 0) {
                delay(left.coerceAtMost(CHECK_MILLIS))
                left = due - clock.elapsedMillis()
            }
            vault.lock()
        }
    }

    internal fun cameToForeground() {
        timer?.cancel()
        timer = null
        val at = leftAt ?: return
        leftAt = null
        val announced = systemScreenTrip
        systemScreenTrip = false
        // A heir reading closes at EVERY return, whatever the delay says and whatever was announced
        // (2.7.1): it is someone else's phone, read once, and the snapshot has no reason to survive a
        // trip to another app.
        if (vault.state.value is VaultManager.State.Heir) {
            scope.launch { vault.lock() }
            return
        }
        // Back from a file picker: a lock that was due waits, but only for a moment.
        if (announced && clock.elapsedMillis() - at <= SYSTEM_SCREEN_GRACE_MILLIS) return
        if (isDue(at)) {
            mutableLocking.value = true
            scope.launch {
                try {
                    vault.lock()
                } finally {
                    mutableLocking.value = false
                }
            }
        }
    }

    private fun isDue(leftAt: Long): Boolean {
        val seconds = delaySeconds.value
        if (seconds == AppPreferences.NEVER) return false
        val away = clock.elapsedMillis() - leftAt
        // The boot clock cannot go back within one process. If it seems to, nothing is known: lock.
        return away < 0 || away >= seconds * MILLIS_PER_SECOND
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** How often the background timer looks at the clock again. */
        const val CHECK_MILLIS = 15_000L

        /** How long an announced system screen may keep a due lock waiting. */
        const val SYSTEM_SCREEN_GRACE_MILLIS = 2 * 60 * 1_000L

        /** How long an announcement lasts before the next trip counts as an ordinary one. */
        const val ANNOUNCE_GRACE_MILLIS = 30 * 1_000L
    }
}
