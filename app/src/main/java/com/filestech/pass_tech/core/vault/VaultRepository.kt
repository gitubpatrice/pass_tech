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
 * The vault slots, as the rest of the app sees them (design v2, `audit/conversion-kotlin`).
 *
 * Two rules hold everywhere below:
 * - **Opening never depends on the occupancy marks**: it tries every slot. Whatever state is lost,
 *   the owner keeps access with the password.
 * - **Nothing is ever written into a slot that is not PROVABLY free** (file absent, or mark read as
 *   free). A slot whose mark cannot be read is treated as occupied: at worst the app refuses to
 *   create a vault, it never overwrites one.
 *
 * Every derivation runs against ALL the slots, even after a match, so the time an attempt takes says
 * nothing about which slot answered.
 *
 * Not thread-safe: the caller serialises the calls (one vault operation at a time).
 */
class VaultRepository(
    private val files: VaultFiles,
    private val keystore: SlotKeystore,
    private val guard: BruteForceGuard,
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
            UnlockResult.Opened(session)
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
            leaveCreationMode(opened)
            CreateResult.Opened(opened)
        } else {
            createInFreeSlot(password)
        }
    }

    /** Only a PROVABLY free slot is used. None means Impossible, and the attempt counts as failed. */
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

    /** Saves [entries] into the open vault. Returns the updated session. */
    fun save(session: VaultSession, entries: List<Entry>): VaultSession {
        val updated = session.with(entries = entries)
        write(updated)
        return updated
    }

    private fun create(slot: Slot, password: ByteArray, root: Boolean): VaultSession {
        Slot.entries.forEach(keystore::ensureKey)
        val header = VaultContainer.newHeader(slot, keystore, params)
        val key = checkNotNull(VaultContainer.deriveKeyOrNull(header, password, keystore)) {
            "the Keystore key of a slot created a moment ago does not unwrap"
        }
        val session = VaultSession(slot, header, key, VaultMeta(OccupancyMark.newGeneration(), root, child = null), emptyList())
        write(session, clearCreationRequests = true)
        return session
    }

    /** Opening from the creation form ends the creation mode. */
    private fun leaveCreationMode(session: VaultSession) {
        if (statuses().values.any { it.creationRequested }) write(session, clearCreationRequests = true)
    }

    /**
     * Seals [session] and rewrites every slot file (design v2 §7). Dummies are regenerated when they
     * are smaller than the new bucket, or when the creation mode ends; a missing slot file becomes a
     * dummy. An occupied or unknown slot is copied as it is, never touched.
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

    /** The padded plaintext size of a slot, read without its key. 0 if unreadable. */
    private fun bucketOf(slot: Slot): Int =
        files.read(slot)?.let { VaultContainer.parseOrNull(it, slot) }?.cipherAndTag?.size?.minus(AesGcm.TAG_LENGTH) ?: 0

    private fun largestBucketOnDisk(except: Slot): Int = (Slot.entries - except).maxOfOrNull(::bucketOf) ?: 0

    private companion object {
        const val SALT_LENGTH = 32
    }
}
