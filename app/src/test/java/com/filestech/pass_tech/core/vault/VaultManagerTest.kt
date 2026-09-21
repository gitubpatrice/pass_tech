package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultManager.ChangeOutcome
import com.filestech.pass_tech.core.vault.VaultManager.CreateOutcome
import com.filestech.pass_tech.core.vault.VaultManager.DecoyOutcome
import com.filestech.pass_tech.core.vault.VaultManager.State
import com.filestech.pass_tech.core.vault.VaultManager.UnlockOutcome
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.GatedFiles
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The manager's own promises, on real threads: one operation at a time, a key never wiped under a
 * running operation, a password wiped once used and never before. The vault rules themselves are
 * pinned by VaultRepositoryTest and VaultStructureTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultManagerTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private lateinit var files: GatedFiles
    private lateinit var manager: VaultManager

    @BeforeEach
    fun setUp() {
        files = GatedFiles(VaultFiles(dir))
        val guard = BruteForceGuard.forVault(StateStore(File(dir, StateStore.FILE_NAME), keystore), FakeClock())
        manager = VaultManager(VaultRepository(files, keystore, guard, params = fastParams), Dispatchers.IO)
    }

    // A fresh array for every call: the manager wipes what it is given.
    private fun owner() = "owner password".encodeToByteArray()

    private fun decoy() = "decoy password".encodeToByteArray()

    private fun entry(title: String) = Entry(
        id = title,
        title = title,
        category = "Web",
        password = "secret-$title",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    private fun open() = manager.state.value as State.Open

    private fun titles() = open().entries.map { it.title }

    private fun ByteArray.isWiped() = all { it == 0.toByte() }

    @Test
    fun `create, save, lock and unlock go through the manager`() = runTest {
        assertThat(manager.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(manager.openOrCreate(owner())).isEqualTo(CreateOutcome.Created)
        assertThat(manager.state.value).isEqualTo(State.Open(emptyList(), hasDecoy = false))
        assertThat(manager.updateEntries { it + entry("mail") }).isTrue()
        manager.lock()
        assertThat(manager.state.value).isEqualTo(State.Locked)
        assertThat(manager.unlock("wrong".encodeToByteArray())).isEqualTo(UnlockOutcome.WrongPassword)
        assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.Opened)
        assertThat(titles()).containsExactly("mail")
        manager.lock()
        assertThat(manager.openOrCreate(owner())).isEqualTo(CreateOutcome.Opened)
    }

    @Test
    fun `a decoy is reported, and a password change keeps the entries`() = runTest {
        manager.openOrCreate(owner())
        manager.updateEntries { it + entry("bank") }
        assertThat(manager.configureDecoy(decoy())).isEqualTo(DecoyOutcome.Created)
        assertThat(open().hasDecoy).isTrue()
        assertThat(manager.changePassword(owner(), "renewed password".encodeToByteArray())).isEqualTo(ChangeOutcome.Changed)
        manager.lock()
        assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.WrongPassword)
        assertThat(manager.unlock("renewed password".encodeToByteArray())).isEqualTo(UnlockOutcome.Opened)
        assertThat(titles()).containsExactly("bank")
        assertThat(open().hasDecoy).isTrue()
    }

    @Test
    fun `a decoy the Keystore interrupts locks the vault, which then opens as it is on disk`() = runTest {
        manager.openOrCreate(owner())
        manager.updateEntries { it + entry("bank") }
        keystore.unavailable += Slot.B.hardwareKeyAlias
        assertThat(manager.configureDecoy(decoy())).isEqualTo(DecoyOutcome.KeystoreUnavailable)
        assertThat(manager.state.value).isEqualTo(State.Locked)
        keystore.unavailable.clear()
        assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.Opened)
        assertThat(titles()).containsExactly("bank")
    }

    @Test
    fun `deleting leaves the vault locked and the entry screen in creation mode`() = runTest {
        manager.openOrCreate(owner())
        assertThat(manager.deleteData()).isTrue()
        assertThat(manager.state.value).isEqualTo(State.Locked)
        assertThat(manager.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(manager.deleteData()).isFalse()
    }

    @Test
    fun `operations on the open vault do nothing once it is locked`() = runTest {
        manager.openOrCreate(owner())
        manager.lock()
        assertThat(manager.updateEntries { it + entry("lost") }).isFalse()
        assertThat(manager.configureDecoy(decoy())).isNull()
        assertThat(manager.verifyPassword(owner())).isNull()
        assertThat(manager.changePassword(owner(), decoy())).isNull()
        assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.Opened)
        assertThat(titles()).isEmpty()
        assertThat(open().hasDecoy).isFalse()
    }

    @Test
    fun `every password handed in is wiped once used, whatever the outcome`() = runTest {
        val handedIn = mutableListOf<ByteArray>()
        fun given(text: String) = text.encodeToByteArray().also(handedIn::add)

        manager.openOrCreate(given("owner password"))
        manager.verifyPassword(given("owner password"))
        manager.configureDecoy(given("owner password")) // refused: it opens a vault
        manager.changePassword(given("wrong"), given("other"))
        manager.lock()
        manager.unlock(given("wrong"))
        manager.configureDecoy(given("decoy password")) // no vault open: nothing done
        assertThat(handedIn.map { it.isWiped() }).doesNotContain(false)
    }

    @Test
    fun `lock waits for the save in progress, which is sealed with the real key`() = runTest {
        manager.openOrCreate(owner())
        val gate = files.arm()
        val saving = launch { manager.updateEntries { it + entry("kept") } }
        runCurrent()
        gate.awaitEntered()
        val locking = launch { manager.lock() }
        runCurrent()
        // An unserialised lock runs now, on another thread, and wipes the key under the save.
        Thread.sleep(SETTLE_MILLIS)
        assertThat(manager.state.value).isInstanceOf(State.Open::class.java)
        gate.open()
        joinAll(saving, locking)
        assertThat(manager.state.value).isEqualTo(State.Locked)
        assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.Opened)
        assertThat(titles()).containsExactly("kept")
    }

    @Test
    fun `a cancelled unlock still derives from the whole password, and wipes it afterwards`() = runTest {
        manager.openOrCreate(owner())
        manager.lock()
        val password = owner()
        val gate = files.arm()
        val unlocking = launch { manager.unlock(password) }
        runCurrent()
        gate.awaitEntered()
        unlocking.cancel()
        // Lets the cancellation run: a manager that wiped on cancellation zeroes the password here,
        // before the derivation has read it.
        runCurrent()
        gate.open()
        unlocking.join()
        assertThat(password.isWiped()).isTrue()
        assertThat(manager.state.value).isInstanceOf(State.Open::class.java)
    }

    @Test
    fun `a call cancelled while waiting for its turn does nothing, and still wipes its password`() = runTest {
        manager.openOrCreate(owner())
        val gate = files.arm()
        val saving = launch { manager.updateEntries { it + entry("first") } }
        runCurrent()
        gate.awaitEntered()
        val password = decoy()
        val decoying = launch { manager.configureDecoy(password) }
        runCurrent()
        decoying.cancel()
        runCurrent()
        gate.open()
        joinAll(saving, decoying)
        assertThat(password.isWiped()).isTrue()
        assertThat(open().hasDecoy).isFalse()
        assertThat(titles()).containsExactly("first")
    }

    @Test
    fun `two edits made at once both survive`() = runTest {
        manager.openOrCreate(owner())
        val gate = files.arm()
        val first = launch { manager.updateEntries { it + entry("first") } }
        runCurrent()
        gate.awaitEntered()
        val second = launch { manager.updateEntries { it + entry("second") } }
        runCurrent()
        gate.open()
        joinAll(first, second)
        assertThat(titles()).containsExactly("first", "second").inOrder()
    }

    private companion object {
        const val SETTLE_MILLIS = 300L
    }
}
