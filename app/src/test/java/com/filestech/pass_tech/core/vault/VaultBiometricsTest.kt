package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.biometric.StoredBiometricBinding
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricStatus
import com.filestech.pass_tech.core.vault.VaultRepository.BiometricUnlockResult
import com.filestech.pass_tech.testing.FakeBiometricKeys
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Biometric unlock against a real vault (design v2 §9, §10, v2.1 §3): in-memory Keystore, temporary files. */
class VaultBiometricsTest {

    @TempDir
    lateinit var dir: File

    private val keystore = InMemorySlotKeystore()
    private val bioKeys = FakeBiometricKeys()
    private lateinit var state: StateStore
    private lateinit var binding: StoredBiometricBinding
    private lateinit var repo: VaultRepository

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val owner = "owner password".encodeToByteArray()
    private val decoyPassword = "decoy password".encodeToByteArray()

    @BeforeEach
    fun setUp() {
        state = StateStore(File(dir, StateStore.FILE_NAME), keystore)
        binding = StoredBiometricBinding(state, bioKeys)
        repo = VaultRepository(VaultFiles(dir), keystore, BruteForceGuard.forVault(state, FakeClock()), binding, fastParams)
    }

    private fun created(): VaultSession = (repo.openOrCreate(owner.copyOf()) as VaultRepository.CreateResult.Created).session

    private fun opened(password: ByteArray): VaultSession = (repo.unlock(password.copyOf()) as VaultRepository.UnlockResult.Opened).session

    private fun arm(session: VaultSession): Boolean {
        val cipher = repo.cipherToArmBiometrics(session) ?: return false
        return repo.armBiometrics(session, cipher)
    }

    private fun fingerprint(): BiometricUnlockResult {
        val start = repo.cipherToUnlockWithBiometrics()
        assertThat(start).isInstanceOf(BiometricBinding.Start.Ready::class.java)
        return repo.unlockWithBiometrics((start as BiometricBinding.Start.Ready).cipher)
    }

    private fun withDecoy(parent: VaultSession): VaultSession {
        val result = repo.configureDecoy(parent, decoyPassword.copyOf())
        return (result as VaultRepository.DecoyResult.Created).parent
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
    fun `an armed vault opens with the fingerprint, the same vault with its entries`() {
        val vault = repo.save(created(), listOf(entry("Banque")))
        assertThat(arm(vault)).isTrue()
        assertThat(repo.biometricStatus(vault)).isEqualTo(BiometricStatus.THIS_VAULT)
        assertThat(repo.biometricsArmed()).isTrue()
        vault.close()

        val result = fingerprint()
        assertThat(result).isInstanceOf(BiometricUnlockResult.Opened::class.java)
        val session = (result as BiometricUnlockResult.Opened).session
        assertThat(session.slot).isEqualTo(Slot.A)
        assertThat(session.entries.map { it.title }).containsExactly("Banque")
    }

    @Test
    fun `nothing armed, nothing offered`() {
        created()
        assertThat(repo.biometricsArmed()).isFalse()
        assertThat(repo.cipherToUnlockWithBiometrics()).isEqualTo(BiometricBinding.Start.NotArmed)
    }

    @Test
    fun `a vault with a decoy cannot arm, from the start or at the seal`() {
        val lone = created()
        // A decoy created between the start and the seal: the seal checks again.
        val cipher = repo.cipherToArmBiometrics(lone)!!
        val parent = withDecoy(lone)
        assertThat(repo.armBiometrics(parent, cipher)).isFalse()
        assertThat(repo.cipherToArmBiometrics(parent)).isNull()
        assertThat(repo.biometricsArmed()).isFalse()
    }

    @Test
    fun `creating a decoy disarms the parent first, the 2_7_0 flaw`() {
        val parent = created()
        assertThat(arm(parent)).isTrue()
        withDecoy(parent)
        assertThat(repo.biometricsArmed()).isFalse()
        assertThat(bioKeys.hasKey).isFalse()
    }

    @Test
    fun `a fingerprint never opens a vault that has a decoy, whatever armed it`() {
        val parent = withDecoy(created())
        // Armed behind every rule's back, as a purge that went missing would leave it.
        binding.arm(parent.meta.generation, parent.key.copyOf(), binding.cipherToArm())
        parent.close()
        assertThat(fingerprint()).isEqualTo(BiometricUnlockResult.Disarmed)
        assertThat(repo.biometricsArmed()).isFalse()
        opened(owner).close()
    }

    @Test
    fun `a secure chip that does not answer stops the decoy before anything is written, still armed`() {
        val parent = created()
        assertThat(arm(parent)).isTrue()
        bioKeys.unavailable = true
        assertThat(repo.configureDecoy(parent, decoyPassword.copyOf())).isEqualTo(VaultRepository.DecoyResult.KeystoreUnavailable)
        bioKeys.unavailable = false
        assertThat(repo.statuses().getValue(Slot.B).provablyFree).isTrue()
        assertThat(repo.biometricsArmed()).isTrue()
    }

    @Test
    fun `a deletion disarms`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        repo.deleteData(vault)
        assertThat(repo.biometricsArmed()).isFalse()
    }

