package com.filestech.pass_tech.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain app settings, in a DataStore: nothing secret and nothing that says whether a vault exists.
 * Security state (lockout, heir, biometric binding) lives in the encrypted state store instead.
 * A damaged file reads as the defaults.
 */
@Singleton
class AppPreferences @Inject constructor(private val store: DataStore<Preferences>) {

    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    /** Whether the first-launch splash was seen (2.7.1: `splash_shown_v1`). */
    val splashShown: Flow<Boolean> = data.map { it[SPLASH_SHOWN] ?: false }

    suspend fun markSplashShown() {
        store.edit { it[SPLASH_SHOWN] = true }
    }

    /**
     * How long the vault stays open once the app is left, in seconds: one of [AUTO_LOCK_CHOICES]. A value
     * that is not one of them (a damaged or hand-edited file) reads as the default, never as [NEVER].
     */
    val autoLockSeconds: Flow<Int> = data.map { prefs -> prefs[AUTO_LOCK]?.takeIf { it in AUTO_LOCK_CHOICES } ?: AUTO_LOCK_DEFAULT }

    suspend fun setAutoLockSeconds(seconds: Int) {
        require(seconds in AUTO_LOCK_CHOICES) { "Not an auto-lock choice: $seconds" }
        store.edit { it[AUTO_LOCK] = seconds }
    }

    companion object {
        /** The vault never locks by itself. */
        const val NEVER = -1

        /** 2.7.1's choices: immediately, 1, 5, 15 or 30 minutes, never. */
        val AUTO_LOCK_CHOICES = listOf(0, 60, 300, 900, 1800, NEVER)

        /** 2.7.1's default: 5 minutes. */
        const val AUTO_LOCK_DEFAULT = 300

        private val SPLASH_SHOWN = booleanPreferencesKey("splash_shown")
        private val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
    }
}
