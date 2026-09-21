package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.di.IoDispatcher
import com.filestech.pass_tech.core.model.Entry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 */
@Singleton
class VaultManager @Inject constructor(
    private val repository: VaultRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    sealed interface State {
        data object Locked : State

        /** [hasDecoy]: this vault created a decoy that still exists, so it cannot create another one. */
        data class Open(val entries: List<Entry>, val hasDecoy: Boolean) : State
    }

    sealed interface UnlockOutcome {
        data object Opened : UnlockOutcome

        data object WrongPassword : UnlockOutcome

        data class Locked(val remainingMillis: Long) : UnlockOutcome
    }

    sealed interface CreateOutcome {
        /** The password opened an existing vault: nothing was created. */
        data object Opened : CreateOutcome

        data object Created : CreateOutcome

        /** No slot is provably free. Deliberately says nothing more. */
        data object Impossible : CreateOutcome

        data class Locked(val remainingMillis: Long) : CreateOutcome
    }

    sealed interface DecoyOutcome {
        data object Created : DecoyOutcome

        /** The password opens an existing vault. Same answer as "the same as the current password". */
        data object PasswordRefused : DecoyOutcome

        data object Impossible : DecoyOutcome

        data class Locked(val remainingMillis: Long) : DecoyOutcome
    }

    sealed interface ChangeOutcome {
        data object Changed : ChangeOutcome

        data object WrongCurrentPassword : ChangeOutcome

        data object PasswordRefused : ChangeOutcome

        data class Locked(val remainingMillis: Long) : ChangeOutcome
    }

    private val mutex = Mutex()

    /** Read and written only under [mutex]. */
    private var session: VaultSession? = null

    private val mutableState = MutableStateFlow<State>(State.Locked)
    val state: StateFlow<State> = mutableState.asStateFlow()

    suspend fun entryMode(): VaultRepository.EntryMode = serialized { repository.entryMode() }

    suspend fun unlock(password: ByteArray): UnlockOutcome =
        serialized(password) {
            when (val result = repository.unlock(password)) {
                is VaultRepository.UnlockResult.Opened -> {
                    open(result.session)
                    UnlockOutcome.Opened
                }
                VaultRepository.UnlockResult.WrongPassword -> UnlockOutcome.WrongPassword
                is VaultRepository.UnlockResult.Locked -> UnlockOutcome.Locked(result.remainingMillis)
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

    /** Returns `null`, doing nothing, if no vault is open. The caller only offers it when [State.Open.hasDecoy] is false. */
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
                        ChangeOutcome.Changed
                    }
                    VaultRepository.ChangeResult.WrongCurrentPassword -> ChangeOutcome.WrongCurrentPassword
                    VaultRepository.ChangeResult.PasswordRefused -> ChangeOutcome.PasswordRefused
                    is VaultRepository.ChangeResult.Locked -> ChangeOutcome.Locked(result.remainingMillis)
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

    /** Wipes the key and forgets the entries. Waits for the operation in progress, if any. */
    suspend fun lock() {
        serialized {
            session?.close()
            session = null
            publish()
        }
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

    private fun publish() {
        mutableState.value = session?.let { State.Open(it.entries, hasDecoy = repository.decoyOf(it) != null) } ?: State.Locked
    }
}
