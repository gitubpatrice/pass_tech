package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.backup.ImportParser
import com.filestech.pass_tech.core.backup.PtbakCodec
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FixedDomain
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Bringing a file in and taking one out, against a real vault: what is merged, what is skipped, what is written. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultImportExportTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val fastBackup = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private lateinit var files: CountingFiles
    private lateinit var repository: VaultRepository
    private lateinit var manager: VaultManager

    /** Counts the writes, to see that an import saves the vault ONCE, not once per entry as 2.7.1 did. */
    private class CountingFiles(private val delegate: SlotFiles) : SlotFiles {
        var writes = 0
            private set

        override fun exists(slot: Slot): Boolean = delegate.exists(slot)

        override fun read(slot: Slot): String? = delegate.read(slot)

        override fun writeAll(updates: Map<Slot, String>) {
            writes++
            delegate.writeAll(updates)
        }
    }

    @BeforeEach
    fun setUp() {
        files = CountingFiles(VaultFiles(dir))
        val clock = FakeClock()
        val store = StateStore(File(dir, StateStore.FILE_NAME), keystore)
        val guard = BruteForceGuard.forVault(store, clock)
        repository = VaultRepository(files, keystore, guard, heirRepository(dir, keystore, store, clock, fastParams), params = fastParams)
    }

    private var fresh = 0

    private fun owner() = "owner password".encodeToByteArray()

    private fun entry(title: String, username: String = "", id: String = title) = Entry(
        id = id,
        title = title,
        category = "Web",
        username = username,
        password = "secret-$title",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    private fun open() = manager.state.value as VaultManager.State.Open

    /** The vault on the test's own scheduler: nothing is left running on a real thread at the end. */
    private suspend fun TestScope.opened() {
        manager = VaultManager(repository, FixedDomain(), StandardTestDispatcher(testScheduler))
        assertThat(manager.openOrCreate(owner())).isEqualTo(VaultManager.CreateOutcome.Created)
    }

    @Test
    fun `an import adds what is new, skips what the vault already holds, and saves once`() = runTest {
        opened()
        manager.updateEntries { listOf(entry("Bank", "alice"), entry("Mail", "bob")) }
        val before = files.writes
        val outcome = manager.importEntries(
            listOf(
                entry("bank", "ALICE", id = "other"), // same title and username, other case: skipped
                entry("Bank", "carol", id = "kept"), // same title, another username: a different account
                entry("New", id = "new"),
            ),
        )
        assertThat(outcome?.added).isEqualTo(2)
        assertThat(outcome?.skipped).isEqualTo(1)
        assertThat(open().entries.map { it.title }).containsExactly("Bank", "Mail", "Bank", "New").inOrder()
        assertThat(files.writes - before).isEqualTo(1)
    }

    @Test
    fun `an id already in the vault, or twice in the file, is replaced, and a free one is kept`() = runTest {
        opened()
        manager.updateEntries { listOf(entry("Bank", "alice", id = "taken")) }
        manager.importEntries(
            listOf(entry("A", id = "taken"), entry("B", id = "twice"), entry("C", id = "twice"), entry("D", id = "free")),
            newId = { "fresh-" + fresh++ },
        )
        val ids = open().entries.associate { it.title to it.id }
        assertThat(ids["A"]).isNotEqualTo("taken")
        assertThat(ids["B"]).isEqualTo("twice")
        assertThat(ids["C"]).isNotEqualTo("twice")
        assertThat(ids["D"]).isEqualTo("free")
        assertThat(ids.values.toSet()).hasSize(ids.size)
    }

    @Test
    fun `importing into a locked vault does nothing`() = runTest {
        opened()
        manager.lock()
        assertThat(manager.importEntries(listOf(entry("New")))).isNull()
    }

    @Test
    fun `the encrypted backup opens with its passphrase, and with nothing else`() = runTest {
        opened()
        manager.updateEntries { listOf(entry("Bank", "alice"), entry("Note")) }
        val content = manager.exportBackup("phrase de sauvegarde")!!

        val imported = PtbakCodec.import(content, "phrase de sauvegarde")!!
        assertThat(imported.version).isEqualTo(3)
        assertThat(imported.entries.map { it.title }).containsExactly("Bank", "Note")
        assertThat(imported.entries.first().password).isEqualTo("secret-Bank")
        assertThat(PtbakCodec.import(content, "phrase de sauvegard")).isNull()
    }

    @Test
    fun `the plain export is read back by the import, entry for entry`() = runTest {
        opened()
        val entries = listOf(entry("Bank", "alice"), entry("Note", id = "note-id"))
        manager.updateEntries { entries }
        val content = manager.exportPlain()!!

        assertThat(content).contains("\n  {")
        val result = ImportParser.parse(content, untitled = "Untitled")
        assertThat(result.format).isEqualTo(ImportParser.Format.PASS_TECH)
        assertThat(result.entries.map { it.id }).isEqualTo(entries.map { it.id })
        assertThat(result.entries.map { it.password }).isEqualTo(entries.map { it.password })
        assertThat(result.entries.map { it.createdAt }).isEqualTo(entries.map { it.createdAt })
    }

    @Test
    fun `a locked vault exports nothing`() = runTest {
        opened()
        manager.lock()
        assertThat(manager.exportBackup("phrase")).isNull()
        assertThat(manager.exportPlain()).isNull()
    }

    @Test
    fun `a backup of this app opens in the Flutter app's reader, and the other way round`() = runTest {
        // The way back (design D6): both readers are pinned by vectors, here the two meet on one file.
        opened()
        manager.updateEntries { listOf(entry("Bank", "alice")) }
        val content = manager.exportBackup("phrase")!!
        val reread = PtbakCodec.import(content, "phrase")!!.entries
        val roundTrip = PtbakCodec.import(PtbakCodec.export(reread, "phrase", fastBackup), "phrase")!!.entries
        assertThat(roundTrip.map { it.title }).isEqualTo(reread.map { it.title })
    }
}
