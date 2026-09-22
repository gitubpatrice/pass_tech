package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.heir.HeirFiles
import com.filestech.pass_tech.core.heir.HeirRepository
import com.filestech.pass_tech.core.heir.HeirState
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.MonotonicWallClock
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.SlotKeystore
import java.io.File

/**
 * The heir side of a vault, wired as the app wires it: the same directory, the same state store, the
 * same clock. Tests that only need a vault take one and never look at it again.
 */
fun heirRepository(dir: File, keystore: SlotKeystore, store: StateStore, clock: Clock, params: KdfParams): HeirRepository =
    HeirRepository(
        files = HeirFiles(dir),
        keystore = keystore,
        guard = BruteForceGuard.forHeir(store, clock),
        state = HeirState(store, MonotonicWallClock(store, clock)),
        params = params,
    )
