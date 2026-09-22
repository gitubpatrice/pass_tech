package com.filestech.pass_tech.core.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The files the owner points at, through the system picker: one to read, one to write.
 *
 * The app never browses storage and never keeps a copy: it is handed one document at a time, by the
 * person, and writes where they chose. Unlike 2.7.1, which wrote the export into its own cache and
 * shared it from there, leaving a copy behind to be shredded afterwards (Patrice, 2026-09-22).
 */
interface DocumentStore {

    sealed interface Read {
        class Text(val content: String, val name: String) : Read

        /** Above the cap, in whole megabytes, as the message shows it. */
        class TooLarge(val megabytes: Long) : Read

        data object Unreadable : Read
    }

    fun read(uri: Uri, maxBytes: Long): Read

    /** `false` if nothing could be written: the document is gone, or read-only. */
    fun write(uri: Uri, content: String): Boolean
}

@Singleton
class AndroidDocumentStore @Inject constructor(@ApplicationContext private val context: Context) : DocumentStore {

    override fun read(uri: Uri, maxBytes: Long): DocumentStore.Read =
        try {
            val name = displayName(uri) ?: ""
            val size = size(uri)
            if (size != null && size > maxBytes) {
                DocumentStore.Read.TooLarge(size / BYTES_PER_MEGABYTE)
            } else {
                readText(uri, maxBytes, name)
            }
        } catch (_: Exception) {
            // Any way a content provider can fail is the same answer: the file could not be read.
            DocumentStore.Read.Unreadable
        }

    /**
     * Reads at most [maxBytes], and one byte more: a provider that does not say its size cannot make the
     * app read a file without end. Malformed bytes become the replacement character, as 2.7.1 reads them.
     */
    private fun readText(uri: Uri, maxBytes: Long, name: String): DocumentStore.Read {
        // Read by hand, not with readNBytes: that one arrived in Android 13, and the oldest phone here
        // runs Android 10 (lint caught it).
        val cap = (maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            while (buffer.size() < cap) {
                val read = stream.read(chunk, 0, minOf(chunk.size, cap - buffer.size()))
                if (read <= 0) break
                buffer.write(chunk, 0, read)
            }
        } ?: return DocumentStore.Read.Unreadable
        val bytes = buffer.toByteArray()
        if (bytes.size > maxBytes) return DocumentStore.Read.TooLarge(bytes.size / BYTES_PER_MEGABYTE)
        return DocumentStore.Read.Text(String(bytes, Charsets.UTF_8), name)
    }

    override fun write(uri: Uri, content: String): Boolean =
        try {
            // "wt": truncate, so that a shorter export never leaves the tail of a longer one behind.
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.encodeToByteArray()) } != null
        } catch (_: Exception) {
            false
        }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun size(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private companion object {
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
        const val CHUNK_BYTES = 64 * 1024
    }
}
