package com.filestech.pass_tech.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings store, in memory. No file, no scope of its own, nothing left running on a real thread
 * to come back to a `Main` that the test has already reset — which the CI caught twice.
 *
 * Tests that are about the store itself use the real one over a temporary file.
 */
class InMemoryPreferences : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())
    private val writing = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writing.withLock {
            transform(state.value).also { state.value = it }
        }
}