    @Test
    fun `changing the armed vault's password disarms it, and says so`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        val result = repo.changePassword(vault, owner.copyOf(), "a new password".encodeToByteArray())
        assertThat((result as VaultRepository.ChangeResult.Changed).biometricsDisarmed).isTrue()
        assertThat(repo.biometricsArmed()).isFalse()
    }

    @Test
    fun `the decoy may arm itself, and a password change in its parent leaves it armed`() {
        val parent = withDecoy(created())
        parent.close()
        val decoy = opened(decoyPassword)
        assertThat(decoy.slot).isNotEqualTo(Slot.A)
        assertThat(arm(decoy)).isTrue()
        assertThat(repo.biometricStatus(decoy)).isEqualTo(BiometricStatus.THIS_VAULT)
        decoy.close()

        val real = opened(owner)
        // Known here, never shown: the settings screen says nothing about a vault armed elsewhere
        // (Patrice, 2026-09-22). What it decides is the purge below, not a line on a screen.
        assertThat(repo.biometricStatus(real)).isEqualTo(BiometricStatus.ANOTHER_VAULT)
        val changed = repo.changePassword(real, owner.copyOf(), "a new password".encodeToByteArray())
        assertThat((changed as VaultRepository.ChangeResult.Changed).biometricsDisarmed).isFalse()
        changed.session.close()

        // The fingerprint still opens the decoy, never the real vault.
        val result = fingerprint() as BiometricUnlockResult.Opened
        assertThat(result.session.slot).isEqualTo(decoy.slot)
    }

    @Test
    fun `a new fingerprint enrolled disarms, and the password still opens`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        vault.close()
        bioKeys.enrollFingerprint()
        assertThat(repo.cipherToUnlockWithBiometrics()).isEqualTo(BiometricBinding.Start.Invalidated)
        assertThat(repo.biometricsArmed()).isFalse()
        opened(owner).close()
    }

    @Test
    fun `a key sealed under another generation opens nothing, and disarms`() {
        val vault = created()
        val cipher = binding.cipherToArm()
        binding.arm("0123456789abcdef", vault.key.copyOf(), cipher)
        vault.close()
        assertThat(fingerprint()).isEqualTo(BiometricUnlockResult.Disarmed)
        assertThat(repo.biometricsArmed()).isFalse()
    }

    @Test
    fun `a generation swapped in the state does not authenticate, and disarms`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        vault.close()
        state.update { JsonObject(it + ("bio.gen" to JsonPrimitive("0123456789abcdef"))) }
        // The seal itself refuses, before the vault's own generation check.
        val start = repo.cipherToUnlockWithBiometrics() as BiometricBinding.Start.Ready
        assertThat(binding.open(start.cipher)).isNull()
        assertThat(fingerprint()).isEqualTo(BiometricUnlockResult.Disarmed)
        assertThat(repo.biometricsArmed()).isFalse()
    }

    @Test
    fun `a running lockout refuses the fingerprint`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        vault.close()
        val wrong = "not the password".encodeToByteArray()
        repeat(FAILURES_TO_LOCK) { repo.unlock(wrong.copyOf()) }
        assertThat(repo.lockoutRemainingMillis()).isGreaterThan(0L)
        assertThat(fingerprint()).isInstanceOf(BiometricUnlockResult.Locked::class.java)
        assertThat(repo.biometricsArmed()).isTrue()
    }

    @Test
    fun `a lost state reads as not armed, and arming again starts from a new key`() {
        val vault = created()
        assertThat(arm(vault)).isTrue()
        File(dir, StateStore.FILE_NAME).delete()
        assertThat(repo.biometricsArmed()).isFalse()
        assertThat(arm(vault)).isTrue()
        assertThat(bioKeys.created).isEqualTo(2)
    }

    private companion object {
        /** More than the vault's free attempts. */
        const val FAILURES_TO_LOCK = 8
    }
}
