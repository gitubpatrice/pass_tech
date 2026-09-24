package com.filestech.pass_tech.di

import android.content.Context
import com.filestech.pass_tech.core.backup.AndroidDocumentStore
import com.filestech.pass_tech.core.backup.DocumentStore
import com.filestech.pass_tech.core.biometric.AndroidBiometricKeys
import com.filestech.pass_tech.core.biometric.BiometricSupport
import com.filestech.pass_tech.core.biometric.StoredBiometricBinding
import com.filestech.pass_tech.core.heir.HeirFiles
import com.filestech.pass_tech.core.heir.HeirRepository
import com.filestech.pass_tech.core.heir.HeirState
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.state.MonotonicWallClock
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

    /** The documents the owner picks, through the system picker: nothing else reaches storage. */
    @Binds
    abstract fun documentStore(impl: AndroidDocumentStore): DocumentStore

    companion object {

        /** One state file for the whole app: the vault lockout now, the heir lockout later. */
        @Provides
        @Singleton
        fun stateStore(@ApplicationContext context: Context, keystore: SlotKeystore): StateStore =
            StateStore(File(context.filesDir, StateStore.FILE_NAME), keystore)

        /** Uptime for the lockout and every countdown, never the wall clock. */
        @Provides
        fun clock(): Clock = SystemClockSource

        /** The one biometric key, `pt_bio`, and what it seals, kept in the state store (design v2 §9). */
        @Provides
        @Singleton
        fun biometricBinding(state: StateStore): BiometricBinding = StoredBiometricBinding(state, AndroidBiometricKeys())

        @Provides
        fun biometricSupport(@ApplicationContext context: Context): BiometricSupport = BiometricSupport.of(context)

        /**
         * The heir side (design v2 §8): its own files, its own delay after a wrong passphrase, and a
         * wall clock that cannot go backwards, since it counts days and not seconds.
         */
        @Provides
        @Singleton
        fun heirRepository(
            @ApplicationContext context: Context,
            keystore: SlotKeystore,
            state: StateStore,
            clock: Clock,
        ): HeirRepository =
            HeirRepository(
                files = HeirFiles(context.filesDir),
                keystore = keystore,
                guard = BruteForceGuard.forHeir(state, clock),
                state = HeirState(state, MonotonicWallClock(state, clock)),
            )

        @Provides
        @Singleton
        fun vaultRepository(
            @ApplicationContext context: Context,
            keystore: SlotKeystore,
            state: StateStore,
            clock: Clock,
            heir: HeirRepository,
            biometrics: BiometricBinding,
        ): VaultRepository =
            VaultRepository(
                files = VaultFiles(context.filesDir),
                keystore = keystore,
                guard = BruteForceGuard.forVault(state, clock),
                heir = heir,
                biometrics = biometrics,
            )
    }
}
