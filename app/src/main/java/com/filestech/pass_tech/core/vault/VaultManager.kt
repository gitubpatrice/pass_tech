package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.backup.PlainExport
import com.filestech.pass_tech.core.backup.PtbakCodec
import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.di.IoDispatcher
import com.filestech.pass_tech.core.heir.HeirRepository
import com.filestech.pass_tech.core.heir.HeirState
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only owner of the open vault, and the only way the rest of the app reaches it.
 *
 * - **One operation at a time**, on the IO dispatcher, behind [mutex]. [VaultRepository] is not
 *   thread-safe, and the key of the open session is only ever wiped under that same lock: otherwise
 *   [lock] could zero the key while a save is sealing the file with it, and the vault would be written
 *   under a null key, which no password opens.
 * - **The session never leaves this class.** Callers get outcomes and observe [state]; the key stays
 *   here until [lock] or [deleteData] wipes it.
 * - **Every password handed in is wiped once used**, also when the call is cancelled before it ran.
 *   Never earlier: the operations have no suspension point, so a call only returns, cancelled or not,
 *   once the vault has finished reading the password.
 * - A cancelled caller loses nothing: the operation runs to its end and its effect is recorded here
 *   before the call returns. A vault it opened stays open until [lock].
 * - [updateEntries] and [deleteData] throw [KeystoreUnavailableException] when the secure hardware did
 *   not answer: nothing was written, and the vault stays open. The other operations say it in their result.
 */
