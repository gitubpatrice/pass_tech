package com.filestech.pass_tech.core.clipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import com.filestech.pass_tech.core.di.ApplicationScope
import com.filestech.pass_tech.core.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a value out of the vault, marked sensitive, and clears the clipboard once the chosen delay
 * is over (2.7.1: `clipboard_service.dart` and its native half in `MainActivity.kt`).
 *
 * - The clip has no label. 2.7.1 labelled it "Pass Tech", which named the app in clipboard history
 *   and previews even while it was disguised as a calculator.
 * - Two clearings for one delay. A timer of the process, on time to the second, as long as the
 *   process runs. And an ALARM, for when it does not: Android freezes an app soon after it goes to
 *   the background, which is exactly when the owner pastes the value somewhere else, and a frozen
 *   timer only fires once the app comes back. The alarm alone was measured 22 seconds late on a
 *   Galaxy S24, while the message said "cleared in 30s". Whichever comes first clears; the timer
 *   also drops the alarm.
 * - The alarm WAKES the device, and until 3.0.0 it did not. The two clearings then shared one blind
 *   spot: a coroutine `delay` runs on `uptimeMillis`, which stops in deep sleep, and a non-wakeup
 *   alarm is not delivered until something else wakes the phone. Both stopped in the same state, so
 *   a secret could sit in the clipboard well past the second the message named. The comment that
 *   stood here defended the choice with a permission — true of an EXACT alarm, which needs
 *   SCHEDULE_EXACT_ALARM, and not true at all of a waking one, which needs none. A comment arguing
 *   from a constraint that does not apply is very probably what carried the flag through review
 *   (audit of 2026-09-24). Inexact it stays: a few seconds late was always the promise; deep sleep
 *   was not.
 * - Two nets past the delay, because a timer and an alarm both die with the process. The vault
 *   closing clears it ([clearOnLock]), and a new process clears what an old one left ([clearIfLeftBehind]).
 *   2.7.1 cleared on every trip to the background; that is deliberately NOT repeated, because
 *   leaving is how the owner pastes.
 * - One clearing at a time, as 2.7.1: a new copy moves it.
 */
@Singleton
class SecureClipboard @Inject constructor(
    @ApplicationContext private val context: Context,
    preferences: AppPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) : SensitiveClipboard {
    /** Until the setting is read, the default applies: a few milliseconds after the start. */
    private val clearAfterSeconds: StateFlow<Int> =
        preferences.clipboardClearSeconds.stateIn(scope, SharingStarted.Eagerly, AppPreferences.CLIPBOARD_DEFAULT)

    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** The in-process clearing. Touched on the main thread only, where the screens call from. */
    private var timer: Job? = null

    /** [clearIfLeftBehind] runs once for the life of the process. */
    private var leftBehindChecked = false

    override fun copy(text: String): Int? {
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE_EXTRA, true)
            putBoolean(OWNED_EXTRA, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        timer?.cancel()
        val seconds = clearAfterSeconds.value
        return if (seconds > 0) {
            val delayMillis = seconds * MILLIS_PER_SECOND
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMillis, clearing())
            timer = scope.launch(Dispatchers.Main) {
                delay(delayMillis)
                timer = null
                alarms.cancel(clearing())
                clearNow(context)
            }
            seconds
        } else {
            alarms.cancel(clearing())
            null
        }
    }

    override fun clear() {
        timer?.cancel()
        timer = null
        alarms.cancel(clearing())
        clearNow(context)
    }

    /**
     * The vault closed, so what it put in the clipboard goes with it — a net for the case the delay
     * cannot cover, a process killed before its alarm.
     *
     * "Never clear" is honoured: an owner who turned the delay off asked for the value to stay, and
     * a lock is not the place to overrule them. Every other choice is a minute at most, so for them
     * this changes nothing they would notice.
     */
    override fun clearOnLock() {
        if (clearAfterSeconds.value > 0) clear()
    }

    /**
     * Clears a value THIS app left in the clipboard and never got to clear: a force-stop drops the
     * alarm along with the process, and so does a reboot.
     *
     * It reads the clip's DESCRIPTION, never its content, and acts only on [OWNED_EXTRA], the mark
     * [copy] writes. A password the owner copied out of a mail to paste INTO Pass Tech carries no
     * such mark and is not this app's to erase — which is why 2.7.1's "clear whenever the app is
     * resumed" is not what happens here.
     *
     * Once per process. Android 10 and later answer `null` to an app that is not in the foreground,
     * and that is fine: nothing is cleared, which is the safe way to be wrong.
     */
    override fun clearIfLeftBehind() {
        if (leftBehindChecked) return
        leftBehindChecked = true
        if (clearAfterSeconds.value <= 0) return
        val ours = runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.primaryClipDescription?.extras?.getBoolean(OWNED_EXTRA) == true
        }.getOrDefault(false)
        if (ours) clear()
    }

    private fun clearing(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Receives the clearing alarm. Not exported: only this app's own alarm reaches it. */
    class ClearReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clearNow(context)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * Says the clip is this app's, so [clearIfLeftBehind] never erases what somebody else put
         * there. Its own key, not [SENSITIVE_EXTRA]: other apps mark values sensitive too, and this
         * one must mean "Pass Tech wrote this", nothing wider.
         */
        const val OWNED_EXTRA = "com.filestech.pass_tech.CLIP_IS_OURS"

        /** Keeps the value out of clipboard previews. Before Android 13, the key under its legacy name. */
        val SENSITIVE_EXTRA =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }

        fun clearNow(context: Context) {
            val manager = context.getSystemService(ClipboardManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
