package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.crypto.useThenWipe
import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.security.BruteForceGuard
import javax.crypto.Cipher

/**
 * The vault slots, as the rest of the app sees them. Design: `audit/conversion-kotlin/10-conception-coffre.md`
 * (v2.2), reviewed by three adversarial readers.
 *
 * Rules that hold everywhere below:
 * - **Opening never depends on the occupancy marks**: it tries every slot. Whatever state is lost,
 *   the owner keeps access with the password.
 * - **Nothing is ever written into a slot that is not PROVABLY free** (file absent, or mark read as
 *   free). A slot whose mark cannot be read counts as occupied: at worst the app refuses to create a
 *   vault, it never overwrites one. The only exception is the explicit total erase of a root vault.
 * - **A decoy never touches the vault that created it**: what a vault may erase is itself and the
 *   decoy it created, never its parent, which it does not even know about.
 * - **Every password check does the same work against every slot**, even after a match: the time an
 *   attempt takes says nothing about which slot answered. Every check is counted by the lockout.
 * - **A Keystore that does not answer is never a wrong password** (v2.2): the attempt still counts,
 *   nothing is created or rewritten on a guess, and the caller says "retry". Nor is a slot key ever
 *   created under a vault: only provably free slots get one.
 *
 * Not thread-safe: the caller serialises the calls (one vault operation at a time).
 */