// Every public function is one vault operation behind the same lock, which is the point of this class:
// split across classes, each half would need the lock and the session of the other.
@Suppress("TooManyFunctions")
@Singleton
class VaultManager @Inject constructor(
    private val repository: VaultRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    sealed interface State {
        data object Locked : State

        /**
         * [hasDecoy]: this vault has a decoy, or cannot rule one out, so it cannot create another one.
         * [biometrics]: which vault a fingerprint opens; [BiometricStatus.OFF] as well if the secure
         * hardware did not answer, which at worst offers to enable it again.
         */
        data class Open(
            val entries: List<Entry>,
            val hasDecoy: Boolean,
            val biometrics: BiometricStatus = BiometricStatus.OFF,
        ) : State

        /**
         * The read-only view an heir gets (design v2 §8). No vault is open: these entries come from a
         * snapshot, and nothing here can write one.
         */
        data class Heir(val entries: List<Entry>) : State
    }

    sealed interface UnlockOutcome {
        data object Opened : UnlockOutcome

        data object WrongPassword : UnlockOutcome

        data class Locked(val remainingMillis: Long) : UnlockOutcome

        /** The secure hardware did not answer: say "retry", never "wrong password". */
        data object KeystoreUnavailable : UnlockOutcome
    }

    sealed interface CreateOutcome {
        /** The password opened an existing vault: nothing was created. */
        data object Opened : CreateOutcome

        data object Created : CreateOutcome

        /** No slot is provably free. Deliberately says nothing more. */
        data object Impossible : CreateOutcome

        data class Locked(val remainingMillis: Long) : CreateOutcome

        data object KeystoreUnavailable : CreateOutcome
    }

    sealed interface DecoyOutcome {
        data object Created : DecoyOutcome

        /** The password opens an existing vault. Same answer as "the same as the current password". */
        data object PasswordRefused : DecoyOutcome

        data object Impossible : DecoyOutcome

        data class Locked(val remainingMillis: Long) : DecoyOutcome

        /** The vault was locked: reopening it completes or drops an interrupted creation. */
        data object KeystoreUnavailable : DecoyOutcome
    }

    sealed interface DecoyDeleteOutcome {
        data object Deleted : DecoyDeleteOutcome

        /** There was no decoy left to delete: the screen simply shows the tile again. */
        data object NotConfigured : DecoyDeleteOutcome

        /** Nothing was written: a decoy may exist that this call could not prove. */
        data object KeystoreUnavailable : DecoyDeleteOutcome
    }

    sealed interface ChangeOutcome {
        /** [biometricsDisarmed]: a fingerprint opened this vault, and must be enabled again (2.7.1 says so). */
        data class Changed(val biometricsDisarmed: Boolean) : ChangeOutcome

        data object WrongCurrentPassword : ChangeOutcome

        data object PasswordRefused : ChangeOutcome

        data class Locked(val remainingMillis: Long) : ChangeOutcome

        data object KeystoreUnavailable : ChangeOutcome
    }

    class ImportOutcome(val added: Int, val skipped: Int)

    sealed interface ArmStart {
        /** The system prompt authenticates [cipher], then [armBiometrics] takes it. */
        class Ready(val cipher: Cipher) : ArmStart

        /** This vault has a decoy, or cannot rule one out (design v2 §9). */
        data object Refused : ArmStart

        data object KeystoreUnavailable : ArmStart
    }

    sealed interface ArmOutcome {
        data object Armed : ArmOutcome

        data object Refused : ArmOutcome

        data object KeystoreUnavailable : ArmOutcome
    }

    sealed interface BiometricStart {
        /** The system prompt authenticates [cipher], then [unlockWithBiometrics] takes it. */
        class Ready(val cipher: Cipher) : BiometricStart

        data object NotArmed : BiometricStart

        /** A fingerprint was enrolled, or every one removed: biometrics is now disarmed (2.7.1 says so). */
        data object Invalidated : BiometricStart

        data object KeystoreUnavailable : BiometricStart
    }

    sealed interface BiometricOutcome {
        data object Opened : BiometricOutcome

        data class Locked(val remainingMillis: Long) : BiometricOutcome

        /** What was armed opens no vault any more: now disarmed. */
        data object Disarmed : BiometricOutcome

        data object KeystoreUnavailable : BiometricOutcome
    }

    sealed interface HeirUnlockOutcome {
        data object Opened : HeirUnlockOutcome

        /** A wrong passphrase, or a vault still answering. The same words for both (design v2 §8). */
        data object Refused : HeirUnlockOutcome

        data class Locked(val remainingMillis: Long) : HeirUnlockOutcome

        data object KeystoreUnavailable : HeirUnlockOutcome
    }

    private val mutex = Mutex()

    /** Read and written only under [mutex]. */
    private var session: VaultSession? = null

    /** The entries of a heir reading, if one is open. Read and written only under [mutex]. */
    private var heirEntries: List<Entry>? = null

    private val mutableState = MutableStateFlow<State>(State.Locked)
    val state: StateFlow<State> = mutableState.asStateFlow()

    suspend fun entryMode(): VaultRepository.EntryMode = serialized { repository.entryMode() }

    /** See [VaultRepository.lockoutRemainingMillis]. */
    suspend fun lockoutRemainingMillis(): Long = serialized { repository.lockoutRemainingMillis() }

    suspend fun unlock(password: ByteArray): UnlockOutcome =
        serialized(password) {
            when (val result = repository.unlock(password)) {
                is VaultRepository.UnlockResult.Opened -> {
                    open(result.session)
                    UnlockOutcome.Opened
                }
                VaultRepository.UnlockResult.WrongPassword -> UnlockOutcome.WrongPassword
                is VaultRepository.UnlockResult.Locked -> UnlockOutcome.Locked(result.remainingMillis)
                VaultRepository.UnlockResult.KeystoreUnavailable -> UnlockOutcome.KeystoreUnavailable
            }
        }

    /** The creation form: see [VaultRepository.openOrCreate]. */
    suspend fun openOrCreate(password: ByteArray): CreateOutcome =
        serialized(password) {
            when (val result = repository.openOrCreate(password)) {
                is VaultRepository.CreateResult.Opened -> {
                    open(result.session)
                    CreateOutcome.Opened
                }
                is VaultRepository.CreateResult.Created -> {
                    open(result.session)
                    CreateOutcome.Created
                }
                VaultRepository.CreateResult.Impossible -> CreateOutcome.Impossible
                is VaultRepository.CreateResult.Locked -> CreateOutcome.Locked(result.remainingMillis)
                VaultRepository.CreateResult.KeystoreUnavailable -> CreateOutcome.KeystoreUnavailable
            }
        }

    /**
     * Applies [transform] to the entries of the open vault and saves the result. [transform] runs under
     * the lock, against the entries as they are at that moment, so two edits never overwrite each other.
     *
     * Returns `false`, saving nothing, if no vault is open (locked in the meantime).
     */
    suspend fun updateEntries(transform: (List<Entry>) -> List<Entry>): Boolean =
        serialized {
            val current = session
            if (current != null) advance(repository.save(current, transform(current.entries)))
            current != null
        }

    /** Returns `null`, doing nothing, if no vault is open. Only offered when [State.Open.hasDecoy] is false. */
    suspend fun configureDecoy(password: ByteArray): DecoyOutcome? =
        serialized(password) {
            session?.let { current ->
                when (val result = repository.configureDecoy(current, password)) {
                    is VaultRepository.DecoyResult.Created -> {
                        advance(result.parent)
                        DecoyOutcome.Created
                    }
                    VaultRepository.DecoyResult.PasswordRefused -> DecoyOutcome.PasswordRefused
                    VaultRepository.DecoyResult.Impossible -> DecoyOutcome.Impossible
                    is VaultRepository.DecoyResult.Locked -> DecoyOutcome.Locked(result.remainingMillis)
                    VaultRepository.DecoyResult.KeystoreUnavailable -> {
                        // The creation may have stopped after its journal, and this session does not know it:
                        // saving from it would erase the journal. Lock; the next opening settles it.
                        close()
                        DecoyOutcome.KeystoreUnavailable
                    }
                }
            }
        }

    /** Returns `null`, doing nothing, if no vault is open. See [VaultRepository.deleteDecoy]. */
    suspend fun deleteDecoy(): DecoyDeleteOutcome? =
        serialized {
            session?.let { current ->
                when (val result = repository.deleteDecoy(current)) {
                    is VaultRepository.DecoyDeleteResult.Deleted -> {
                        advance(result.parent)
                        DecoyDeleteOutcome.Deleted
                    }
                    // Whatever the screen showed, the state it reads is republished: the tile follows.
                    VaultRepository.DecoyDeleteResult.NotConfigured -> {
                        publish()
                        DecoyDeleteOutcome.NotConfigured
                    }
                    VaultRepository.DecoyDeleteResult.KeystoreUnavailable -> DecoyDeleteOutcome.KeystoreUnavailable
                }
            }
        }

    /** Returns `null` if no vault is open. */
    suspend fun verifyPassword(password: ByteArray): VaultRepository.CheckResult? =
        serialized(password) { session?.let { repository.verifyPassword(it, password) } }

    /** Returns `null`, doing nothing, if no vault is open. */
    suspend fun changePassword(current: ByteArray, new: ByteArray): ChangeOutcome? =
        serialized(current, new) {
            session?.let { vault ->
                when (val result = repository.changePassword(vault, current, new)) {
                    // The repository closed the previous session: its key is already wiped.
                    is VaultRepository.ChangeResult.Changed -> {
                        advance(result.session)
                        ChangeOutcome.Changed(result.biometricsDisarmed)
                    }
                    VaultRepository.ChangeResult.WrongCurrentPassword -> ChangeOutcome.WrongCurrentPassword
                    VaultRepository.ChangeResult.PasswordRefused -> ChangeOutcome.PasswordRefused
                    is VaultRepository.ChangeResult.Locked -> ChangeOutcome.Locked(result.remainingMillis)
                    VaultRepository.ChangeResult.KeystoreUnavailable -> ChangeOutcome.KeystoreUnavailable
                }
            }
        }

    /** See [VaultRepository.deleteData]. Leaves the vault locked. Returns `false` if no vault was open. */
    suspend fun deleteData(): Boolean =
        serialized {
            val current = session
            if (current != null) {
                repository.deleteData(current)
                session = null
                publish()
            }
            current != null
        }

    /** The heir state of the open vault; `null` if none is open. */
    suspend fun heirStatus(): HeirState.Status? = serialized { session?.let(repository::heirStatus) }

    /** Takes or replaces the heir snapshot. Returns `null`, doing nothing, if no vault is open. */
    suspend fun configureHeir(passphrase: ByteArray, thresholdDays: Int): VaultRepository.HeirConfigureResult? =
        serialized(passphrase) {
            session?.let { repository.configureHeir(it, passphrase, thresholdDays) }
        }

    /** Returns `false` if no vault is open: 2.7.1 asks for one too, and says so. */
    suspend fun disableHeir(): Boolean =
        serialized {
            session?.let {
                repository.disableHeir(it)
                true
            } ?: false
        }

    suspend fun setHeirThreshold(thresholdDays: Int): Boolean =
        serialized {
            session?.let {
                repository.setHeirThreshold(it, thresholdDays)
                true
            } ?: false
        }

    /**
     * Opens the read-only heir view. No vault is opened, and an open one would be left alone: the
     * screen only offers this when the vault is locked.
     */
    suspend fun unlockAsHeir(passphrase: ByteArray): HeirUnlockOutcome =
        serialized(passphrase) {
            when (val result = repository.unlockAsHeir(passphrase)) {
                is HeirRepository.UnlockResult.Opened -> {
                    heirEntries = result.entries
                    publish()
                    HeirUnlockOutcome.Opened
                }
                HeirRepository.UnlockResult.Refused -> HeirUnlockOutcome.Refused
                is HeirRepository.UnlockResult.Locked -> HeirUnlockOutcome.Locked(result.remainingMillis)
                HeirRepository.UnlockResult.KeystoreUnavailable -> HeirUnlockOutcome.KeystoreUnavailable
            }
        }

    /** Whether the unlock screen offers the fingerprint. See [VaultRepository.biometricsArmed]. */
    suspend fun biometricsArmed(): Boolean = serialized { repository.biometricsArmed() }

    /** Returns `null` if no vault is open. Disarms whatever was armed. */
    suspend fun startArmingBiometrics(): ArmStart? =
        serialized {
            session?.let { current ->
                try {
                    val cipher = repository.cipherToArmBiometrics(current)
                    // Whatever was armed is gone: the settings must say so even if the prompt is cancelled.
                    publish()
                    if (cipher == null) ArmStart.Refused else ArmStart.Ready(cipher)
                } catch (_: KeystoreUnavailableException) {
                    publish()
                    ArmStart.KeystoreUnavailable
                }
            }
        }

    /** With a cipher from [startArmingBiometrics] the prompt authenticated. Returns `null` if no vault is open. */
    suspend fun armBiometrics(cipher: Cipher): ArmOutcome? =
        serialized {
            session?.let { current ->
                try {
                    if (repository.armBiometrics(current, cipher)) ArmOutcome.Armed else ArmOutcome.Refused
                } catch (_: KeystoreUnavailableException) {
                    ArmOutcome.KeystoreUnavailable
                } finally {
                    publish()
                }
            }
        }

    /** Throws [KeystoreUnavailableException] if the secure hardware did not answer: still armed, then. */
    suspend fun disarmBiometrics() {
        serialized {
            try {
                repository.disarmBiometrics()
            } finally {
                publish()
            }
        }
    }

    suspend fun startBiometricUnlock(): BiometricStart =
        serialized {
            try {
                when (val start = repository.cipherToUnlockWithBiometrics()) {
                    is BiometricBinding.Start.Ready -> BiometricStart.Ready(start.cipher)
                    BiometricBinding.Start.NotArmed -> BiometricStart.NotArmed
                    BiometricBinding.Start.Invalidated -> BiometricStart.Invalidated
                }
            } catch (_: KeystoreUnavailableException) {
                BiometricStart.KeystoreUnavailable
            }
        }

    /** With a cipher from [startBiometricUnlock] the prompt authenticated. */
    suspend fun unlockWithBiometrics(cipher: Cipher): BiometricOutcome =
        serialized {
            when (val result = repository.unlockWithBiometrics(cipher)) {
                is VaultRepository.BiometricUnlockResult.Opened -> {
                    open(result.session)
                    BiometricOutcome.Opened
                }
                is VaultRepository.BiometricUnlockResult.Locked -> BiometricOutcome.Locked(result.remainingMillis)
                VaultRepository.BiometricUnlockResult.Disarmed -> BiometricOutcome.Disarmed
                VaultRepository.BiometricUnlockResult.KeystoreUnavailable -> BiometricOutcome.KeystoreUnavailable
            }
        }

    /**
     * Merges [incoming] into the open vault, in ONE write (2.7.1 saved the vault once per entry).
     *
     * An entry whose title AND username already exist in the vault, whatever their case, is skipped:
     * importing the same file twice adds nothing. Duplicates INSIDE one file are both kept, as in 2.7.1,
     * which compares against the vault as it was before the import.
     *
     * An id that would collide gets a new one (2.7.1 kept the file's id, so an entry could share an id
     * with one already there, and deleting either deleted both). Returns `null` if no vault is open.
     */
    suspend fun importEntries(incoming: List<Entry>, newId: () -> String = { UUID.randomUUID().toString() }): ImportOutcome? {
        var outcome: ImportOutcome? = null
        val saved = updateEntries { current ->
            val known = current.mapTo(mutableSetOf()) { it.title.lowercase() to it.username.lowercase() }
            val ids = current.mapTo(mutableSetOf()) { it.id }
            val kept = mutableListOf<Entry>()
            var skipped = 0
            for (entry in incoming) {
                if (entry.title.lowercase() to entry.username.lowercase() in known) {
                    skipped++
                } else {
                    kept += if (ids.add(entry.id)) entry else entry.copy(id = newId().also(ids::add))
                }
            }
            outcome = ImportOutcome(added = kept.size, skipped = skipped)
            current + kept
        }
        return if (saved) outcome else null
    }

    /**
     * The open vault as an encrypted backup, sealed with [passphrase] and nothing else: no Keystore key,
     * so it opens on any phone, including the Flutter app 2.7.1. `null` if no vault is open.
     */
    suspend fun exportBackup(passphrase: String): String? =
        serialized { session?.let { PtbakCodec.export(it.entries, passphrase) } }

    /** Every secret in clear, for the owner who confirmed twice (2.7.1). `null` if no vault is open. */
    suspend fun exportPlain(): String? = serialized { session?.let { PlainExport.encode(it.entries) } }

    /** Wipes the key and forgets the entries. Waits for the operation in progress, if any. */
    suspend fun lock() {
        serialized { close() }
    }

    /**
     * Runs [block] on the IO dispatcher under [mutex], then wipes [consumed]. The wipe waits for
     * [block]: `withContext` only returns, or throws for a cancelled caller, once its block is over.
     */
    private suspend fun <T> serialized(vararg consumed: ByteArray, block: () -> T): T =
        try {
            mutex.withLock { withContext(io) { block() } }
        } finally {
            consumed.forEach(ByteArray::wipe)
        }

    /** A freshly derived session: its key is its own, so the previous one, if any, is closed. */
    private fun open(opened: VaultSession) {
        session?.close()
        session = opened
        heirEntries = null
        // The silence the heir waits for starts again, and a failure here must never cost an opening:
        // at worst the heir's door opens earlier than it should, which beats refusing the owner.
        try {
            repository.markVaultActive(opened)
        } catch (_: Exception) {
            // Nothing to say and nothing to do: the vault is open, which is what was asked.
        }
        publish()
    }

    /**
     * The same vault after a save or a structural change. Not closed: a saved session shares its key
     * with the one it replaces.
     */
    private fun advance(updated: VaultSession) {
        session = updated
        publish()
    }

    private fun close() {
        session?.close()
        session = null
        heirEntries = null
        publish()
    }

    private fun publish() {
        mutableState.value = session?.let {
            State.Open(it.entries, hasDecoy = repository.hasDecoy(it), biometrics = biometricStatus(it))
        } ?: heirEntries?.let(State::Heir) ?: State.Locked
    }

    private fun biometricStatus(session: VaultSession): BiometricStatus =
        try {
            repository.biometricStatus(session)
        } catch (_: KeystoreUnavailableException) {
            BiometricStatus.OFF
        }
}
