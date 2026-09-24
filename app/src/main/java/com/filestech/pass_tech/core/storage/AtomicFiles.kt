package com.filestech.pass_tech.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Crash-safe file replacement: the new content is written to a temporary sibling, flushed to the
 * device, then renamed over the target. A crash leaves the target either fully old or fully new,
 * never torn. The single implementation for every file the app writes.
 */
object AtomicFiles {

    private const val TEMP_SUFFIX = ".tmp"

    /** Writes [bytes] next to [target], flushed; [commit] then puts it in place. */
    fun stage(target: File, bytes: ByteArray): File {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        return temp
    }

    /**
     * Puts [staged] in place of [target], then flushes the directory: on Linux (Android), a rename
     * is atomic but only durable once the directory entry itself reaches the device.
     */
    fun commit(staged: File, target: File) {
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(target.parentFile)
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Not supported everywhere (Windows, where the JVM tests run). Best effort by nature.
        }
    }

    fun write(target: File, bytes: ByteArray) {
        val staged = stage(target, bytes)
        try {
            commit(staged, target)
        } finally {
            staged.delete()
        }
    }
}
