package com.filestech.pass_tech.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.pass_tech.core.audit.VaultAudit
import com.filestech.pass_tech.core.breach.BreachCheck
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.state.Clock
import com.filestech.pass_tech.core.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The security audit of the open vault. Everything it knows lives here and dies with the lock: no
 * result is written anywhere, and the breach answers are kept as hashes.
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val vault: VaultManager,
    private val breach: BreachCheck,
    private val clock: Clock,
) : ViewModel() {

    data class UiState(
        val audit: VaultAudit.Result = EMPTY,
        val checking: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** How many passwords the last run could not ask about. They stay outside the score. */
        val failed: Int = 0,
        val problem: Problem? = null,
    )

    enum class Problem {
        /** Not one request came back. */
        NETWORK,

        /**
         * The launcher is showing a calculator. Reaching the network at all would contradict it, so
         * nothing was sent — and the screen says why rather than looking broken.
         */
        DISGUISED,
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** Hashes, never passwords: see [VaultAudit]. They grow across runs, so a retry adds to them. */
    private var breachedHashes: Set<String>? = null
    private var checkedHashes: Set<String>? = null

    init {
        viewModelScope.launch {
            vault.state.collect { state ->
                if (state is VaultManager.State.Open) {
                    analyse(state.entries)
                } else {
                    // The vault closed: the audit goes with it, results included.
                    breachedHashes = null
                    checkedHashes = null
                    mutableState.value = UiState()
                }
            }
        }
    }

    /** The refresh button of 2.7.1: the entries may have changed under the screen. */
    fun refresh() {
        (vault.state.value as? VaultManager.State.Open)?.let { analyse(it.entries) }
    }

    fun runBreachCheck() {
        if (mutableState.value.checking) return
        val entries = (vault.state.value as? VaultManager.State.Open)?.entries ?: return
        val passwords = entries.filter { it.type == EntryType.PASSWORD && it.password.isNotEmpty() }.map { it.password }
        mutableState.update { it.copy(checking = true, done = 0, total = passwords.distinct().size, problem = null, failed = 0) }
        viewModelScope.launch {
            val outcome = breach.check(passwords) { done, total ->
                mutableState.update { it.copy(done = done, total = total) }
            }
            when (outcome) {
                is BreachCheck.Outcome.Done -> {
                    breachedHashes = (breachedHashes ?: emptySet()) + outcome.breached
                    checkedHashes = (checkedHashes ?: emptySet()) + outcome.checked
                    mutableState.update { it.copy(checking = false, failed = outcome.failed) }
                }
                BreachCheck.Outcome.Disguised ->
                    mutableState.update { it.copy(checking = false, problem = Problem.DISGUISED) }
                BreachCheck.Outcome.NoNetwork ->
                    mutableState.update { it.copy(checking = false, problem = Problem.NETWORK) }
            }
            refresh()
        }
    }

    fun problemSeen() {
        mutableState.update { it.copy(problem = null) }
    }

    private fun analyse(entries: List<Entry>) {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.wallMillis()), ZoneId.systemDefault())
        val audit = VaultAudit.of(entries, now, breachedHashes, checkedHashes)
        mutableState.update { it.copy(audit = audit) }
    }

    private companion object {
        val EMPTY = VaultAudit.Result(score = null, partial = false)
    }
}
