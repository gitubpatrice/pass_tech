package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.storage.AtomicFiles
import com.filestech.pass_tech.core.vault.Slot
import java.io.File

/**
 * The K heir snapshots on disk, `pt_heir_{a,b,c}.enc`.
 *
 * Like the vault files, they are **always all there and always written together** (design v2 §8): a
 * slot with no heir carries a snapshot nobody can open, of the same size as the others. Otherwise
 * the mere presence, size or date of one file would say which slot has an heir — and, with it, which
 * slot holds a vault at all.
 *
 * **"Of the same size" holds for the dummies, and only for them.** A slot carrying the real snapshot
 * of ANOTHER vault cannot be rewritten by this one — only its own passphrase can seal it — so after
 * one vault outgrows a padding bucket, that slot stays at its own size until its own vault writes
 * again. That is the residual the design states for the vault files themselves (§11 point 2), and it
 * is the one this sentence used to promise away. The dummies are a different matter: they are this
 * app's own files, `HeirMark` is what lets it say so, and they are realigned at every write.
 */
interface HeirSnapshotFiles {
    fun read(slot: Slot): String?

    /** Writes [updates] and rewrites every other snapshot with its exact current bytes. */
    fun writeAll(updates: Map<Slot, String>)
}

class HeirFiles(private val directory: File) : HeirSnapshotFiles {

    override fun read(slot: Slot): String? = file(slot).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun writeAll(updates: Map<Slot, String>) {
        val unchanged = Slot.entries.filter { it !in updates }.mapNotNull { slot -> read(slot)?.let { slot to it } }
        val ordered = unchanged + Slot.entries.filter { it in updates }.map { it to updates.getValue(it) }
        val staged = ordered.map { (slot, content) -> slot to AtomicFiles.stage(file(slot), content.toByteArray(Charsets.UTF_8)) }
        try {
            staged.forEach { (slot, temp) -> AtomicFiles.commit(temp, file(slot)) }
        } finally {
            staged.forEach { (_, temp) -> temp.delete() }
        }
    }

    private fun file(slot: Slot) = File(directory, "pt_heir_${slot.label}.enc")
}
