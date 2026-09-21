package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VaultRepositoryTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private lateinit var repo: VaultRepository

    // The smallest parameters a file may carry, to keep the tests fast. The logic does not depend on them.
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)

    private val password = "owner password".encodeToByteArray()

    @BeforeEach
    fun setUp() {
        val clock = FakeClock()
        val guard = BruteForceGuard.forVault(StateStore(File(dir, StateStore.FILE_NAME), keystore), clock)
        repo = VaultRepository(VaultFiles(dir), keystore, guard, fastParams)
    }

    private fun created(pw: ByteArray = password): VaultSession {
        val result = repo.openOrCreate(pw)
        assertThat(result).isInstanceOf(VaultRepository.CreateResult.Created::class.java)
        return (result as VaultRepository.CreateResult.Created).session
    }

    private fun entry(title: String) = Entry(
        id = title,
        title = title,
        category = "Web",
        password = "secret-$title",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    @Test
    fun `a fresh install shows the creation form, and the first vault is root in slot A`() {
        assertThat(repo.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        val session = created()
        assertThat(session.slot).isEqualTo(Slot.A)
        assertThat(session.meta.root).isTrue()
        assertThat(repo.entryMode()).isEqualTo(VaultRepository.EntryMode.UNLOCK)
    }

    @Test
    fun `every slot exists after the first vault, and only A is occupied`() {
        created()
        val statuses = repo.statuses()
        assertThat(statuses.values.all { it is SlotStatus.Marked }).isTrue()
        assertThat((statuses.getValue(Slot.A) as SlotStatus.Marked).mark.occupied).isTrue()
        assertThat(statuses.getValue(Slot.B).provablyFree).isTrue()
        assertThat(statuses.getValue(Slot.C).provablyFree).isTrue()
    }

    @Test
    fun `the vault opens with its password, and only with it`() {
        created().close()
        val opened = repo.unlock(password)
        assertThat(opened).isInstanceOf(VaultRepository.UnlockResult.Opened::class.java)
        assertThat((opened as VaultRepository.UnlockResult.Opened).session.slot).isEqualTo(Slot.A)
        assertThat(repo.unlock("owner passworD".encodeToByteArray())).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
    }

    @Test
    fun `saved entries come back after a new unlock`() {
        val session = created()
        repo.save(session, listOf(entry("bank"), entry("mail"))).close()
        val reopened = (repo.unlock(password) as VaultRepository.UnlockResult.Opened).session
        assertThat(reopened.entries.map { it.title }).containsExactly("bank", "mail").inOrder()
        assertThat(reopened.entries.first().password).isEqualTo("secret-bank")
    }

    @Test
    fun `every slot file has the same size, before and after a save`() {
        val session = created()
        fun sizes() = Slot.entries.map { File(dir, it.vaultFileName).length() }.toSet()
        assertThat(sizes()).hasSize(1)
        repo.save(session, (1..400).map { entry("entry number $it with a long enough title") })
        assertThat(sizes()).hasSize(1)
    }

    @Test
    fun `every attempt derives against every slot, whichever answers`() {
        created().close()
        keystore.unwrapCalls.clear()
        repo.unlock(password)
        assertThat(keystore.unwrapCalls.filter { it.startsWith("pt_kek_") }).containsExactly("pt_kek_a", "pt_kek_b", "pt_kek_c")
    }

    @Test
    fun `with the occupancy key lost, nothing is created, and the vault still opens`() {
        created().close()
        keystore.deleteKey(OccupancyMark.KEY_ALIAS)
        val before = Slot.entries.associateWith { File(dir, it.vaultFileName).readText() }

        assertThat(repo.statuses().values.all { it == SlotStatus.Unknown }).isTrue()
        assertThat(repo.openOrCreate("attacker password".encodeToByteArray())).isEqualTo(VaultRepository.CreateResult.Impossible)
        assertThat(Slot.entries.associateWith { File(dir, it.vaultFileName).readText() }).isEqualTo(before)

        assertThat(repo.unlock(password)).isInstanceOf(VaultRepository.UnlockResult.Opened::class.java)
    }

    @Test
    fun `the creation form opens an existing vault instead of creating one`() {
        created().close()
        val result = repo.openOrCreate(password)
        assertThat(result).isInstanceOf(VaultRepository.CreateResult.Opened::class.java)
        assertThat(repo.statuses().values.count { it is SlotStatus.Marked && it.mark.occupied }).isEqualTo(1)
    }

    @Test
    fun `a slot file copied over another is not opened in its place`() {
        created().close()
        File(dir, Slot.A.vaultFileName).copyTo(File(dir, Slot.B.vaultFileName), overwrite = true)
        val opened = repo.unlock(password) as VaultRepository.UnlockResult.Opened
        assertThat(opened.session.slot).isEqualTo(Slot.A)
    }
}
