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
 *   the background, which is exactly when the user pastes the value somewhere else, and a frozen
 *   timer only fires once the app comes back. The alarm alone was measured 22 seconds late on a
 *   Galaxy S24 (inexact: an exact one needs a permission this app has no claim to), while the
 *   message said "cleared in 30s". Whichever comes first clears; the timer also drops the alarm.
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

    override fun copy(text: String): Int? {
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_EXTRA, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        timer?.cancel()
        val seconds = clearAfterSeconds.value
        return if (seconds > 0) {
            val delayMillis = seconds * MILLIS_PER_SECOND
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMillis, clearing())
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
        alarms.cancel(clearing())
        clearNow(context)
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
