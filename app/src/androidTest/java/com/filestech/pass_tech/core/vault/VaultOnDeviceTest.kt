package com.filestech.pass_tech.core.vault

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.VaultManager.CreateOutcome
import com.filestech.pass_tech.core.vault.VaultManager.DecoyOutcome
import com.filestech.pass_tech.core.vault.VaultManager.State
import com.filestech.pass_tech.core.vault.VaultManager.UnlockOutcome
import com.filestech.pass_tech.di.VaultModule
import com.filestech.pass_tech.testing.PrefixedKeystore
import com.filestech.pass_tech.testing.keyLevel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The vault as the app builds it (the providers of [VaultModule]), on the real Keystore and the real
 * file system, with the real Argon2id parameters. Two sandboxes keep it away from the app it runs in:
 * a scratch directory in place of `filesDir`, and prefixed Keystore aliases.
 *
 * What only a device can tell: that the whole cycle holds on the TEE (Galaxy S9) and StrongBox
 * (Galaxy S24), and what an unlock costs there. The timings go to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class VaultOnDeviceTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val sandbox = File(target.cacheDir, "vault-on-device-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val keystore = PrefixedKeystore(AndroidSlotKeystore())
    private lateinit var manager: VaultManager

    @Before
    fun setUp() {
        val context: Context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = sandbox
        }
        val repository = VaultModule.vaultRepository(context, keystore, VaultModule.stateStore(context, keystore))
        manager = VaultManager(repository, Dispatchers.IO)
    }

    @After
    fun cleanUp() {
        runBlocking { manager.lock() }
        keystore.deleteCreated()
        sandbox.deleteRecursively()
    }

    private fun owner() = "Owner — pass phrase ✓ 2026".encodeToByteArray()

    private fun decoy() = "Decoy — pass phrase ✓ 2026".encodeToByteArray()

    private fun entry(title: String) = Entry(
        id = title,
        title = title,
        category = "Web",
        password = "secret-$title",
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    private fun open() = manager.state.value as State.Open

    @Test
    fun createUnlockDecoyAndDelete() = runBlocking {
        assertThat(manager.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(manager.openOrCreate(owner())).isEqualTo(CreateOutcome.Created)
        assertThat(manager.updateEntries { it + entry("bank") }).isTrue()

        // Every slot file exists, all the same size, and no temporary file is left behind.
        val files = sandbox.listFiles().orEmpty().map { it.name }
        assertThat(files).containsExactly("pt_vault_a.enc", "pt_vault_b.enc", "pt_vault_c.enc", StateStore.FILE_NAME)
        assertThat(Slot.entries.map { File(sandbox, it.vaultFileName).length() }.distinct()).hasSize(1)

        assertThat(manager.configureDecoy(decoy())).isEqualTo(DecoyOutcome.Created)
        assertThat(open().hasDecoy).isTrue()
        manager.lock()

        // The decoy opens as a fresh vault of its own, and its deletion leaves the real one whole.
        assertThat(manager.unlock(decoy())).isEqualTo(UnlockOutcome.Opened)
        assertThat(open()).isEqualTo(State.Open(emptyList(), hasDecoy = false))
        assertThat(manager.deleteData()).isTrue()
        assertThat(manager.entryMode()).isEqualTo(VaultRepository.EntryMode.CREATE)
        assertThat(manager.unlock(decoy())).isEqualTo(UnlockOutcome.WrongPassword)

        // The creation form opens the real vault with its password, as it is.
        assertThat(manager.openOrCreate(owner())).isEqualTo(CreateOutcome.Opened)
        assertThat(open().entries.map { it.title }).containsExactly("bank")
        assertThat(open().hasDecoy).isFalse()
        assertThat(manager.entryMode()).isEqualTo(VaultRepository.EntryMode.UNLOCK)
    }

    @Test
    fun timings() = runBlocking {
        val create = measure { manager.openOrCreate(owner()) }
        val saves = List(RUNS) { index -> measure { manager.updateEntries { it + entry("entry $index") } } }
        manager.lock()
        val unlocks = List(RUNS) {
            measure { assertThat(manager.unlock(owner())).isEqualTo(UnlockOutcome.Opened) }.also { manager.lock() }
        }
        // Fewer than the five free attempts: no lockout delay gets in the measure.
        val wrong = List(WRONG_RUNS) { measure { assertThat(manager.unlock(decoy())).isEqualTo(UnlockOutcome.WrongPassword) } }
        val levels = (Slot.entries.map { it.hardwareKeyAlias } + OccupancyMark.KEY_ALIAS + StateStore.KEY_ALIAS)
            .joinToString { "$it=${keyLevel(keystore.realAlias(it))}" }
        Log.i(
            TAG,
            "${Build.MODEL} API ${Build.VERSION.SDK_INT} | keys: $levels | create $create ms | save $saves median ${median(saves)} | " +
                "unlock $unlocks median ${median(unlocks)} | wrong password $wrong median ${median(wrong)} (ms)",
        )
        // A generous ceiling, only to catch a pathological regression: the figures that matter are in the log.
        assertThat(median(unlocks)).isLessThan(CEILING_MS)
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private companion object {
        const val TAG = "PassTechVault"
        const val RUNS = 5
        const val WRONG_RUNS = 3
        const val CEILING_MS = 5_000L
    }
}
