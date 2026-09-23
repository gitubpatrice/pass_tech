package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.SlotKeystore
import com.filestech.pass_tech.core.vault.valueOrNull

/**
 * Whether a heir snapshot is one somebody can open, sealed INSIDE the snapshot file — the same idea
 * as `OccupancyMark` on the vault side, and for the same reason.
 *
 * **What it is for.** The K files must always weigh the same, or their sizes say which slot has an
 * heir and, with it, which slot holds a vault at all. A slot with no heir therefore carries a dummy.
 * Without this mark nothing could tell a dummy from a real snapshot — only its passphrase could —
 * so a dummy could never be rewritten, and one written at 64 KiB stayed at 64 KiB for ever while the
 * vault beside it grew to 256 KiB. That is the common phone: one vault, one heir, two dummies whose
 * size never catches up and never will. The vault side never had that hole, because a free slot
 * carries a mark saying so and is realigned at every save.
 *
 * **What it is deliberately not.** It says "real" or "dummy" and nothing else — no generation, no
 * date, no size. It is sealed with the Keystore key [KEY_ALIAS], so a copy of the private directory
 * cannot read it: the plaintext has a FIXED length and the nonce is random, so a "real" mark and a
 * "dummy" one are the same bytes to anyone without that key.
 *
 * **It never leaves [HeirRepository].** The app itself can now tell which slots hold a real heir,
 * which it could not before. That answer is used in exactly one place, to decide whether a file may
 * be rewritten; putting it on a screen, in a state file or in any result would hand a decoy session
 * a fact about another vault — the one thing this whole design exists to prevent.
 *
 * A mark that cannot be read is UNKNOWN, and an unknown file is never rewritten. That is the side to
 * fail on: refusing to touch a file can only leave sizes uneven, while touching one wrongly destroys
 * the heir of a vault this one is not supposed to know about.
 */
data class HeirMark(val real: Boolean) {

    fun seal(slot: Slot, keystore: SlotKeystore): SlotKeystore.Wrapped {
        keystore.ensureAesKey(KEY_ALIAS)
        val text = "$MAGIC|slot=${slot.label}|state=${if (real) REAL else DUMMY}"
        require(text.length <= PLAIN_LENGTH) { "heir mark too long" }
        return keystore.wrap(KEY_ALIAS, text.padEnd(PLAIN_LENGTH, ' ').encodeToByteArray())
    }

    companion object {
        const val KEY_ALIAS = "pt_heirocc"
        private const val MAGIC = "PTHOCC1"
        private const val REAL = "real"
        private const val DUMMY = "dummy"

        /** Fixed, and comfortably longer than both texts: the length must say nothing. */
        private const val PLAIN_LENGTH = 48
        private val FORMAT = Regex("^PTHOCC1\\|slot=([a-z])\\|state=(real|dummy)$")

        val real = HeirMark(real = true)
        val dummy = HeirMark(real = false)

        /**
         * `null` if the mark does not authenticate, belongs to another slot, is malformed, or if the
         * Keystore did not answer — and also for a file written before this mark existed, which
         * carries none. Every one of those reads as UNKNOWN.
         */
        fun openOrNull(wrapped: SlotKeystore.Wrapped, slot: Slot, keystore: SlotKeystore): HeirMark? {
            val bytes = keystore.unwrap(KEY_ALIAS, wrapped).valueOrNull() ?: return null
            val groups = FORMAT.matchEntire(bytes.decodeToString().trimEnd())?.groupValues ?: return null
            return if (groups[1] != slot.label) null else HeirMark(real = groups[2] == REAL)
        }
    }
}
