package com.filestech.pass_tech.di

import android.content.Context
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.state.SystemClockSource
import com.filestech.pass_tech.core.vault.AndroidSlotKeystore
import com.filestech.pass_tech.core.vault.BiometricBinding
import com.filestech.pass_tech.core.vault.SlotKeystore
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The vault and what it stands on. Nothing here is used directly by the UI: it goes through
 * [com.filestech.pass_tech.core.vault.VaultManager], which serialises every call.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VaultModule {

    @Binds
    abstract fun slotKeystore(impl: AndroidSlotKeystore): SlotKeystore

    companion object {

        /** One state file for the whole app: the vault lockout now, the heir lockout later. */
        @Provides
        @Singleton
        fun stateStore(@ApplicationContext context: Context, keystore: SlotKeystore): StateStore =
            StateStore(File(context.filesDir, StateStore.FILE_NAME), keystore)

        @Provides
        @Singleton
        fun vaultRepository(@ApplicationContext context: Context, keystore: SlotKeystore, state: StateStore): VaultRepository =
            VaultRepository(
                files = VaultFiles(context.filesDir),
                keystore = keystore,
                guard = BruteForceGuard.forVault(state, SystemClockSource),
                // Until biometric unlock lands, nothing is ever armed, so there is nothing to purge.
                biometrics = BiometricBinding.NONE,
            )
    }
}
