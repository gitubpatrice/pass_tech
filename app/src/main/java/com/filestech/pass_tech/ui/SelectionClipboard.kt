package com.filestech.pass_tech.ui

import android.content.ClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard

/**
 * Compose's own copies go through the vault's clipboard.
 *
 * The selection toolbar of a `SelectionContainer` — an entry's notes, a secure note's content, a user name,
 * the heir's view — and of a field copies through Compose's [Clipboard], not through [SensitiveClipboard]: the
 * text landed in the system clipboard unmarked, so Android 13 and later previewed it on screen, and it was never
 * cleared. Provided as `LocalClipboard` around the whole app (dialogs read the same local), this hands every
 * text copy to [SensitiveClipboard.copy] instead: marked sensitive, cleared after the delay the owner chose,
 * cleared when the vault locks, as the copy buttons' copies are. SMS Tech closed the same gap in 1.28.6.
 *
 * Reading is the platform's own. A clip with no text (none is made by a text selection) and the empty clip go
 * to the platform clipboard as before.
 */
class SelectionClipboard(
    private val platform: Clipboard,
    private val vault: SensitiveClipboard,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text != null) vault.copy(text) else platform.setClipEntry(clipEntry)
    }

    // Deprecated by Compose, but its default throws NotImplementedError and Compose still reads it: delegated.
    @Deprecated("Deprecated in Compose's Clipboard; delegates to the platform clipboard.")
    @Suppress("DEPRECATION")
    override val nativeClipboard: ClipboardManager get() = platform.nativeClipboard
}
