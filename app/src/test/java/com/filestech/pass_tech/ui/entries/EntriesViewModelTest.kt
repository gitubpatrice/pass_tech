package com.filestech.pass_tech.ui.entries

import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.OccupancyMark
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.filestech.pass_tech.testing.heirRepository
import com.filestech.pass_tech.ui.entries.EntriesViewModel.Message
import com.filestech.pass_tech.ui.entries.EntriesViewModel.Screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The screens above the home, on a real vault (in-memory Keystore, temporary files). */
@OptIn(ExperimentalCoroutinesApi::class)
class EntriesViewModelTest {

    @TempDir
    lateinit var dir: File

    private val fastParams = KdfParams(memoryKiB = KdfParams.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)
    private val keystore = InMemorySlotKeystore()

    private class FakeClipboard(var clearAfter: Int?) : SensitiveClipboard {
        val copied = mutableListOf<String>()

        override fun copy(text: String): Int? {
            copied += text
            return clearAfter
        }

        override fun clear() = Unit
    }

    private data class Setup(
        val viewModel: EntriesViewModel,
        val vault: VaultManager,
        val messages: List<Message>,
        val clipboard: FakeClipboard,
    )

    private suspend fun TestScope.opened(block: suspend TestScope.(Setup) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val clock = FakeClock()
            val store = StateStore(File(dir, StateStore.FILE_NAME), keystore)
            val guard = BruteForceGuard.forVault(store, clock)
            val heir = heirRepository(dir, keystore, store, clock, fastParams)
            // The vault on the test scheduler too: no write left on a real thread to come back to Main after the test.
            val io = StandardTestDispatcher(testScheduler)
            val vault = VaultManager(VaultRepository(VaultFiles(dir), keystore, guard, heir, params = fastParams), io)
            assertThat(vault.openOrCreate("renardclochesoleil2026".encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
            val clipboard = FakeClipboard(clearAfter = 30)
            val viewModel = EntriesViewModel(vault, clipboard)
            val messages = mutableListOf<Message>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
            block(Setup(viewModel, vault, messages, clipboard))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun VaultManager.entries() = (state.value as VaultManager.State.Open).entries

    /** Lets the view model's coroutine run, and the vault write it started: both are on the test scheduler. */
    private fun TestScope.settle() = testScheduler.advanceUntilIdle()

    private fun EntriesViewModel.editor() = stack.value.last() as Screen.Edit

    @Test
    fun `a new entry is saved into the vault and its editor closes`() = runTest {
        opened { (viewModel, vault) ->
            viewModel.openNew(EntryType.CARD)
            val editor = viewModel.editor()
            editor.form.title = "Visa"
            viewModel.save(editor)
            settle()
            assertThat(vault.entries().map { it.title }).containsExactly("Visa")
            assertThat(vault.entries().single().category).isEqualTo("Banque")
            assertThat(viewModel.stack.value).isEmpty()
        }
    }

    @Test
    fun `no title, nothing written, the editor stays and says why`() = runTest {
        opened { (viewModel, vault, messages) ->
            viewModel.openNew(EntryType.NOTE)
            viewModel.save(viewModel.editor())
            settle()
            assertThat(vault.entries()).isEmpty()
            assertThat(viewModel.stack.value).hasSize(1)
            assertThat(messages).containsExactly(Message.TitleRequired)
        }
    }

    @Test
    fun `an edit replaces the entry in place, same id, same creation date`() = runTest {
        opened { (viewModel, vault) ->
            viewModel.openNew(EntryType.PASSWORD)
            viewModel.editor().form.title = "Mail"
            viewModel.save(viewModel.editor())
            settle()
            val first = vault.entries().single()
            viewModel.openDetail(first.id)
            viewModel.openEditor(first)
            viewModel.editor().form.username = "alice"
            viewModel.save(viewModel.editor())
            settle()
            val edited = vault.entries().single()
            assertThat(edited.id).isEqualTo(first.id)
            assertThat(edited.username).isEqualTo("alice")
            assertThat(edited.createdAt).isEqualTo(first.createdAt)
            assertThat(viewModel.stack.value).containsExactly(Screen.Detail(first.id))
        }
    }

    @Test
    fun `starring an entry does not move its modification date`() = runTest {
        opened { (viewModel, vault) ->
            viewModel.openNew(EntryType.NOTE)
            viewModel.editor().form.title = "Wi-Fi"
            viewModel.save(viewModel.editor())
            settle()
            val before = vault.entries().single()
            viewModel.toggleFavorite(before.id)
            settle()
            val after = vault.entries().single()
            assertThat(after.isFavorite).isTrue()
            assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        }
    }

    @Test
    fun `deleting closes the entry's detail and says so`() = runTest {
        opened { (viewModel, vault, messages) ->
            viewModel.openNew(EntryType.NOTE)
            viewModel.editor().form.title = "Wi-Fi"
            viewModel.save(viewModel.editor())
            settle()
            val entry = vault.entries().single()
            viewModel.openDetail(entry.id)
            viewModel.delete(entry)
            settle()
            assertThat(vault.entries()).isEmpty()
            assertThat(viewModel.stack.value).isEmpty()
            assertThat(messages).containsExactly(Message.Deleted("Wi-Fi"))
        }
    }

    @Test
    fun `Use puts the generated password into the editor's field and closes the generator`() = runTest {
        opened { (viewModel) ->
            viewModel.openNew(EntryType.PASSWORD)
            val editor = viewModel.editor()
            viewModel.openGenerator(editor.form)
            val generator = viewModel.stack.value.last() as Screen.Generator
            viewModel.useGenerated(generator)
            assertThat(editor.form.password).isEqualTo(generator.state.password)
            assertThat(editor.form.password).isNotEmpty()
            assertThat(viewModel.stack.value).containsExactly(editor)
        }
    }

    @Test
    fun `the generator opened from the home fills nothing`() = runTest {
        opened { (viewModel) ->
            viewModel.openGenerator()
            val generator = viewModel.stack.value.single() as Screen.Generator
            assertThat(generator.target).isNull()
        }
    }

    @Test
    fun `locking drops every screen, an entry being typed included`() = runTest {
        opened { (viewModel, vault) ->
            viewModel.openNew(EntryType.PASSWORD)
            viewModel.editor().form.password = "typed but not saved"
            vault.lock()
            testScheduler.advanceUntilIdle()
            assertThat(viewModel.stack.value).isEmpty()
        }
    }

    @Test
    fun `a copy says when the clipboard clears, or nothing when it never does`() = runTest {
        opened { setup ->
            val (viewModel, vault, messages) = setup
            val clipboard = setup.clipboard
            viewModel.copy("secret", R.string.entry_detail_field_password)
            clipboard.clearAfter = null
            viewModel.copy("alice", R.string.entry_detail_field_username)
            settle()
            assertThat(clipboard.copied).containsExactly("secret", "alice").inOrder()
            assertThat(messages).containsExactly(
                Message.Copied(R.string.entry_detail_field_password, 30),
                Message.Copied(R.string.entry_detail_field_username, null),
            ).inOrder()
        }
    }

    @Test
    fun `a Keystore that does not answer writes nothing, keeps the editor and lets the user retry`() = runTest {
        opened { (viewModel, vault, messages) ->
            viewModel.openNew(EntryType.NOTE)
            val editor = viewModel.editor()
            editor.form.title = "Wi-Fi"
            keystore.unavailable += OccupancyMark.KEY_ALIAS
            viewModel.save(editor)
            settle()
            assertThat(vault.entries()).isEmpty()
            assertThat(viewModel.stack.value).containsExactly(editor)
            assertThat(editor.form.saving).isFalse()
            assertThat(messages).containsExactly(Message.KeystoreUnavailable)

            keystore.unavailable.clear()
            viewModel.save(editor)
            settle()
            assertThat(vault.entries().map { it.title }).containsExactly("Wi-Fi")
        }
    }
}
