package com.filestech.pass_tech.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

    private companion object {
        val SPLASH_SHOWN = booleanPreferencesKey("splash_shown")
    }
}
