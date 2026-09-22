package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.core.vault.VaultSession
import com.filestech.pass_tech.core.vault.valueOrNull
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The dead man's switch (design v2 §8), on a real vault: the snapshot, the silence it waits for, and
 * the two things it must never say — which slot answered, and whether a passphrase was right.
 */
class HeirTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val clock = FakeClock()
    private lateinit var store: StateStore
    private lateinit var heir: HeirRepository
    private lateinit var repo: VaultRepository

    private val owner = "owner password"
    private val decoy = "decoy password"
    private val heirPassphrase = "phrasedelheritier2026"

    @BeforeEach
    fun setUp() {
        store = StateStore(File(dir, StateStore.FILE_NAME), keystore)
        heir = heirRepository(dir, keystore, store, clock, fastParams)
        repo = VaultRepository(VaultFiles(dir), keystore, BruteForceGuard.forVault(store, clock), heir, params = fastParams)
    }

    private fun entry(title: String) = Entry(
        id = title,
        title = title,
        category = "Web",
        password = "secret-$title",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    /** A vault with one entry, which the heir needs: an empty one is refused. */
    private fun vaultWith(password: String, titles: List<String> = listOf("bank")): VaultSession {
        val created = (repo.openOrCreate(password.encodeToByteArray()) as VaultRepository.CreateResult.Created).session
        return repo.save(created, titles.map(::entry))
    }

    private fun open(password: String): VaultSession =
        (repo.unlock(password.encodeToByteArray()) as VaultRepository.UnlockResult.Opened).session

    private fun configure(session: VaultSession, passphrase: String = heirPassphrase, days: Int = 30) =
        repo.configureHeir(session, passphrase.encodeToByteArray(), days)

    /** Moves time on by [days], as a phone left alone does: both clocks, as a phone that stays on. */
    private fun silentFor(days: Int) {
        clock.advance(days * DAY_MILLIS)
    }

    /**
     * A reading, far enough apart from the last one that any delay a wrong passphrase left has run
     * out. Only the test about that delay calls the repository directly.
     */
    private fun readAsHeir(passphrase: String = heirPassphrase): HeirRepository.UnlockResult {
        clock.advance(MINUTE_MILLIS)
        return repo.unlockAsHeir(passphrase.encodeToByteArray())
    }

    /**
     * Whether the file on disk still gives up its contents to [passphrase] — read straight, without
     * asking the app. Forgetting the heir state refuses the heir just as well, and would leave every
     * secret of the vault sitting there, sealed by a passphrase its owner handed to someone else.
     */
    private fun snapshotOpens(slot: Slot, passphrase: String = heirPassphrase): Boolean {
        val content = File(dir, "pt_heir_" + slot.label + ".enc").takeIf { it.isFile }?.readText() ?: return false
        val parsed = HeirContainer.parseOrNull(content, slot) ?: return false
        val key = HeirContainer.deriveKey(parsed.header, passphrase.encodeToByteArray(), keystore).valueOrNull() ?: return false
        return HeirContainer.openOrNull(parsed, key) != null
    }

    @Test
    fun `an heir reads the entries once the vault has been silent long enough`() {
        val session = vaultWith(owner, listOf("bank", "mail"))
        assertThat(configure(session)).isEqualTo(VaultRepository.HeirConfigureResult.Done)

        silentFor(30 + HeirState.GRACE_DAYS)
        val result = readAsHeir()
        assertThat(result).isInstanceOf(HeirRepository.UnlockResult.Opened::class.java)
        assertThat((result as HeirRepository.UnlockResult.Opened).entries.map { it.title })
            .containsExactly("bank", "mail")
    }

    @Test
    fun `before the threshold, and during the grace days, the door stays shut`() {
        configure(vaultWith(owner))

        silentFor(29)
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)
        silentFor(5) // past the threshold, inside the grace
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)
        silentFor(HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isInstanceOf(HeirRepository.UnlockResult.Opened::class.java)
    }

    @Test
    fun `the owner coming back starts the silence again`() {
        configure(vaultWith(owner))
        silentFor(30 + HeirState.GRACE_DAYS)

        // Opening the vault is what says the owner is there. The manager does it; here, by hand.
        repo.markVaultActive(open(owner))
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)

        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isInstanceOf(HeirRepository.UnlockResult.Opened::class.java)
    }

    @Test
    fun `a wrong passphrase and a vault that still answers are refused in the same words`() {
        configure(vaultWith(owner))
        assertThat(readAsHeir("pas la bonne phrase du tout")).isEqualTo(HeirRepository.UnlockResult.Refused)
        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir("pas la bonne phrase du tout")).isEqualTo(HeirRepository.UnlockResult.Refused)
    }

    @Test
    fun `the master password does not open the snapshot, and the heir passphrase does not open the vault`() {
        configure(vaultWith(owner))
        silentFor(30 + HeirState.GRACE_DAYS)

        assertThat(readAsHeir(owner)).isEqualTo(HeirRepository.UnlockResult.Refused)
        assertThat(repo.unlock(heirPassphrase.encodeToByteArray())).isEqualTo(VaultRepository.UnlockResult.WrongPassword)
    }

    @Test
    fun `the heir passphrase may not be the master password of the vault it snapshots`() {
        val session = vaultWith(owner)
        assertThat(configure(session, passphrase = owner)).isEqualTo(VaultRepository.HeirConfigureResult.PassphraseRefused)
        assertThat(repo.heirStatus(session).enabled).isFalse()
    }

    @Test
    fun `an empty vault is refused, as in 2_7_1`() {
        val empty = (repo.openOrCreate(owner.encodeToByteArray()) as VaultRepository.CreateResult.Created).session
        assertThat(configure(empty)).isEqualTo(VaultRepository.HeirConfigureResult.VaultEmpty)
    }

    @Test
    fun `a new passphrase simply replaces the old one, with the entries of the day`() {
        var session = vaultWith(owner, listOf("bank"))
        configure(session)
        session = repo.save(session, listOf(entry("bank"), entry("mail")))
        configure(session, passphrase = "unenouvellephrase2027")

        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)
        val result = readAsHeir("unenouvellephrase2027")
        assertThat((result as HeirRepository.UnlockResult.Opened).entries.map { it.title }).containsExactly("bank", "mail")
    }

    @Test
    fun `turning it off shreds the snapshot`() {
        val session = vaultWith(owner)
        configure(session)
        repo.disableHeir(session)
        assertThat(snapshotOpens(session.slot)).isFalse()

        assertThat(repo.heirStatus(session).enabled).isFalse()
        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)
    }

    @Test
    fun `an heir set up from the decoy really works, and knows nothing of the other vault`() {
        val parent = vaultWith(owner)
        repo.configureDecoy(parent, decoy.encodeToByteArray())
        val inDecoy = repo.save(open(decoy), listOf(entry("decoy entry")))
        assertThat(configure(inDecoy, passphrase = "laphraseduleurre2026")).isEqualTo(VaultRepository.HeirConfigureResult.Done)

        // The parent's own heir state is untouched: each incarnation has its own.
        assertThat(repo.heirStatus(open(owner)).enabled).isFalse()

        silentFor(30 + HeirState.GRACE_DAYS)
        val result = readAsHeir("laphraseduleurre2026")
        assertThat((result as HeirRepository.UnlockResult.Opened).entries.map { it.title }).containsExactly("decoy entry")
    }

    @Test
    fun `deleting the vault shreds its snapshot, so nothing readable is left behind`() {
        val session = vaultWith(owner)
        configure(session)
        assertThat(snapshotOpens(session.slot)).isTrue()

        repo.deleteData(open(owner))
        assertThat(snapshotOpens(session.slot)).isFalse()
        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isEqualTo(HeirRepository.UnlockResult.Refused)
    }

    @Test
    fun `deleting the decoy shreds the decoy's snapshot too`() {
        val parent = vaultWith(owner)
        repo.configureDecoy(parent, decoy.encodeToByteArray())
        val inDecoy = repo.save(open(decoy), listOf(entry("decoy entry")))
        configure(inDecoy, passphrase = "laphraseduleurre2026")

        assertThat(snapshotOpens(inDecoy.slot, "laphraseduleurre2026")).isTrue()
        repo.deleteDecoy(open(owner))
        assertThat(snapshotOpens(inDecoy.slot, "laphraseduleurre2026")).isFalse()
        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir("laphraseduleurre2026")).isEqualTo(HeirRepository.UnlockResult.Refused)
    }

    @Test
    fun `every slot carries a snapshot, and they all weigh the same`() {
        configure(vaultWith(owner))
        val sizes = Slot.entries.map { File(dir, "pt_heir_${it.label}.enc").length() }
        assertThat(sizes.toSet()).hasSize(1)
        assertThat(sizes.first()).isGreaterThan(0L)
    }

    @Test
    fun `a wrong passphrase costs a growing delay`() {
        configure(vaultWith(owner))
        val wrong = "mauvaise phrase".encodeToByteArray()
        assertThat(repo.unlockAsHeir(wrong.copyOf())).isEqualTo(HeirRepository.UnlockResult.Refused)
        val locked = repo.unlockAsHeir(wrong.copyOf())
        assertThat(locked).isInstanceOf(HeirRepository.UnlockResult.Locked::class.java)
        assertThat((locked as HeirRepository.UnlockResult.Locked).remainingMillis).isGreaterThan(0L)
    }

    @Test
    fun `the threshold is the vault's own, and can be changed`() {
        val session = vaultWith(owner)
        configure(session, days = 180)
        assertThat(repo.heirStatus(session).thresholdDays).isEqualTo(180)

        repo.setHeirThreshold(session, 30)
        assertThat(repo.heirStatus(session).thresholdDays).isEqualTo(30)
        silentFor(30 + HeirState.GRACE_DAYS)
        assertThat(readAsHeir()).isInstanceOf(HeirRepository.UnlockResult.Opened::class.java)
    }

    @Test
    fun `the days of silence are counted for the screen to show`() {
        val session = vaultWith(owner)
        configure(session)
        assertThat(repo.heirStatus(session).inactivityDays).isEqualTo(0)
        silentFor(12)
        assertThat(repo.heirStatus(session).inactivityDays).isEqualTo(12)
        assertThat(repo.heirStatus(session).due).isFalse()
    }

    /**
     * The clock floor: a date moved back does not give back days the app has already SEEN pass. It
     * cannot give back what it never saw, and does not claim to — a floor only remembers its readings.
     */
    @Test
    fun `a clock moved backwards does not undo the silence the app has seen`() {
        val session = vaultWith(owner)
        configure(session)
        silentFor(30 + HeirState.GRACE_DAYS)
        // Someone opens the settings during those days, which is when the app reads the date.
        assertThat(repo.heirStatus(session).due).isTrue()

        clock.wall -= 365 * DAY_MILLIS
        assertThat(readAsHeir()).isInstanceOf(HeirRepository.UnlockResult.Opened::class.java)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val MINUTE_MILLIS = 60L * 1000
    }
}
