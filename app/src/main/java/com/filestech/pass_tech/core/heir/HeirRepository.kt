package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.vault.KeyResult
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.filestech.pass_tech.core.vault.Padding
import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.SlotKeystore
import com.filestech.pass_tech.core.vault.valueOrNull

/**
 * The heir's side of the vault (design v2 §8): a snapshot of the entries, sealed by a passphrase the
 * owner hands over out of band, which opens only once the vault has been silent long enough.
 *
 * Rules that hold everywhere below:
 * - **A heir snapshot belongs to one vault incarnation.** Configuring one from a decoy really works,
 *   and touches nothing of any other slot — the same promise the vault itself makes.
 * - **The reading tries every slot, always all of them**, so the time an attempt takes says nothing
 *   about which slot answered, exactly as an unlock does.
 * - **A passphrase that opens a snapshot whose vault is not yet due is refused in the same words as a
 *   wrong passphrase.** The unlock screen offers heir access on every phone, configured or not
 *   (design v2 §8): there is nothing to read from the offer, and nothing to read from the refusal.
 * - **No REAL snapshot of another slot is ever rewritten.** Its passphrase is the only thing that
 *   could seal it again, so rewriting "the others" would destroy an heir belonging to a vault this
 *   one must not even know about. What can be rewritten is this app's own dummies, and only because
 *   [HeirMark] lets it say which those are: without that they never caught up in size, and their
 *   size then said which slot had an heir (design §21).
 *
 * Not thread-safe: the caller serialises the calls.
 */
