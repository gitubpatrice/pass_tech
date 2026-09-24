package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.wipe
import com.filestech.pass_tech.core.model.Entry

/**
 * An open vault: which slot, the key that seals it, what it knows about itself, and its entries.
 *
 * The key stays in memory for as long as the vault is open, so that saving does not run Argon2id
 * again. [close] wipes it; a closed session must not be used.
 */
class VaultSession internal constructor(
    val slot: Slot,
    internal val header: VaultContainer.Header,
    internal val key: ByteArray,
    val meta: VaultMeta,
    val entries: List<Entry>,
) {
    internal fun with(entries: List<Entry> = this.entries, meta: VaultMeta = this.meta) =
        VaultSession(slot, header, key, meta, entries)

    fun close() {
        key.wipe()
    }

    /** Never the key, never the entries. */
    override fun toString(): String = "VaultSession(slot=${slot.label}, entries=${entries.size})"
}
