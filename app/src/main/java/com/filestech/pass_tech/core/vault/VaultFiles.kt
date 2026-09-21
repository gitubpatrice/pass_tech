package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.storage.AtomicFiles
import java.io.File

/** Reads and writes the slot files. An interface so tests can simulate a crash between two writes. */
interface SlotFiles {
    fun exists(slot: Slot): Boolean

    /** The content of [slot], or `null` if its file does not exist. */
    fun read(slot: Slot): String?

    /** Writes [updates] and rewrites every other existing slot file identically. */
    fun writeAll(updates: Map<Slot, String>)
}

/**
 * The slot files on disk.
 *
 * Every write goes through [writeAll], which rewrites EVERY slot file, the unchanged ones with their
 * exact current bytes. Otherwise the modification date of the file alone would tell which slot the
 * open session belongs to (Gemini's objection to the 2.7.1 design).
 *
 * Every temporary file is written and flushed first, then renamed: the unchanged slots first, the
 * changed ones LAST. A crash in between leaves some dates behind until the next save (a residual
 * stated in THREAT_MODEL), but every slot is always either fully old or fully new.
 */
class VaultFiles(private val directory: File) : SlotFiles {

    override fun exists(slot: Slot): Boolean = file(slot).isFile

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

    private fun file(slot: Slot) = File(directory, slot.vaultFileName)
}
