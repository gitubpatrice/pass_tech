package com.filestech.pass_tech.ui.entries

import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.phishing.AntiPhishing
import com.filestech.pass_tech.core.phishing.DomainMatch
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.core.vault.OccupancyMark
import com.filestech.pass_tech.core.vault.VaultFiles
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.core.vault.VaultRepository
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FakePhishingComponent
import com.filestech.pass_tech.testing.FixedDomain
import com.filestech.pass_tech.testing.InMemoryPreferences
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
        val domain: FixedDomain,
        val phishing: FakePhishingComponent,
        val antiPhishing: AntiPhishing,
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
            val domain = FixedDomain()
            val vault = VaultManager(VaultRepository(VaultFiles(dir), keystore, guard, heir, params = fastParams), domain, io)
            assertThat(vault.openOrCreate("renardclochesoleil2026".encodeToByteArray())).isEqualTo(VaultManager.CreateOutcome.Created)
            val clipboard = FakeClipboard(clearAfter = 30)
            val preferences = AppPreferences(InMemoryPreferences())
            val phishing = FakePhishingComponent()
            val antiPhishing = AntiPhishing(preferences, phishing, domain)
            val viewModel = EntriesViewModel(vault, clipboard, antiPhishing)
            val messages = mutableListOf<Message>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
            block(Setup(viewModel, vault, messages, clipboard, domain, phishing, antiPhishing))
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

    // The browser the password is about to be pasted into.

    /** An entry belonging to [url], and declaring [otherDomains] on top of it. */
    private fun site(url: String, vararg otherDomains: String) = Entry(
        id = "e1",
        title = "Ma banque",
        category = Entry.DEFAULT_CATEGORY,
        url = url,
        otherDomains = otherDomains.toList(),
        createdAt = DartDateTime.nowLocal(),
        updatedAt = DartDateTime.nowLocal(),
    )

    /** The protection on and granted, with the browser wherever [showing] says. */
    private suspend fun Setup.watching(showing: String?) {
        phishing.granted = true
        antiPhishing.turnOn()
        domain.host = showing
    }

    @Test
    fun `off, a password copies with nothing said, whatever the browser shows`() = runTest {
        opened { setup ->
            setup.domain.host = "evil.com"
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.clipboard.copied).containsExactly("s3cret")
            assertThat(setup.viewModel.domainAlert.value).isNull()
            assertThat(setup.messages.map { it::class }).containsExactly(Message.Copied::class)
        }
    }

    @Test
    fun `on the right site, a password copies with nothing said`() = runTest {
        opened { setup ->
            setup.watching("login.mabanque.fr")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.clipboard.copied).containsExactly("s3cret")
            assertThat(setup.viewModel.domainAlert.value).isNull()
        }
    }

    @Test
    fun `no browser read, the copy goes ahead and says the check could not be made`() = runTest {
        opened { setup ->
            setup.watching(showing = null)
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.clipboard.copied).containsExactly("s3cret")
            assertThat(setup.messages).contains(Message.DomainUnchecked)
        }
    }

    @Test
    fun `a look-alike domain holds the copy back, and can be overridden`() = runTest {
        opened { setup ->
            setup.watching("mabanque.co")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.clipboard.copied).isEmpty()
            val alert = setup.viewModel.domainAlert.value
            assertThat(alert?.check?.verdict).isEqualTo(DomainMatch.Verdict.TYPOSQUATTING)
            assertThat(alert?.check?.active).isEqualTo("mabanque.co")

            setup.viewModel.copyAnyway()
            settle()
            assertThat(setup.clipboard.copied).containsExactly("s3cret")
            assertThat(setup.viewModel.domainAlert.value).isNull()
        }
    }

    @Test
    fun `another domain holds the copy back, and there is no way through`() = runTest {
        opened { setup ->
            setup.watching("evil.com")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.viewModel.domainAlert.value?.check?.verdict).isEqualTo(DomainMatch.Verdict.MISMATCH)

            // The screen draws no button for it, and the model refuses it as well.
            setup.viewModel.copyAnyway()
            settle()
            assertThat(setup.clipboard.copied).isEmpty()
        }
    }

    @Test
    fun `a domain the entry declares copies with nothing said`() = runTest {
        opened { setup ->
            setup.watching("login.microsoftonline.com")
            val office = site("office.com", "login.microsoftonline.com")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = office)
            settle()
            assertThat(setup.clipboard.copied).containsExactly("s3cret")
            assertThat(setup.viewModel.domainAlert.value).isNull()
        }
    }

    @Test
    fun `a plain mismatch opens the entry with the refused domain offered, and copies nothing`() = runTest {
        opened { setup ->
            setup.watching("login.microsoftonline.com")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("office.com"))
            settle()
            assertThat(setup.viewModel.domainAlert.value?.check?.verdict).isEqualTo(DomainMatch.Verdict.MISMATCH)

            setup.viewModel.openToDeclareDomain()
            settle()
            // The way out is an editor, not a copy: nothing reached the clipboard.
            assertThat(setup.clipboard.copied).isEmpty()
            assertThat(setup.viewModel.domainAlert.value).isNull()
            val editor = setup.viewModel.stack.value.last() as Screen.Edit
            assertThat(editor.form.suggestedDomain).isEqualTo("login.microsoftonline.com")
            assertThat(editor.form.otherDomains).isEmpty()

            // And offering it is not declaring it: the field only fills when the owner taps.
            editor.form.acceptSuggestedDomain()
            assertThat(editor.form.otherDomains).isEqualTo("login.microsoftonline.com")
            assertThat(editor.form.suggestedDomain).isNull()
        }
    }

    @Test
    fun `a look-alike domain cannot be declared, only copied past`() = runTest {
        opened { setup ->
            setup.watching("mabanque.co")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.viewModel.domainAlert.value?.check?.verdict).isEqualTo(DomainMatch.Verdict.TYPOSQUATTING)

            // The dialog draws no such button here, and the model refuses it as well: a name one
            // letter from the owner's is the shape of an attack, never a domain to trust for good.
            setup.viewModel.openToDeclareDomain()
            settle()
            assertThat(setup.viewModel.stack.value.filterIsInstance<Screen.Edit>()).isEmpty()
        }
    }

    @Test
    fun `only a value that belongs to a site is checked`() = runTest {
        opened { setup ->
            setup.watching("evil.com")
            // A username, a card number, a note: nothing the browser could be compared against.
            setup.viewModel.copy("alice", R.string.entry_detail_field_username)
            settle()
            assertThat(setup.clipboard.copied).containsExactly("alice")
            assertThat(setup.viewModel.domainAlert.value).isNull()

            // An entry that names no site copies the same way.
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site(""))
            settle()
            assertThat(setup.clipboard.copied).containsExactly("alice", "s3cret").inOrder()
            assertThat(setup.viewModel.domainAlert.value).isNull()
        }
    }

    @Test
    fun `the held-back copy goes with the lock`() = runTest {
        opened { setup ->
            setup.watching("evil.com")
            setup.viewModel.copy("s3cret", R.string.entry_detail_field_password, site = site("mabanque.fr"))
            settle()
            assertThat(setup.viewModel.domainAlert.value).isNotNull()

            setup.vault.lock()
            settle()
            assertThat(setup.viewModel.domainAlert.value).isNull()
        }
    }
}