class HeirRepository(
    private val files: HeirSnapshotFiles,
    private val keystore: SlotKeystore,
    private val guard: BruteForceGuard,
    private val state: HeirState,
    private val params: KdfParams = KdfParams.OWASP_MOBILE_2024,
) {

    sealed interface UnlockResult {
        class Opened(val entries: List<Entry>) : UnlockResult

        /** A wrong passphrase, or a vault that is not due yet. The same answer, on purpose. */
        data object Refused : UnlockResult

        data class Locked(val remainingMillis: Long) : UnlockResult

        data object KeystoreUnavailable : UnlockResult
    }

    fun status(generation: String): HeirState.Status = state.status(generation)

    fun setThreshold(generation: String, thresholdDays: Int) = state.setThreshold(generation, thresholdDays)

    /** This vault was opened: the silence starts again from now. */
    fun markActive(generation: String) = state.markActive(generation)

    fun forget(generation: String) = state.forget(generation)

    fun forgetAll() = state.forgetAll()

    /**
     * Takes the snapshot of [entries] for [generation] in [slot] and turns the heir on. Also replaces
     * an earlier snapshot of the same vault, which is how 2.7.1's "update" works: a new passphrase
     * simply replaces the old one, with nothing to check against it.
     *
     * Throws [KeystoreUnavailableException], having written nothing, if the hardware did not answer.
     */
    fun configure(slot: Slot, generation: String, entries: List<Entry>, passphrase: ByteArray, thresholdDays: Int) {
        val header = HeirContainer.newHeader(slot, params)
        val key = when (val derived = HeirContainer.deriveKey(header, passphrase, keystore)) {
            is KeyResult.Done -> derived.value
            KeyResult.Unavailable -> throw KeystoreUnavailableException()
            // The slot has no hardware key: it holds no vault, so there is no vault to snapshot either.
            else -> error("no hardware key for the open vault's slot")
        }
        val plain = HeirContainer.payloadOf(HeirSnapshot(generation, entries))
        val bucket = maxOf(Padding.bucketFor(plain.size), largestBucketOnDisk())
        val content = key.useThenWipe { sealing ->
            plain.useThenWipe { Padding.pad(it, bucket) }.useThenWipe { padded ->
                HeirContainer.seal(header, sealing, padded, HeirMark.real.seal(slot, keystore))
            }
        }
        writeSnapshot(slot, content, bucket)
        state.enable(generation, thresholdDays)
    }

    /** Shreds this vault's snapshot and forgets its state. The other slots are left untouched. */
    fun disable(slot: Slot, generation: String) {
        val bucket = largestBucketOnDisk()
        writeSnapshot(slot, dummy(slot, bucket), bucket)
        state.forget(generation)
    }

    /**
     * Replaces the snapshots of [slots] with ones nobody can open: what a deletion of those vaults
     * must do. Forgetting the state alone would leave, on disk, a full copy of every secret of an
     * erased vault, sealed by a passphrase its owner had handed to someone else.
     *
     * Only files that exist are replaced: a phone that never had an heir keeps none.
     */
    fun shred(slots: Set<Slot>) {
        val bucket = largestBucketOnDisk()
        val updates = slots.filter { files.read(it) != null }.associateWith { dummy(it, bucket) }
        if (updates.isNotEmpty()) files.writeAll(updates)
    }

    /**
     * Reads the entries an heir may see. Every slot is tried, and the snapshot only opens the door if
     * ITS vault has been silent for the threshold plus the grace days.
     */
    fun unlock(passphrase: ByteArray): UnlockResult {
        val gate = try {
            guard.gate()
        } catch (_: KeystoreUnavailableException) {
            return UnlockResult.KeystoreUnavailable
        }
        if (gate is BruteForceGuard.Gate.Locked) return UnlockResult.Locked(gate.remainingMillis)
        return try {
            guard.beginAttempt()
            settle(tryAllSlots(passphrase))
        } catch (_: KeystoreUnavailableException) {
            UnlockResult.KeystoreUnavailable
        }
    }

    private fun settle(attempt: Attempt): UnlockResult =
        when (attempt) {
            is Attempt.Opened -> {
                guard.succeeded()
                UnlockResult.Opened(attempt.entries)
            }
            Attempt.Indeterminate -> {
                guard.failed()
                UnlockResult.KeystoreUnavailable
            }
            Attempt.NoMatch -> {
                guard.failed()
                UnlockResult.Refused
            }
        }

    private sealed interface Attempt {
        class Opened(val entries: List<Entry>) : Attempt

        data object NoMatch : Attempt

        data object Indeterminate : Attempt
    }

    /** The same work on every slot, on a synthetic header when a slot has no readable snapshot. */
    private fun tryAllSlots(passphrase: ByteArray): Attempt {
        var opened: Attempt.Opened? = null
        var indeterminate = false
        for (slot in Slot.entries) {
            when (val attempt = tryOpen(slot, passphrase)) {
                is Attempt.Opened -> if (opened == null) opened = attempt
                Attempt.Indeterminate -> indeterminate = true
                Attempt.NoMatch -> Unit
            }
        }
        return opened ?: if (indeterminate) Attempt.Indeterminate else Attempt.NoMatch
    }

    private fun tryOpen(slot: Slot, passphrase: ByteArray): Attempt {
        val parsed = files.read(slot)?.let { HeirContainer.parseOrNull(it, slot) }
        val derived = HeirContainer.deriveKey(parsed?.header ?: HeirContainer.newHeader(slot, params), passphrase, keystore)
        val key = derived.valueOrNull()
        return when {
            parsed == null -> {
                key?.wipe()
                Attempt.NoMatch
            }
            derived == KeyResult.Unavailable -> Attempt.Indeterminate
            key == null -> Attempt.NoMatch
            else -> openWith(parsed, key)
        }
    }

    private fun openWith(parsed: HeirContainer.Parsed, key: ByteArray): Attempt {
        val snapshot = key.useThenWipe { HeirContainer.openOrNull(parsed, it) }?.useThenWipe(HeirContainer::snapshotOrNull)
        // A snapshot whose vault still answers is no answer at all: the same refusal as a wrong passphrase.
        return if (snapshot != null && state.status(snapshot.generation).due) Attempt.Opened(snapshot.entries) else Attempt.NoMatch
    }

    /**
     * Writes [slot]'s snapshot, and brings every other slot to [bucket] in the SAME write: a missing
     * file becomes a dummy, and a dummy smaller than [bucket] is made again at that size.
     *
     * A file that says it is a **real** snapshot is never replaced — it may be the heir of a vault
     * this one is not supposed to know exists, and rewriting it would destroy that heir. Neither is
     * one whose mark cannot be read. Both then stay at their own size until their own vault next
     * writes, which is the residual the design states for the vaults themselves (§11 point 2).
     *
     * **What this closes.** Before the mark, nothing could tell a dummy from a real snapshot, so no
     * existing file was ever touched and the dummies never caught up at all — on the ordinary phone,
     * one vault with an heir and two dummies, the two stayed at 64 KiB while the real one grew to
     * 256 KiB, and the sizes said which slot had the heir. The design called that the same residual
     * as the vaults', and it was not: there, a FREE slot is realigned at every single save.
     */
    private fun writeSnapshot(slot: Slot, content: String, bucket: Int) {
        val updates = mutableMapOf(slot to content)
        for (other in Slot.entries - slot) {
            if (needsDummy(other, bucket)) updates[other] = dummy(other, bucket)
        }
        files.writeAll(updates)
    }

    /**
     * Whether [slot]'s file is one this vault may write a dummy over, and one that is not already big
     * enough. Reading the mark costs a Keystore call; a silent Keystore reads as UNKNOWN, like every
     * other answer that is not a plain "this is a dummy".
     */
    private fun needsDummy(slot: Slot, bucket: Int): Boolean {
        val content = files.read(slot) ?: return true
        // Unreadable, unmarked, or a mark the Keystore would not open: UNKNOWN, and left alone.
        val parsed = HeirContainer.parseOrNull(content, slot) ?: return false
        val mark = parsed.mark?.let { HeirMark.openOrNull(it, slot, keystore) } ?: return false
        // The size test is a FAST PATH and not a guard: making a dummy again at the size it already
        // has produces a file of the same size, so removing it changes no verdict, only the work.
        // Said here because a negative control that took it out caught nothing, and a control that
        // catches nothing has to be explained rather than left looking like a weak test.
        return !mark.real && parsed.cipherAndTag.size - AesGcm.TAG_LENGTH < bucket
    }

    /**
     * A snapshot nobody can open: a random key, never kept, over padding only. Its mark says so, and
     * that is the only thing about it anyone without the Keystore key can learn — the mark has a
     * fixed length and a random nonce, so it reads the same as a real one.
     */
    private fun dummy(slot: Slot, bucket: Int): String {
        val header = HeirContainer.newHeader(slot, params)
        return SecretBytes.random(AesGcm.KEY_LENGTH).useThenWipe { key ->
            HeirContainer.seal(header, key, Padding.pad(ByteArray(0), bucket), HeirMark.dummy.seal(slot, keystore))
        }
    }

    /** The padded size of the largest snapshot on disk, read without any key. */
    private fun largestBucketOnDisk(): Int =
        maxOf(
            Padding.FIRST_BUCKET,
            Slot.entries.maxOfOrNull { slot ->
                files.read(slot)?.let { HeirContainer.parseOrNull(it, slot) }?.cipherAndTag?.size?.minus(AesGcm.TAG_LENGTH) ?: 0
            } ?: 0,
        )
}
