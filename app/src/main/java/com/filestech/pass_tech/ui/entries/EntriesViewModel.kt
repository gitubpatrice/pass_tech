package com.filestech.pass_tech.ui.entries

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.phishing.AntiPhishing
import com.filestech.pass_tech.core.phishing.DomainMatch
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.generator.GeneratorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * The screens of an open vault above the home: an entry's detail and the editor. The stack lives here,
 * not in the activity's saved state, and is dropped every time the vault locks: an entry being typed
 * does not outlive the lock.
 */
// One function per user action on the screens above the home (open, save, delete, star, copy, generate):
// splitting them would split one stack across classes.
@Suppress("TooManyFunctions")
@HiltViewModel
class EntriesViewModel @Inject constructor(
    private val vault: VaultManager,
    private val clipboard: SensitiveClipboard,
    private val antiPhishing: AntiPhishing,
) : ViewModel() {

    /**
     * A copy held back because the browser is not where the entry says it should be. Not a data
     * class: [value] is the secret itself, and a generated `toString` would put it in any log that
     * ever printed this object.
     */
    class DomainAlert(val check: DomainMatch.Check, internal val value: String, @StringRes internal val label: Int)

    sealed interface Screen {
        data class Detail(val id: String) : Screen

        /** Compared by identity: two editors are two screens, even on the same entry. */
        class Edit(val form: EntryForm) : Screen

        /** [target]: the editor whose password "Use" fills, or `null` when opened from the home. */
        class Generator(val state: GeneratorState, val target: EntryForm?) : Screen

        data object Settings : Screen

        data object Audit : Screen

        data object About : Screen
    }

    sealed interface Message {
        /** [clearedInSeconds] is `null` when the clipboard is never cleared. */
        data class Copied(@StringRes val label: Int, val clearedInSeconds: Int?) : Message

        data class Deleted(val title: String) : Message

        data object TitleRequired : Message

        data object SecretAdded : Message

        data object GeneratedCopied : Message

        /** Nothing was written: the secure hardware did not answer. */
        data object KeystoreUnavailable : Message

        /** The copy went ahead, but the protection the owner asked for could not read any browser. */
        data object DomainUnchecked : Message
    }

    private val mutableStack = MutableStateFlow<List<Screen>>(emptyList())

    /** The screens above the home, the top one last. */
    val stack: StateFlow<List<Screen>> = mutableStack.asStateFlow()

    private val mutableAlert = MutableStateFlow<DomainAlert?>(null)

    /** Set while the dialog holding a copy back is up. */
    val domainAlert: StateFlow<DomainAlert?> = mutableAlert.asStateFlow()

    private val messageChannel = Channel<Message>(Channel.BUFFERED)
    val messages: Flow<Message> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            vault.state.collect {
                if (it != VaultManager.State.Locked) return@collect
                mutableStack.value = emptyList()
                // It holds a password waiting to be copied: it goes with the screens it was raised over.
                mutableAlert.value = null
            }
        }
    }

    fun openDetail(id: String) = push(Screen.Detail(id))

    fun openNew(type: EntryType) = push(Screen.Edit(EntryForm.new(type)))

    fun openEditor(entry: Entry) = push(Screen.Edit(EntryForm.edit(entry)))

    fun openSettings() = push(Screen.Settings)

    fun openAudit() = push(Screen.Audit)

    fun openAbout() = push(Screen.About)

    fun openGenerator(target: EntryForm? = null) = push(Screen.Generator(GeneratorState(), target))

    /** "Use": the generated password goes into the editor's password field, and the generator closes. */
    fun useGenerated(screen: Screen.Generator) {
        screen.target?.password = screen.state.password
        close(screen)
    }

    fun copyGenerated(value: String) {
        clipboard.copy(value)
        send(Message.GeneratedCopied)
    }

    fun close(screen: Screen) {
        mutableStack.update { stack -> stack.filterNot { it === screen } }
    }

    fun save(screen: Screen.Edit) {
        val form = screen.form
        if (form.saving) return
        when (form.validate()) {
            EntryForm.Problem.TITLE_REQUIRED -> return send(Message.TitleRequired)
            EntryForm.Problem.INVALID_TOTP -> return
            null -> Unit
        }
        form.saving = true
        val entry = form.toEntry(DartDateTime.nowLocal()) { UUID.randomUUID().toString() }
        write(onDone = { close(screen) }, finally = { form.saving = false }) { entries ->
            if (form.isEdit) entries.map { if (it.id == entry.id) entry else it } else entries + entry
        }
    }

    /** The detail of [entry], if it shows, closes with it. */
    fun delete(entry: Entry) {
        write(
            onDone = {
                mutableStack.update { stack -> stack.filterNot { it == Screen.Detail(entry.id) } }
                send(Message.Deleted(entry.title))
            },
        ) { entries -> entries.filterNot { it.id == entry.id } }
    }

    /** Only the star changes: not the modification date, which 2.7.1 moved and the sort follows. */
    fun toggleFavorite(id: String) {
        write { entries -> entries.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it } }
    }

    /**
     * [site] is the URL the entry belongs to, and is given only for what an impostor site is after: a
     * password, a two-factor code. Everything else copies straight away, as does a secret from an
     * entry that names no site — there would be nothing to compare the browser against.
     */
    fun copy(value: String, @StringRes label: Int, site: String? = null) {
        if (site.isNullOrBlank()) return copyNow(value, label)
        viewModelScope.launch {
            val check = antiPhishing.check(site)
            when (check.verdict) {
                DomainMatch.Verdict.OK -> copyNow(value, label)
                // Asked for, and could not be made. Saying nothing would let a protection that sees
                // nothing pass for one that saw nothing wrong.
                DomainMatch.Verdict.UNKNOWN -> {
                    copyNow(value, label)
                    send(Message.DomainUnchecked)
                }
                DomainMatch.Verdict.TYPOSQUATTING,
                DomainMatch.Verdict.MISMATCH,
                -> mutableAlert.value = DomainAlert(check, value, label)
            }
        }
    }

    /** The dialog's only way through, and only on a look-alike domain. */
    fun copyAnyway() {
        val alert = mutableAlert.value ?: return
        mutableAlert.value = null
        // Checked here and not only in the dialog: a different domain has no way through at all, and
        // that must not depend on which buttons a screen happened to draw.
        if (alert.check.verdict != DomainMatch.Verdict.TYPOSQUATTING) return
        copyNow(alert.value, alert.label)
    }

    fun dismissDomainAlert() {
        mutableAlert.value = null
    }

    fun openAccessibilitySettings() {
        antiPhishing.openSystemSettings()
    }

    private fun copyNow(value: String, @StringRes label: Int) {
        send(Message.Copied(label, clipboard.copy(value)))
    }

    fun secretAdded() = send(Message.SecretAdded)

    private fun push(screen: Screen) {
        mutableStack.update { it + screen }
    }

    private fun send(message: Message) {
        messageChannel.trySend(message)
    }

    /** Nothing happens if the vault locked in the meantime: the stack is gone with it. */
    private fun write(onDone: () -> Unit = {}, finally: () -> Unit = {}, transform: (List<Entry>) -> List<Entry>) {
        viewModelScope.launch {
            try {
                if (vault.updateEntries(transform)) onDone()
            } catch (_: KeystoreUnavailableException) {
                send(Message.KeystoreUnavailable)
            } finally {
                finally()
            }
        }
    }
}
