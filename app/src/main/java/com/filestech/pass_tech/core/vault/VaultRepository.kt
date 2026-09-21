package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.Argon2id
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard

/**
 * The vault slots, as the rest of the app sees them. Design: `audit/conversion-kotlin/10-conception-coffre.md`
 * (v2.1), reviewed by three adversarial readers.
 *
 * Rules that hold everywhere below:
 * - **Opening never depends on the occupancy marks**: it tries every slot. Whatever state is lost,
 *   the owner keeps access with the password.
 * - **Nothing is ever written into a slot that is not PROVABLY free** (file absent, or mark read as
 *   free). A slot whose mark cannot be read counts as occupied: at worst the app refuses to create a
 *   vault, it never overwrites one. The only exception is the explicit total erase of a root vault.
 * - **A decoy never touches the vault that created it**: what a vault may erase is itself and the
 *   decoy it created, never its parent, which it does not even know about.
 * - **Every password check derives against every slot**, even after a match: the time an attempt
 *   takes says nothing about which slot answered. Every check is counted by the lockout.
 *
 * Not thread-safe: the caller serialises the calls (one vault operation at a time).
 */
class VaultRepository(
    private val files: SlotFiles,
    private val keystore: SlotKeystore,
    private val guard: BruteForceGuard,
    private val biometrics: BiometricBinding = BiometricBinding.NONE,
    private val params: KdfParams = KdfParams.OWASP_MOBILE_2024,
) {

    enum class EntryMode { CREATE, UNLOCK }

    sealed interface UnlockResult {
        data class Opened(val session: VaultSession) : UnlockResult

        data object WrongPassword : UnlockResult

        data class Locked(val remainingMillis: Long) : UnlockResult
    }

    sealed interface CreateResult {
        /** The password opened an existing vault: nothing was created. */
        data class Opened(val session: VaultSession) : CreateResult

        data class Created(val session: VaultSession) : CreateResult

        /** No slot is provably free. Deliberately says nothing more. */
        data object Impossible : CreateResult

        data class Locked(val remainingMillis: Long) : CreateResult
    }

    sealed interface DecoyResult {
        /** The decoy exists; the returned session is the updated PARENT. */
        data class Created(val parent: VaultSession) : DecoyResult

        /**
         * The chosen password cannot be used: it opens an existing vault. The same answer as for "the
         * same as the current password", which a single-vault phone gives too.
         */
        data object PasswordRefused : DecoyResult

        data object Impossible : DecoyResult

        data class Locked(val remainingMillis: Long) : DecoyResult
    }

    sealed interface CheckResult {
        data object Correct : CheckResult

        data object Wrong : CheckResult

        data class Locked(val remainingMillis: Long) : CheckResult
    }

    sealed interface ChangeResult {
        data class Changed(val session: VaultSession) : ChangeResult

        data object WrongCurrentPassword : ChangeResult

        data object PasswordRefused : ChangeResult

        data class Locked(val remainingMillis: Long) : ChangeResult
    }

    fun statuses(): Map<Slot, SlotStatus> = Slot.entries.associateWith(::status)

    /**
     * The creation form shows on a fresh install, and after any deletion, whichever vault it came
     * from: the screen that follows a deletion never tells whether another vault survived (oracle E).
     */
    fun entryMode(): EntryMode {
        val statuses = statuses().values
        val fresh = statuses.all { it == SlotStatus.Missing }
        return if (fresh || statuses.any { it.creationRequested }) EntryMode.CREATE else EntryMode.UNLOCK
    }

    fun unlock(password: ByteArray): UnlockResult {
        val gate = guard.gate()
        if (gate is BruteForceGuard.Gate.Locked) return UnlockResult.Locked(gate.remainingMillis)
        guard.beginAttempt()
        val session = tryAllSlots(password)
        return if (session != null) {
            guard.succeeded()
            UnlockResult.Opened(settle(session))
        } else {
            guard.failed()
            UnlockResult.WrongPassword
        }
    }

    /**
     * The creation form (design v2 §5): a password that opens an existing vault opens it, which is
     * how the owner gets the real vault back after someone emptied the decoy. Otherwise a new vault
     * is created in a provably free slot.
     */
    fun openOrCreate(password: ByteArray): CreateResult {
        val gate = guard.gate()
        if (gate is BruteForceGuard.Gate.Locked) return CreateResult.Locked(gate.remainingMillis)
        guard.beginAttempt()
        val opened = tryAllSlots(password)
        return if (opened != null) {
            guard.succeeded()
            val settled = settle(opened)
            if (statuses().values.any { it.creationRequested }) write(settled, clearCreationRequests = true)
            CreateResult.Opened(settled)
        } else {
            createInFreeSlot(password)
        }
    }

    /** Saves [entries] into the open vault. Returns the updated session. */
    fun save(session: VaultSession, entries: List<Entry>): VaultSession {
        val updated = session.with(entries = entries)
        write(updated)
        return updated
    }

    /**
     * The decoy this vault created, or `null` if it has none or if its slot no longer holds that
     * incarnation: erased from its own session, or reused since. A stale reference reads as no decoy.
     */
    fun decoyOf(session: VaultSession): ChildRef? =
        session.meta.child?.takeIf { child -> markOf(child.slot)?.let { it.occupied && it.generation == child.generation } == true }

    /**
     * Creates the decoy of [session] (design v2 §6, v2.1 §3). One per vault: the caller only offers it
     * when [decoyOf] is `null`.
     */
    fun configureDecoy(session: VaultSession, password: ByteArray): DecoyResult {
        check(decoyOf(session) == null) { "this vault already has a decoy" }
        val gate = guard.gate()
        if (gate is BruteForceGuard.Gate.Locked) return DecoyResult.Locked(gate.remainingMillis)
        if (collides(password)) return DecoyResult.PasswordRefused
        val statuses = statuses()
        val target = (Slot.entries - session.slot).firstOrNull { statuses.getValue(it).provablyFree }
        return if (target == null) DecoyResult.Impossible else DecoyResult.Created(createDecoy(session, target, password))
    }

    /** Re-authentication inside an open vault. Counted by the lockout like any other check. */
    fun verifyPassword(session: VaultSession, password: ByteArray): CheckResult {
        val gate = guard.gate()
        if (gate is BruteForceGuard.Gate.Locked) return CheckResult.Locked(gate.remainingMillis)
        guard.beginAttempt()
        val derived = VaultContainer.deriveKeyOrNull(session.header, password, keystore)
        val correct = derived?.useThenWipe { SecretBytes.constantTimeEquals(it, session.key) } ?: false
        if (correct) guard.succeeded() else guard.failed()
        return if (correct) CheckResult.Correct else CheckResult.Wrong
    }

    /** New salt, new hardware secret, new key. The previous file stays whole until the atomic replace. */
    fun changePassword(session: VaultSession, current: ByteArray, new: ByteArray): ChangeResult =
        when (val check = verifyPassword(session, current)) {
            is CheckResult.Locked -> ChangeResult.Locked(check.remainingMillis)
            CheckResult.Wrong -> ChangeResult.WrongCurrentPassword
            CheckResult.Correct ->
                if (collides(new)) {
                    ChangeResult.PasswordRefused
                } else {
                    biometrics.purge()
                    val changed = newSession(session.slot, new, session.meta).with(entries = session.entries)
                    write(changed)
                    session.close()
                    ChangeResult.Changed(changed)
                }
        }

    /**
     * "Delete my data" (design v2 §5, v2.1 §1-2). A root vault erases every slot; any other vault
     * erases itself and its own decoy, and nothing it does not know about. Either way the freed slots
     * become dummies at the largest bucket on disk, marked for the creation form, and no Keystore key
     * is regenerated: both paths do the same work, so a stopwatch cannot tell them apart.
     */
    fun deleteData(session: VaultSession) {
        biometrics.purge()
        val erased = if (session.meta.root) Slot.entries.toSet() else setOfNotNull(session.slot, decoyOf(session)?.slot)
        val bucket = largestBucketOnDisk(except = null)
        files.writeAll(erased.associateWith { dummy(it, bucket, creationRequested = true) })
        session.close()
    }

    private fun createInFreeSlot(password: ByteArray): CreateResult {
        val statuses = statuses()
        val target = Slot.entries.firstOrNull { statuses.getValue(it).provablyFree }
        return if (target == null) {
            guard.failed()
            CreateResult.Impossible
        } else {
            guard.succeeded()
            CreateResult.Created(create(target, password, root = statuses.values.all { it.provablyFree }))
        }
    }

    private fun create(slot: Slot, password: ByteArray, root: Boolean): VaultSession {
        Slot.entries.forEach(keystore::ensureKey)
        val session = newSession(slot, password, VaultMeta(OccupancyMark.newGeneration(), root, child = null))
        write(session, clearCreationRequests = true)
        return session
    }

    /**
     * The decoy is journalled in the parent BEFORE it exists, and biometrics are purged before either
     * write: a crash at any point leaves no orphan and no fingerprint that opens the parent while a
     * decoy exists (GPT 5.6 review of v2, §3 and §C).
     */
    private fun createDecoy(parent: VaultSession, target: Slot, password: ByteArray): VaultSession {
        val child = ChildRef(target, OccupancyMark.newGeneration())
        biometrics.purge()
        val journalled = parent.with(meta = parent.meta.copy(pendingChild = child))
        write(journalled)
        val decoy = newSession(target, password, VaultMeta(child.generation, root = false, child = null))
        write(decoy)
        decoy.close()
        val confirmed = journalled.with(meta = journalled.meta.copy(child = child, pendingChild = null))
        write(confirmed)
        return confirmed
    }

    /** Completes or drops a decoy creation interrupted by a crash (design v2.1 §3). */
    private fun settle(session: VaultSession): VaultSession {
        val pending = session.meta.pendingChild ?: return session
        val mark = markOf(pending.slot)
        val confirmed = mark != null && mark.occupied && mark.generation == pending.generation
        val settled = session.with(meta = session.meta.copy(child = if (confirmed) pending else session.meta.child, pendingChild = null))
        write(settled)
        return settled
    }

    /**
     * Whether [password] opens an existing vault. Runs the full derivation against every slot and is
     * counted: a collision check must not become a free way to test guesses. A match counts as a
     * failure (the password is refused); no match cancels the attempt.
     */
    private fun collides(password: ByteArray): Boolean {
        guard.beginAttempt()
        val match = tryAllSlots(password)
        match?.close()
        if (match != null) guard.failed() else guard.succeeded()
        return match != null
    }

    /** A vault in [slot] with no entries yet: fresh salt, fresh hardware secret, key from [password]. */
    private fun newSession(slot: Slot, password: ByteArray, meta: VaultMeta): VaultSession {
        val header = VaultContainer.newHeader(slot, keystore, params)
        val key = checkNotNull(VaultContainer.deriveKeyOrNull(header, password, keystore)) {
            "the Keystore key of slot ${slot.label} does not unwrap a secret it wrapped a moment ago"
        }
        return VaultSession(slot, header, key, meta, emptyList())
    }

    /**
     * Seals [session] and rewrites every slot file (design v2 §7). Dummies are regenerated when smaller
     * than the new bucket or when the creation mode ends; a missing slot file becomes a dummy. An
     * occupied or unknown slot is copied as it is, never touched.
     */
    private fun write(session: VaultSession, clearCreationRequests: Boolean = false) {
        val statuses = statuses()
        val plain = VaultPayload(session.entries, session.meta).toBytes()
        val bucket = maxOf(Padding.bucketFor(plain.size), largestBucketOnDisk(except = session.slot))
        val mark = OccupancyMark.occupied(session.meta.generation).seal(session.slot, keystore)
        val content = plain.useThenWipe { Padding.pad(it, bucket) }.useThenWipe { padded ->
            VaultContainer.seal(session.header, session.key, padded, mark)
        }
        val updates = mutableMapOf(session.slot to content)
        for (slot in Slot.entries - session.slot) {
            val status = statuses.getValue(slot)
            if (status == SlotStatus.Missing) {
                updates[slot] = dummy(slot, bucket, creationRequested = false)
            } else if (status is SlotStatus.Marked && !status.mark.occupied) {
                val keepRequest = status.mark.creationRequested && !clearCreationRequests
                if (bucketOf(slot) < bucket || keepRequest != status.mark.creationRequested) {
                    updates[slot] = dummy(slot, bucket, keepRequest)
                }
            }
        }
        files.writeAll(updates)
    }

    /**
     * A slot file nobody can open: random key, never stored, over padding only. Indistinguishable
     * from a real vault without its password, and cheap: no Argon2id to run.
     */
    private fun dummy(slot: Slot, bucket: Int, creationRequested: Boolean): String {
        keystore.ensureKey(slot)
        val header = VaultContainer.newHeader(slot, keystore, params)
        return SecretBytes.random(AesGcm.KEY_LENGTH).useThenWipe { key ->
            VaultContainer.seal(header, key, Padding.pad(ByteArray(0), bucket), OccupancyMark.free(creationRequested).seal(slot, keystore))
        }
    }

    private fun tryAllSlots(password: ByteArray): VaultSession? {
        var opened: VaultSession? = null
        for (slot in Slot.entries) {
            val candidate = tryOpen(slot, password)
            if (opened == null) opened = candidate else candidate?.close()
        }
        return opened
    }

    private fun tryOpen(slot: Slot, password: ByteArray): VaultSession? {
        val parsed = files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) }
        if (parsed == null) {
            // No file, or not a vault file: spend the same derivation anyway.
            Argon2id.derive(password, SecretBytes.random(SALT_LENGTH), params).wipe()
            return null
        }
        val key = VaultContainer.deriveKeyOrNull(parsed.header, password, keystore) ?: return null
        val payload = VaultContainer.openOrNull(parsed, key)?.useThenWipe(VaultPayload::fromPaddedBytesOrNull)
        return if (payload == null) {
            key.wipe()
            null
        } else {
            VaultSession(slot, parsed.header, key, payload.meta, payload.entries)
        }
    }

    private fun status(slot: Slot): SlotStatus {
        val content = files.read(slot) ?: return SlotStatus.Missing
        return VaultContainer.parseOrNull(content, slot)
            ?.let { OccupancyMark.openOrNull(it.occupancy, slot, keystore) }
            ?.let { SlotStatus.Marked(it) }
            ?: SlotStatus.Unknown
    }

    private fun markOf(slot: Slot): OccupancyMark? = (status(slot) as? SlotStatus.Marked)?.mark

    /** The padded plaintext size of a slot, read without its key. 0 if unreadable. */
    private fun bucketOf(slot: Slot): Int =
        files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) }?.cipherAndTag?.size?.minus(AesGcm.TAG_LENGTH) ?: 0

    private fun largestBucketOnDisk(except: Slot?): Int =
        maxOf(Padding.FIRST_BUCKET, Slot.entries.filter { it != except }.maxOfOrNull(::bucketOf) ?: 0)

    private companion object {
        const val SALT_LENGTH = 32
    }
}