// Every public function is one operation on the slots, and they share the invariants above: split
// across classes, each half would have to re-establish them.
@Suppress("TooManyFunctions")
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

        /** The Keystore did not answer: nothing is known about the password, and the attempt still counts. */
        data object KeystoreUnavailable : UnlockResult
    }

    sealed interface CreateResult {
        /** The password opened an existing vault: nothing was created. */
        data class Opened(val session: VaultSession) : CreateResult

        data class Created(val session: VaultSession) : CreateResult

        /** No slot is provably free. Deliberately says nothing more. */
        data object Impossible : CreateResult

        data class Locked(val remainingMillis: Long) : CreateResult

        /** Nothing was created: a slot could not be checked, and the password might open it. */
        data object KeystoreUnavailable : CreateResult
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

        /**
         * The Keystore did not answer. The creation may have stopped after its journal was written: the
         * caller locks the vault, and the next opening completes or drops it (design v2.1 §3).
         */
        data object KeystoreUnavailable : DecoyResult
    }

    sealed interface CheckResult {
        data object Correct : CheckResult

        data object Wrong : CheckResult

        data class Locked(val remainingMillis: Long) : CheckResult

        data object KeystoreUnavailable : CheckResult
    }

    sealed interface ChangeResult {
        /** [biometricsDisarmed]: a fingerprint opened this vault, and no longer does (design v2 §10). */
        data class Changed(val session: VaultSession, val biometricsDisarmed: Boolean) : ChangeResult

        data object WrongCurrentPassword : ChangeResult

        data object PasswordRefused : ChangeResult

        data class Locked(val remainingMillis: Long) : ChangeResult

        /** Nothing was written: the current password still opens the vault. */
        data object KeystoreUnavailable : ChangeResult
    }

    /** Which vault a fingerprint opens, as the owner of an open vault may know it (design v2 §9). */
    enum class BiometricStatus { OFF, THIS_VAULT, ANOTHER_VAULT }

    sealed interface BiometricUnlockResult {
        data class Opened(val session: VaultSession) : BiometricUnlockResult

        data class Locked(val remainingMillis: Long) : BiometricUnlockResult

        /** What was armed opens no vault any more: now disarmed. The password still opens the vault. */
        data object Disarmed : BiometricUnlockResult

        data object KeystoreUnavailable : BiometricUnlockResult
    }

    /** One attempt, against one slot or against all of them. */
    private sealed interface Attempt {
        data class Opened(val session: VaultSession) : Attempt

        data object NoMatch : Attempt

        /** Nothing opened, and at least one slot could not be checked. */
        data object Indeterminate : Attempt
    }

    private enum class Collision { NONE, MATCH, UNKNOWN }

    fun statuses(): Map<Slot, SlotStatus> = Slot.entries.associateWith(::status)

    /**
     * How long the lockout still lasts, 0 if an attempt may be made now: lets the unlock screen show
     * its countdown before anyone types. 0 as well if the state cannot be read; the attempt then says so.
     */
    fun lockoutRemainingMillis(): Long =
        unavailableAs(0L) { (guard.gate() as? BruteForceGuard.Gate.Locked)?.remainingMillis ?: 0L }

    /**
     * The creation form shows on a fresh install, and after any deletion, whichever vault it came
     * from: the screen that follows a deletion never tells whether another vault survived (oracle E).
     */
    fun entryMode(): EntryMode {
        val statuses = statuses().values
        val fresh = statuses.all { it == SlotStatus.Missing }
        return if (fresh || statuses.any { it.creationRequested }) EntryMode.CREATE else EntryMode.UNLOCK
    }

    fun unlock(password: ByteArray): UnlockResult =
        unavailableAs(UnlockResult.KeystoreUnavailable) {
            val gate = guard.gate()
            if (gate is BruteForceGuard.Gate.Locked) return UnlockResult.Locked(gate.remainingMillis)
            guard.beginAttempt()
            when (val attempt = tryAllSlots(password)) {
                is Attempt.Opened -> UnlockResult.Opened(settle(succeededWith(attempt.session)))
                Attempt.NoMatch -> {
                    guard.failed()
                    UnlockResult.WrongPassword
                }
                Attempt.Indeterminate -> {
                    guard.failed()
                    UnlockResult.KeystoreUnavailable
                }
            }
        }

    /**
     * The creation form (design v2 §5): a password that opens an existing vault opens it, which is
     * how the owner gets the real vault back after someone emptied the decoy. Otherwise a new vault
     * is created in a provably free slot.
     */
    fun openOrCreate(password: ByteArray): CreateResult =
        unavailableAs(CreateResult.KeystoreUnavailable) {
            val gate = guard.gate()
            if (gate is BruteForceGuard.Gate.Locked) return CreateResult.Locked(gate.remainingMillis)
            guard.beginAttempt()
            when (val attempt = tryAllSlots(password)) {
                is Attempt.Opened -> CreateResult.Opened(clearCreationRequests(settle(succeededWith(attempt.session))))
                Attempt.NoMatch -> createInFreeSlot(password)
                // The password may open the slot that could not be checked: creating now could make two vaults answer it.
                Attempt.Indeterminate -> {
                    guard.failed()
                    CreateResult.KeystoreUnavailable
                }
            }
        }

    /**
     * Saves [entries] into the open vault. Returns the updated session. Throws
     * [KeystoreUnavailableException] if the Keystore did not answer, before anything was written.
     */
    fun save(session: VaultSession, entries: List<Entry>): VaultSession {
        val updated = session.with(entries = entries)
        write(updated)
        return updated
    }

    /**
     * The decoy this vault created, PROVEN still there: its slot's mark reads occupied, with the same
     * generation. The only decoy [deleteData] may erase. A stale reference reads as no decoy.
     */
    fun decoyOf(session: VaultSession): ChildRef? = session.meta.child?.takeIf { status(it.slot).holds(it) }

    /**
     * Whether this vault may NOT create a decoy: it has one, or one it cannot rule out (an unreadable
     * mark, a creation still journalled). A second decoy is never offered over one that may exist.
     */
    fun hasDecoy(session: VaultSession): Boolean {
        val child = session.meta.child
        return session.meta.pendingChild != null ||
            (child != null && status(child.slot).let { it.holds(child) || it == SlotStatus.Unknown })
    }

    /**
     * Creates the decoy of [session] (design v2 §6, v2.1 §3). One per vault: the caller only offers it
     * when [hasDecoy] is false.
     */
    fun configureDecoy(session: VaultSession, password: ByteArray): DecoyResult =
        unavailableAs(DecoyResult.KeystoreUnavailable) {
            check(!hasDecoy(session)) { "this vault has a decoy, or cannot rule one out" }
            val gate = guard.gate()
            if (gate is BruteForceGuard.Gate.Locked) return DecoyResult.Locked(gate.remainingMillis)
            when (collision(password)) {
                Collision.MATCH -> DecoyResult.PasswordRefused
                Collision.UNKNOWN -> DecoyResult.KeystoreUnavailable
                Collision.NONE -> {
                    val statuses = statuses()
                    val target = (Slot.entries - session.slot).firstOrNull { statuses.getValue(it).provablyFree }
                    if (target == null) DecoyResult.Impossible else createDecoy(session, target, password)
                }
            }
        }

    /** Re-authentication inside an open vault. Counted by the lockout like any other check. */
    fun verifyPassword(session: VaultSession, password: ByteArray): CheckResult =
        unavailableAs(CheckResult.KeystoreUnavailable) {
            val gate = guard.gate()
            if (gate is BruteForceGuard.Gate.Locked) return CheckResult.Locked(gate.remainingMillis)
            guard.beginAttempt()
            val derived = VaultContainer.deriveKey(session.header, password, keystore)
            val correct = derived.valueOrNull()?.useThenWipe { SecretBytes.constantTimeEquals(it, session.key) } ?: false
            if (correct) guard.succeeded() else guard.failed()
            when {
                correct -> CheckResult.Correct
                derived == KeyResult.Unavailable -> CheckResult.KeystoreUnavailable
                else -> CheckResult.Wrong
            }
        }

    /** A new salt and a new key. The previous file stays whole until the atomic replace. */
    fun changePassword(session: VaultSession, current: ByteArray, new: ByteArray): ChangeResult =
        when (val check = verifyPassword(session, current)) {
            is CheckResult.Locked -> ChangeResult.Locked(check.remainingMillis)
            CheckResult.Wrong -> ChangeResult.WrongCurrentPassword
            CheckResult.KeystoreUnavailable -> ChangeResult.KeystoreUnavailable
            CheckResult.Correct -> unavailableAs(ChangeResult.KeystoreUnavailable) { changeVerified(session, new) }
        }

    /**
     * "Delete my data" (design v2 §5, v2.1 §1-2). A root vault erases every slot; any other vault
     * erases itself and its own decoy, and nothing it does not know about. Either way the freed slots
     * become dummies at the largest bucket on disk, marked for the creation form, and no Keystore key
     * is regenerated: both paths do the same work, so a stopwatch cannot tell them apart.
     *
     * Throws [KeystoreUnavailableException] if the Keystore did not answer, before anything was written:
     * the session stays open.
     */
    fun deleteData(session: VaultSession) {
        biometrics.purge()
        val erased = if (session.meta.root) Slot.entries.toSet() else setOfNotNull(session.slot, decoyOf(session)?.slot)
        val bucket = largestBucketOnDisk(except = null)
        files.writeAll(erased.associateWith { dummy(it, bucket, creationRequested = true) })
        session.close()
    }

    /**
     * Whether a fingerprint opens some vault: the unlock screen offers it. Says nothing of which, and
     * `false` if the secure hardware did not answer: the password is always there.
     */
    fun biometricsArmed(): Boolean = unavailableAs(false) { biometrics.armedGeneration() != null }

    fun biometricStatus(session: VaultSession): BiometricStatus =
        when (biometrics.armedGeneration()) {
            null -> BiometricStatus.OFF
            session.meta.generation -> BiometricStatus.THIS_VAULT
            else -> BiometricStatus.ANOTHER_VAULT
        }

    /**
     * Starts arming biometrics on [session]; `null` if refused: this vault has a decoy, or cannot rule
     * one out (design v2 §9, v2.1 §3). A fingerprint must never open the vault a decoy stands in front
     * of: that was the 2.7.0 flaw. Disarms whatever was armed.
     */
    fun cipherToArmBiometrics(session: VaultSession): Cipher? = if (hasDecoy(session)) null else biometrics.cipherToArm()

    /**
     * Seals the key of [session] with [cipher], once the prompt authenticated it. `false` if refused:
     * the check is made again, a decoy may have been created since [cipherToArmBiometrics].
     */
    fun armBiometrics(session: VaultSession, cipher: Cipher): Boolean {
        if (hasDecoy(session)) return false
        biometrics.arm(session.meta.generation, session.key, cipher)
        return true
    }

    fun cipherToUnlockWithBiometrics(): BiometricBinding.Start = biometrics.cipherToUnlock()

    fun disarmBiometrics() = biometrics.purge()

    /**
     * Opens the vault that armed biometrics, with a cipher the prompt authenticated (design v2 §9): the
     * sealed key is tried on every slot, and opens the one it decrypts whose generation it carries. Not
     * a password guess, so the lockout does not count it; but a running lockout refuses it, as 2.7.1 did.
     *
     * A vault with a decoy, or one it cannot rule out, is never opened by a fingerprint, whatever
     * armed it: checked here, at the moment of use, and not only when arming (Gemini review of the
     * biometrics, 2026-09-22). The purges keep this from happening; this keeps it from mattering.
     */
    fun unlockWithBiometrics(cipher: Cipher): BiometricUnlockResult =
        unavailableAs(BiometricUnlockResult.KeystoreUnavailable) {
            val gate = guard.gate()
            if (gate is BruteForceGuard.Gate.Locked) return BiometricUnlockResult.Locked(gate.remainingMillis)
            val armed = biometrics.open(cipher)
            val session = armed?.key?.useThenWipe { openArmed(it, armed.generation) }
            if (session != null && !hasDecoy(session)) return BiometricUnlockResult.Opened(settle(session))
            session?.close()
            biometrics.purge()
            BiometricUnlockResult.Disarmed
        }

    private fun changeVerified(session: VaultSession, new: ByteArray): ChangeResult =
        when (collision(new)) {
            Collision.MATCH -> ChangeResult.PasswordRefused
            Collision.UNKNOWN -> ChangeResult.KeystoreUnavailable
            Collision.NONE -> {
                // Read before the new key exists: a Keystore that does not answer here leaves nothing to wipe.
                val armedHere = biometrics.armedGeneration() == session.meta.generation
                val changed = newSession(session.slot, new, session.meta)?.with(entries = session.entries)
                if (changed == null) {
                    ChangeResult.KeystoreUnavailable
                } else {
                    // Only this vault's arming: another vault's owner would see their fingerprint stop
                    // working because of a vault they must not know about (design v2 §10).
                    if (armedHere) biometrics.purge()
                    written(changed)
                    session.close()
                    ChangeResult.Changed(changed, biometricsDisarmed = armedHere)
                }
            }
        }

    private fun createInFreeSlot(password: ByteArray): CreateResult {
        val statuses = statuses()
        val free = Slot.entries.filter { statuses.getValue(it).provablyFree }
        val target = free.firstOrNull()
        if (target == null) {
            guard.failed()
            return CreateResult.Impossible
        }
        guard.succeeded()
        // Only provably free slots get a key: a key is never re-created under a vault (GPT review of v2.2, P3).
        keystore.ensureHmacKeys(free.map { it.hardwareKeyAlias })
        val meta = VaultMeta(OccupancyMark.newGeneration(), root = free.size == Slot.entries.size, child = null)
        val session = newSession(target, password, meta) ?: return CreateResult.KeystoreUnavailable
        return CreateResult.Created(written(session, clearCreationRequests = true))
    }

    /**
     * The decoy is derived before anything is written, journalled in the parent BEFORE it exists, and
     * biometrics are purged before any write: a failure at any point leaves no orphan and no
     * fingerprint that opens the parent while a decoy exists (GPT 5.6 reviews of v2 §3, §C and v2.2 P4).
     */
    private fun createDecoy(parent: VaultSession, target: Slot, password: ByteArray): DecoyResult {
        val child = ChildRef(target, OccupancyMark.newGeneration())
        keystore.ensureHmacKeys(listOf(target.hardwareKeyAlias))
        val decoy = newSession(target, password, VaultMeta(child.generation, root = false, child = null))
            ?: return DecoyResult.KeystoreUnavailable
        try {
            biometrics.purge()
            val journalled = parent.with(meta = parent.meta.copy(pendingChild = child))
            write(journalled)
            write(decoy)
            val confirmed = journalled.with(meta = journalled.meta.copy(child = child, pendingChild = null))
            write(confirmed)
            return DecoyResult.Created(confirmed)
        } finally {
            decoy.close()
        }
    }

    /**
     * Completes or drops a decoy creation that was interrupted (design v2.1 §3). Best effort: if the
     * target's mark cannot be read, or the write fails, the journal stays for the next opening. It is
     * never dropped on an unreadable mark: that would orphan a decoy that exists.
     */
    private fun settle(session: VaultSession): VaultSession {
        val pending = session.meta.pendingChild ?: return session
        val status = status(pending.slot)
        if (status == SlotStatus.Unknown) return session
        val child = if (status.holds(pending)) pending else session.meta.child
        val settled = session.with(meta = session.meta.copy(child = child, pendingChild = null))
        return try {
            write(settled)
            settled
        } catch (_: KeystoreUnavailableException) {
            session
        }
    }

    /** After the creation form opened an existing vault, the next start shows the unlock form again. Best effort. */
    private fun clearCreationRequests(session: VaultSession): VaultSession {
        if (statuses().values.any { it.creationRequested }) {
            try {
                write(session, clearCreationRequests = true)
            } catch (_: KeystoreUnavailableException) {
                // Harmless: the creation form shows once more, and the next opening retries.
            }
        }
        return session
    }

    /**
     * Whether [password] opens an existing vault. The full work against every slot, and counted: a
     * collision check must not become a free way to test guesses. A match counts as a failure (the
     * password is refused), and so does a slot that could not be checked; no match cancels the attempt.
     */
    private fun collision(password: ByteArray): Collision {
        guard.beginAttempt()
        return when (val attempt = tryAllSlots(password)) {
            is Attempt.Opened -> {
                attempt.session.close()
                guard.failed()
                Collision.MATCH
            }
            Attempt.Indeterminate -> {
                guard.failed()
                Collision.UNKNOWN
            }
            Attempt.NoMatch -> {
                guard.succeeded()
                Collision.NONE
            }
        }
    }

    /**
     * A vault in [slot] with no entries yet: a fresh salt, and a key from [password] and the slot's
     * hardware key. `null` if the Keystore did not answer.
     */
    private fun newSession(slot: Slot, password: ByteArray, meta: VaultMeta): VaultSession? {
        val header = VaultContainer.newHeader(slot, params)
        return VaultContainer.deriveKey(header, password, keystore).valueOrNull()?.let { VaultSession(slot, header, it, meta, emptyList()) }
    }

    /** Records the success, then hands [session] over; wipes its key if the success cannot be recorded. */
    private fun succeededWith(session: VaultSession): VaultSession =
        try {
            guard.succeeded()
            session
        } catch (e: Exception) {
            session.close()
            throw e
        }

    /**
     * Writes a session that owns its key, and wipes that key if the write fails. Never for a saved
     * session, which shares its key with the open one.
     */
    private fun written(session: VaultSession, clearCreationRequests: Boolean = false): VaultSession =
        try {
            write(session, clearCreationRequests)
            session
        } catch (e: Exception) {
            session.close()
            throw e
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
     * from a real vault without its password, and cheap: no Argon2id to run. The slot keeps a hardware
     * key all the same, so that an attempt costs the same work on every slot.
     */
    private fun dummy(slot: Slot, bucket: Int, creationRequested: Boolean): String {
        keystore.ensureHmacKeys(listOf(slot.hardwareKeyAlias))
        val header = VaultContainer.newHeader(slot, params)
        return SecretBytes.random(AesGcm.KEY_LENGTH).useThenWipe { key ->
            VaultContainer.seal(header, key, Padding.pad(ByteArray(0), bucket), OccupancyMark.free(creationRequested).seal(slot, keystore))
        }
    }

    private fun tryAllSlots(password: ByteArray): Attempt {
        var opened: VaultSession? = null
        var indeterminate = false
        for (slot in Slot.entries) {
            when (val attempt = tryOpen(slot, password)) {
                is Attempt.Opened -> if (opened == null) opened = attempt.session else attempt.session.close()
                Attempt.Indeterminate -> indeterminate = true
                Attempt.NoMatch -> Unit
            }
        }
        return when {
            opened != null -> Attempt.Opened(opened)
            indeterminate -> Attempt.Indeterminate
            else -> Attempt.NoMatch
        }
    }

    /**
     * The same work on every slot: Argon2id and the hardware HMAC, on a synthetic header when the slot
     * has no readable file (GPT review of v2.2, P5).
     */
    private fun tryOpen(slot: Slot, password: ByteArray): Attempt {
        val parsed = files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) }
        val derived = VaultContainer.deriveKey(parsed?.header ?: VaultContainer.newHeader(slot, params), password, keystore)
        val key = derived.valueOrNull()
        return when {
            parsed == null -> {
                key?.wipe()
                Attempt.NoMatch
            }
            derived == KeyResult.Unavailable -> Attempt.Indeterminate
            key == null -> Attempt.NoMatch
            else -> openWith(slot, parsed, key)
        }
    }

    /** The one slot [key] decrypts and whose vault carries [generation]; `null` if none. [key] is left to the caller. */
    private fun openArmed(key: ByteArray, generation: String): VaultSession? {
        var found: VaultSession? = null
        for (slot in Slot.entries) {
            val parsed = files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) } ?: continue
            val attempt = openWith(slot, parsed, key.copyOf())
            if (attempt is Attempt.Opened) {
                if (found == null && attempt.session.meta.generation == generation) found = attempt.session else attempt.session.close()
            }
        }
        return found
    }

    private fun openWith(slot: Slot, parsed: VaultContainer.Parsed, key: ByteArray): Attempt {
        val payload = VaultContainer.openOrNull(parsed, key)?.useThenWipe(VaultPayload::fromPaddedBytesOrNull)
        return if (payload == null) {
            key.wipe()
            Attempt.NoMatch
        } else {
            Attempt.Opened(VaultSession(slot, parsed.header, key, payload.meta, payload.entries))
        }
    }

    private fun status(slot: Slot): SlotStatus {
        val content = files.read(slot) ?: return SlotStatus.Missing
        return VaultContainer.parseOrNull(content, slot)
            ?.let { OccupancyMark.openOrNull(it.occupancy, slot, keystore) }
            ?.let { SlotStatus.Marked(it) }
            ?: SlotStatus.Unknown
    }

    /** Whether this slot provably holds [child]: its mark reads occupied, with the child's generation. */
    private fun SlotStatus.holds(child: ChildRef): Boolean =
        this is SlotStatus.Marked && mark.occupied && mark.generation == child.generation

    /** The padded plaintext size of a slot, read without its key. 0 if unreadable. */
    private fun bucketOf(slot: Slot): Int =
        files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) }?.cipherAndTag?.size?.minus(AesGcm.TAG_LENGTH) ?: 0

    private fun largestBucketOnDisk(except: Slot?): Int =
        maxOf(Padding.FIRST_BUCKET, Slot.entries.filter { it != except }.maxOfOrNull(::bucketOf) ?: 0)

    /** Runs [block]; a Keystore that did not answer anywhere inside becomes [unavailable]. */
    private inline fun <T> unavailableAs(unavailable: T, block: () -> T): T =
        try {
            block()
        } catch (_: KeystoreUnavailableException) {
            unavailable
        }
}
