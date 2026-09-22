package com.filestech.pass_tech.ui.entries

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.VaultManager
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
@HiltViewModel
class EntriesViewModel @Inject constructor(
    private val vault: VaultManager,
    private val clipboard: SensitiveClipboard,
) : ViewModel() {

    sealed interface Screen {
        data class Detail(val id: String) : Screen

        /** Compared by identity: two editors are two screens, even on the same entry. */
        class Edit(val form: EntryForm) : Screen
    }

    sealed interface Message {
        /** [clearedInSeconds] is `null` when the clipboard is never cleared. */
        data class Copied(@StringRes val label: Int, val clearedInSeconds: Int?) : Message

        data class Deleted(val title: String) : Message

        data object TitleRequired : Message

        data object SecretAdded : Message

        /** Nothing was written: the secure hardware did not answer. */
        data object KeystoreUnavailable : Message
    }

    private val mutableStack = MutableStateFlow<List<Screen>>(emptyList())

    /** The screens above the home, the top one last. */
    val stack: StateFlow<List<Screen>> = mutableStack.asStateFlow()

    private val messageChannel = Channel<Message>(Channel.BUFFERED)
    val messages: Flow<Message> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            vault.state.collect { if (it == VaultManager.State.Locked) mutableStack.value = emptyList() }
        }
    }

    fun openDetail(id: String) = push(Screen.Detail(id))

    fun openNew(type: EntryType) = push(Screen.Edit(EntryForm.new(type)))

    fun openEditor(entry: Entry) = push(Screen.Edit(EntryForm.edit(entry)))

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

    fun copy(value: String, @StringRes label: Int) {
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
