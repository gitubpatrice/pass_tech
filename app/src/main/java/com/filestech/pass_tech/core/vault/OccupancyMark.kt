package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.SecretBytes

/**
 * Whether a slot holds a vault, sealed INSIDE the slot file (design v2 §2).
 *
 * The first design kept occupancy in a separate state file. Two files, two writes: a crash or a lost
 * state file could leave a used slot believed free, then overwritten (the 2.7.1 "vital guard"
 * disaster, times three). Here the mark is written by the same atomic rename as the vault itself, so
 * the two can never disagree.
 *
 * Sealed with the Keystore key [KEY_ALIAS], no user authentication. The plaintext has a FIXED length
 * and the nonce is random, so without that key an "occupied" mark and a "free" one are
 * indistinguishable. The slot label is inside the authenticated plaintext: a mark moved to another
 * slot's file no longer reads.
 *
 * A mark that cannot be read is UNKNOWN, and an unknown slot is never written, realigned or erased:
 * see [SlotStatus].
 */
data class OccupancyMark(
    val occupied: Boolean,
    /** Random identifier of this incarnation of the slot; changes at every creation and erasure. */
    val generation: String,
    /** Set on the slots freed by a deletion: the entry screen then shows the creation form. */
    val creationRequested: Boolean,
) {

    fun seal(slot: Slot, keystore: SlotKeystore): SlotKeystore.Wrapped {
        keystore.ensureAesKey(KEY_ALIAS)
        val state = if (occupied) OCCUPIED else FREE
        val create = if (creationRequested) 1 else 0
        val text = "$MAGIC|slot=${slot.label}|state=$state|gen=$generation|create=$create"
        require(text.length <= PLAIN_LENGTH) { "occupancy mark too long" }
        return keystore.wrap(KEY_ALIAS, text.padEnd(PLAIN_LENGTH, ' ').encodeToByteArray())
    }

    companion object {
        const val KEY_ALIAS = "pt_occ"
        private const val MAGIC = "PTOCC1"
        private const val OCCUPIED = "occupied"
        private const val FREE = "free"
        private const val PLAIN_LENGTH = 96
        private const val GENERATION_BYTES = 16
        private val FORMAT = Regex("^PTOCC1\\|slot=([a-z])\\|state=(occupied|free)\\|gen=([0-9a-f]{32})\\|create=([01])$")

        fun newGeneration(): String = SecretBytes.random(GENERATION_BYTES).joinToString("") { "%02x".format(it) }

        fun occupied(generation: String = newGeneration()) = OccupancyMark(occupied = true, generation, creationRequested = false)

        fun free(creationRequested: Boolean) = OccupancyMark(occupied = false, newGeneration(), creationRequested)

        /**
         * `null` if the mark does not authenticate, belongs to another slot, is malformed, or if the
         * Keystore did not answer: every one of these reads as UNKNOWN, which is never written over.
         */
        fun openOrNull(wrapped: SlotKeystore.Wrapped, slot: Slot, keystore: SlotKeystore): OccupancyMark? {
            val bytes = keystore.unwrap(KEY_ALIAS, wrapped).valueOrNull() ?: return null
            val groups = FORMAT.matchEntire(bytes.decodeToString().trimEnd())?.groupValues ?: return null
            return if (groups[1] != slot.label) {
                null
            } else {
                OccupancyMark(occupied = groups[2] == OCCUPIED, generation = groups[3], creationRequested = groups[4] == "1")
            }
        }
    }
}
