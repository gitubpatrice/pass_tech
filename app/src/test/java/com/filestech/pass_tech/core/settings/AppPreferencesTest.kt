package com.filestech.pass_tech.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.filestech.pass_tech.core.model.EntryQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppPreferencesTest {

    @TempDir
    lateinit var dir: File

    private fun withStore(block: suspend (AppPreferences, suspend (Int) -> Unit) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "settings.preferences_pb") })
            block(AppPreferences(store)) { raw -> store.edit { it[intPreferencesKey("auto_lock_seconds")] = raw } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the auto-lock delay is 5 minutes until chosen, then the choice`() = withStore { preferences, _ ->
        assertThat(preferences.autoLockSeconds.first()).isEqualTo(300)
        preferences.setAutoLockSeconds(AppPreferences.NEVER)
        assertThat(preferences.autoLockSeconds.first()).isEqualTo(AppPreferences.NEVER)
        preferences.setAutoLockSeconds(0)
        assertThat(preferences.autoLockSeconds.first()).isEqualTo(0)
    }

    @Test
    fun `a stored delay that is not a choice reads as the default, never as never`() = withStore { preferences, writeRaw ->
        for (raw in listOf(-2, 1, 86_400, Int.MIN_VALUE)) {
            writeRaw(raw)
            assertThat(preferences.autoLockSeconds.first()).isEqualTo(AppPreferences.AUTO_LOCK_DEFAULT)
        }
    }

    @Test
    fun `the sort is kept, and starts as recent`() = withStore { preferences, _ ->
        assertThat(preferences.sortMode.first()).isEqualTo(EntryQuery.Sort.RECENT)
        preferences.setSortMode(EntryQuery.Sort.ALPHA_DESC)
        assertThat(preferences.sortMode.first()).isEqualTo(EntryQuery.Sort.ALPHA_DESC)
    }

    @Test
    fun `only the offered delays can be chosen`() = withStore { preferences, _ ->
        assertThrows<IllegalArgumentException> { runBlocking { preferences.setAutoLockSeconds(-2) } }
        assertThat(preferences.autoLockSeconds.first()).isEqualTo(300)
    }
}
