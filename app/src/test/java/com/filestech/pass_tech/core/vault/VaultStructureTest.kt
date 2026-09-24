package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Decoys, deletion and password change: the operations where the decoy promise is kept or broken.
 * Each test states the design rule it pins (design v2.1, `audit/conversion-kotlin/10-conception-coffre.md`).
 */
class VaultStructureTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private var biometricPurges = 0
    private lateinit var files: CrashingFiles
    private lateinit var repo: VaultRepository

    private val owner = "owner password".encodeToByteArray()
    private val decoy = "decoy password".encodeToByteArray()
    private val attacker = "attacker password".encodeToByteArray()

    @BeforeEach
    fun setUp() {
        files = CrashingFiles(VaultFiles(dir))
        val clock = FakeClock()
        val store = StateStore(File(dir, StateStore.FILE_NAME), keystore)
        val guard = BruteForceGuard.forVault(store, clock)
        // Counts the purges; nothing is ever armed here.
        val biometrics = object : BiometricBinding by BiometricBinding.NONE {
            override fun purge() {
                biometricPurges++
            }
        }
        val heir = heirRepository(dir, keystore, store, clock, fastParams)
        repo = VaultRepository(files, keystore, guard, heir, biometrics, params = fastParams)
    }

    private fun open(password: ByteArray): VaultSession =
        (repo.unlock(password) as VaultRepository.UnlockResult.Opened).session

    private fun ownerWithDecoy(): VaultSession {
        val owner = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        return (repo.configureDecoy(owner, decoy) as VaultRepository.DecoyResult.Created).parent
    }

    private fun occupied() = repo.statuses().filterValues { it is SlotStatus.Marked && it.mark.occupied }.keys

    private fun entry(title: String) = Entry(
        id = title,
        title = title,
        category = "Web",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    @Test
    fun `a decoy opens with its own password, in its own slot, and is not root`() {
        val parent = ownerWithDecoy()
        assertThat(repo.decoyOf(parent)?.slot).isEqualTo(Slot.B)
        val opened = open(decoy)
        assertThat(opened.slot).isEqualTo(Slot.B)
        assertThat(opened.meta.root).isFalse()
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `from inside the decoy, no decoy is configured (oracle A)`() {
        ownerWithDecoy().close()
        assertThat(repo.decoyOf(open(decoy))).isNull()
    }

    @Test
    fun `from inside the decoy, configuring a decoy really works (K = 3)`() {
        ownerWithDecoy().close()
        val inDecoy = open(decoy)
        val result = repo.configureDecoy(inDecoy, attacker)
        assertThat(result).isInstanceOf(VaultRepository.DecoyResult.Created::class.java)
        assertThat(open(attacker).slot).isEqualTo(Slot.C)
        // The owner's vault is untouched.
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `the capacity limit shows only one level further down (accepted N2 residual)`() {
        ownerWithDecoy().close()
        repo.configureDecoy(open(decoy), attacker)
        val deepest = open(attacker)
        assertThat(repo.configureDecoy(deepest, "fourth password".encodeToByteArray())).isEqualTo(VaultRepository.DecoyResult.Impossible)
    }

    @Test
    fun `a vault has one decoy at most`() {
        val parent = ownerWithDecoy()
        assertThrows<IllegalStateException> { repo.configureDecoy(parent, "another".encodeToByteArray()) }
    }

    @Test
    fun `a decoy password that opens an existing vault is refused, and nothing is written`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        val before = Slot.entries.map { files.read(it) }
        assertThat(repo.configureDecoy(parent, owner)).isEqualTo(VaultRepository.DecoyResult.PasswordRefused)
        assertThat(Slot.entries.map { files.read(it) }).isEqualTo(before)
    }

    @Test
    fun `configuring a decoy purges biometrics before the decoy exists`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        files.crashOnWrite = 1 // the journal write, the first one after the purge
        assertThrows<SimulatedCrash> { repo.configureDecoy(parent, decoy) }
        assertThat(biometricPurges).isEqualTo(1)
    }

    @Test
    fun `a crash after the journal but before the decoy leaves no decoy and no orphan`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        files.crashOnWrite = 2 // the decoy write
        assertThrows<SimulatedCrash> { repo.configureDecoy(parent, decoy) }
        files.crashOnWrite = 0

        val reopened = open(owner)
        assertThat(reopened.meta.pendingChild).isNull()
        assertThat(repo.decoyOf(reopened)).isNull()
        assertThat(occupied()).containsExactly(Slot.A)
        assertThat(repo.unlock(decoy)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
    }

    @Test
    fun `a decoy whose key the Keystore cannot derive writes nothing at all`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        val before = Slot.entries.map { files.read(it) }
        keystore.unavailable += Slot.B.hardwareKeyAlias
        // The collision check fails first on slot B: the owner tries again once the Keystore answers.
        assertThat(repo.configureDecoy(parent, decoy)).isEqualTo(VaultRepository.DecoyResult.KeystoreUnavailable)
        assertThat(Slot.entries.map { files.read(it) }).isEqualTo(before)
    }

    @Test
    fun `the decoy key is derived before the parent journals anything`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        val before = Slot.entries.map { files.read(it) }
        // The collision check passes (one HMAC per slot), then the decoy's own derivation fails.
        keystore.hmacCalls.clear()
        keystore.hmacUnavailableAfter = Slot.entries.size
        assertThat(repo.configureDecoy(parent, decoy)).isEqualTo(VaultRepository.DecoyResult.KeystoreUnavailable)
        assertThat(Slot.entries.map { files.read(it) }).isEqualTo(before)
    }

    @Test
    fun `a decoy whose mark cannot be read still counts as there, but is never erased with its parent`() {
        val parent = ownerWithDecoy()
        keystore.unreadable += OccupancyMark.KEY_ALIAS
        assertThat(repo.hasDecoy(parent)).isTrue()
        assertThat(repo.decoyOf(parent)).isNull()
    }

    @Test
    fun `an unreadable decoy mark is never settled away, nor a second decoy offered over it`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        files.crashOnWrite = 3 // the decoy exists, its confirmation does not
        assertThrows<SimulatedCrash> { repo.configureDecoy(parent, decoy) }
        files.crashOnWrite = 0
        keystore.unreadable += OccupancyMark.KEY_ALIAS

        val reopened = open(owner)
        assertThat(reopened.meta.pendingChild?.slot).isEqualTo(Slot.B)
        assertThat(repo.hasDecoy(reopened)).isTrue()

        keystore.unreadable.clear()
        assertThat(repo.decoyOf(open(owner))?.slot).isEqualTo(Slot.B)
        assertThat(open(decoy).slot).isEqualTo(Slot.B)
    }

    @Test
    fun `a crash after the decoy but before its confirmation is completed at the next opening`() {
        val parent = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        files.crashOnWrite = 3 // the confirmation in the parent
        assertThrows<SimulatedCrash> { repo.configureDecoy(parent, decoy) }
        files.crashOnWrite = 0

        val reopened = open(owner)
        assertThat(reopened.meta.pendingChild).isNull()
        assertThat(repo.decoyOf(reopened)?.slot).isEqualTo(Slot.B)
        assertThat(open(decoy).slot).isEqualTo(Slot.B)
    }

    @Test
    fun `deleting from the decoy spares the real vault and shows the creation form (oracle E)`() {
        ownerWithDecoy().close()
        repo.deleteData(open(decoy))

        assertThat(repo.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(repo.unlock(decoy)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `deleting from the decoy also erases the decoy it created, never its parent`() {
        ownerWithDecoy().close()
        repo.configureDecoy(open(decoy), attacker)
        repo.deleteData(open(decoy))
        assertThat(occupied()).containsExactly(Slot.A)
        assertThat(repo.unlock(attacker)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
    }

    @Test
    fun `after the decoy is emptied, a new vault goes to a free slot and the owner still gets in`() {
        ownerWithDecoy().close()
        repo.deleteData(open(decoy))
        val created = repo.openOrCreate(attacker)
        assertThat(created).isInstanceOf(VaultRepository.CreateResult.Created::class.java)
        assertThat((created as VaultRepository.CreateResult.Created).session.slot).isNotEqualTo(Slot.A)
        assertThat(created.session.meta.root).isFalse()
        // The owner, back on the creation form, types the real password: it opens, nothing is created.
        assertThat(repo.openOrCreate(owner)).isInstanceOf(VaultRepository.CreateResult.Opened::class.java)
    }

    @Test
    fun `a vault created while the real one exists is not root, so its deletion cannot erase everything`() {
        ownerWithDecoy().close()
        repo.deleteData(open(decoy))
        val newcomer = (repo.openOrCreate(attacker) as VaultRepository.CreateResult.Created).session
        repo.deleteData(newcomer)
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `deleting from the root erases every vault and shows the creation form`() {
        ownerWithDecoy()
        repo.deleteData(open(owner))
        assertThat(occupied()).isEmpty()
        assertThat(repo.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(repo.unlock(owner)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
        assertThat(repo.unlock(decoy)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
    }

    /**
     * The state no test ever measured, and the one the audit of 2026-09-24 found: a decoy in place,
     * the parent grown past a bucket, and nothing deleted.
     *
     * Every test that claimed "all slot files are the same size" measured somewhere else. Some had a
     * single vault, where the other two slots are dummies and get rewritten whole. Others measured
     * AFTER a deletion, which puts every slot back at the same size anyway. In between — which is
     * simply a decoy being used — the decoy's file stayed at its old bucket, and a file smaller than
     * the largest could only be an occupied slot. That is the whole of plausible deniability, for
     * anyone holding a copy of the directory.
     */
    @Test
    fun `with a decoy in place, a parent that grows leaves no file behind`() {
        val parent = ownerWithDecoy()
        repo.save(parent, (1..400).map { entry("entry number $it with a long enough title") }).close()
        assertThat(Slot.entries.map { File(dir, it.vaultFileName).length() }.toSet()).hasSize(1)
        // And it is bigger than one bucket, or the test would be measuring nothing.
        assertThat(File(dir, Slot.B.vaultFileName).length()).isGreaterThan(Padding.FIRST_BUCKET.toLong())
    }

    @Test
    fun `a decoy whose file was evened out still opens, and still holds its entries`() {
        val parent = ownerWithDecoy()
        val withEntries = repo.save(open(decoy), listOf(entry("a credible looking account")))
        withEntries.close()
        // The parent now grows past the bucket, which rewrites the decoy's file around its ciphertext.
        repo.save(open(owner), (1..400).map { entry("entry number $it with a long enough title") }).close()
        val reopened = open(decoy)
        assertThat(reopened.slot).isEqualTo(Slot.B)
        assertThat(reopened.entries.map { it.title }).containsExactly("a credible looking account")
        // And it can save again, which re-pads its own plaintext to the common bucket.
        repo.save(reopened, reopened.entries + entry("one more")).close()
        assertThat(Slot.entries.map { File(dir, it.vaultFileName).length() }.toSet()).hasSize(1)
        assertThat(open(owner).entries).hasSize(400)
    }

    @Test
    fun `deletion keeps the largest bucket, whichever vault it comes from`() {
        val parent = ownerWithDecoy()
        repo.save(parent, (1..400).map { entry("entry number $it with a long enough title") }).close()
        val bigSize = File(dir, Slot.A.vaultFileName).length()
        repo.deleteData(open(decoy))
        assertThat(Slot.entries.map { File(dir, it.vaultFileName).length() }.toSet()).containsExactly(bigSize)
    }

    @Test
    fun `every deletion purges biometrics`() {
        ownerWithDecoy().close()
        val before = biometricPurges
        repo.deleteData(open(decoy))
        assertThat(biometricPurges).isEqualTo(before + 1)
    }

    @Test
    fun `deleting the decoy frees its slot and its password opens nothing`() {
        val parent = ownerWithDecoy()
        val saved = repo.save(parent, listOf(entry("bank")))
        val result = repo.deleteDecoy(saved)

        assertThat(result).isInstanceOf(VaultRepository.DecoyDeleteResult.Deleted::class.java)
        assertThat(occupied()).containsExactly(Slot.A)
        assertThat(repo.unlock(decoy)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
        // The parent is untouched, entries included, and no longer knows about a decoy.
        val reopened = open(owner)
        assertThat(reopened.entries.map { it.title }).containsExactly("bank")
        assertThat(repo.hasDecoy(reopened)).isFalse()
        assertThat(repo.decoyOf(reopened)).isNull()
    }

    @Test
    fun `deleting the decoy does not ask for the creation form, unlike deleting one's own data`() {
        val parent = ownerWithDecoy()
        repo.deleteDecoy(parent)
        assertThat(repo.entryMode()).isEqualTo(VaultRepository.EntryMode.UNLOCK)
    }

    @Test
    fun `deleting the decoy purges biometrics, as every other deletion does`() {
        val parent = ownerWithDecoy()
        val before = biometricPurges
        repo.deleteDecoy(parent)
        assertThat(biometricPurges).isEqualTo(before + 1)
    }

    @Test
    fun `the freed slot takes the largest bucket, so no file shrinks`() {
        val parent = ownerWithDecoy()
        val big = repo.save(parent, (1..400).map { entry("entry number $it with a long enough title") })
        val bigSize = File(dir, Slot.A.vaultFileName).length()
        repo.deleteDecoy(big)
        assertThat(Slot.entries.map { File(dir, it.vaultFileName).length() }.toSet()).containsExactly(bigSize)
    }

    @Test
    fun `the freed slot can hold a new decoy`() {
        val parent = ownerWithDecoy()
        val deleted = (repo.deleteDecoy(parent) as VaultRepository.DecoyDeleteResult.Deleted).parent
        assertThat(repo.configureDecoy(deleted, attacker)).isInstanceOf(VaultRepository.DecoyResult.Created::class.java)
        assertThat(open(attacker).slot).isEqualTo(Slot.B)
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `a decoy whose mark cannot be read is never erased on a guess`() {
        val parent = ownerWithDecoy()
        val before = Slot.entries.map { files.read(it) }
        keystore.unreadable += OccupancyMark.KEY_ALIAS

        assertThat(repo.deleteDecoy(parent)).isEqualTo(VaultRepository.DecoyDeleteResult.KeystoreUnavailable)
        assertThat(Slot.entries.map { files.read(it) }).isEqualTo(before)
        keystore.unreadable.clear()
        assertThat(open(decoy).slot).isEqualTo(Slot.B)
    }

    @Test
    fun `a vault with no decoy has none to delete, and writes nothing`() {
        val alone = (repo.openOrCreate(owner) as VaultRepository.CreateResult.Created).session
        val before = Slot.entries.map { files.read(it) }
        assertThat(repo.deleteDecoy(alone)).isEqualTo(VaultRepository.DecoyDeleteResult.NotConfigured)
        assertThat(Slot.entries.map { files.read(it) }).isEqualTo(before)
    }

    @Test
    fun `from inside the decoy, deleting a decoy never reaches the parent`() {
        ownerWithDecoy().close()
        val inDecoy = open(decoy)
        assertThat(repo.deleteDecoy(inDecoy)).isEqualTo(VaultRepository.DecoyDeleteResult.NotConfigured)
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }

    @Test
    fun `a changed password opens the vault, the old one no longer does`() {
        val parent = ownerWithDecoy()
        val new = "new owner password".encodeToByteArray()
        val result = repo.changePassword(parent, owner, new)
        assertThat(result).isInstanceOf(VaultRepository.ChangeResult.Changed::class.java)
        assertThat(repo.unlock(owner)).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
        assertThat(open(new).slot).isEqualTo(Slot.A)
        assertThat(open(decoy).slot).isEqualTo(Slot.B)
    }

    @Test
    fun `a new password that opens another vault is refused`() {
        val parent = ownerWithDecoy()
        assertThat(repo.changePassword(parent, owner, decoy)).isEqualTo(VaultRepository.ChangeResult.PasswordRefused)
        assertThat(repo.changePassword(parent, owner, owner)).isEqualTo(VaultRepository.ChangeResult.PasswordRefused)
    }

    @Test
    fun `a wrong current password changes nothing`() {
        val parent = ownerWithDecoy()
        val result = repo.changePassword(parent, "not it".encodeToByteArray(), "whatever".encodeToByteArray())
        assertThat(result).isEqualTo(VaultRepository.ChangeResult.WrongCurrentPassword)
        assertThat(open(owner).slot).isEqualTo(Slot.A)
    }
}

class SimulatedCrash : RuntimeException("simulated crash")

/** Delegates to real files, and throws on the [crashOnWrite]-th write from now (0: never). */
class CrashingFiles(private val delegate: SlotFiles) : SlotFiles by delegate {
    var crashOnWrite = 0
        set(value) {
            field = value
            writes = 0
        }
    private var writes = 0

    override fun writeAll(updates: Map<Slot, String>) {
        writes++
        if (crashOnWrite != 0 && writes == crashOnWrite) throw SimulatedCrash()
        delegate.writeAll(updates)
    }
}
