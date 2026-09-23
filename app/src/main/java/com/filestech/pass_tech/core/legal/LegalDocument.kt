package com.filestech.pass_tech.core.legal

import android.content.res.AssetManager

/**
 * The two documents the app carries, and how a language finds its copy of one.
 *
 * They are shipped inside the app rather than linked to, for two reasons that both matter here: they
 * read with no network, on a screen an owner may well be reading *because* they are wary of the
 * network; and the copy they read is the one that came with the version installed, not a page that
 * has moved on since.
 */
enum class LegalDocument(private val base: String) {
    PRIVACY("PRIVACY"),
    TERMS("TERMS"),
    ;

    fun asset(language: String): String = "$DIRECTORY/$base.$language.md"

    companion object {
        private const val DIRECTORY = "legal"

        /**
         * English is not a courtesy fallback: it is the only language every release is guaranteed to
         * carry, and a translation is added to `assets/legal/` only once it has been written against
         * the code. 3.0.0 ships English and French; the app shows the English one to the other three
         * and says so, because a document that describes the app wrongly is worse than one in a
         * language its reader has to work at.
         */
        const val FALLBACK = "en"

        /** What to try, in order. `distinct` so an English reader is not offered English twice. */
        fun languages(language: String): List<String> = listOf(language, FALLBACK).distinct()
    }
}

/** A document as it was found: its blocks, and the language they turned out to be in. */
data class LegalReading(val language: String, val blocks: List<Markdown.Block>)

/**
 * Opens one of the documents from the APK's assets.
 *
 * `null` means no file could be read at all, which on a sound build cannot happen — the assets are
 * packaged with the code. It is still answered rather than thrown: the screen says the document could
 * not be read, which is true and readable, where a crash on a legal screen would be neither.
 */
object LegalLibrary {

    fun read(assets: AssetManager, document: LegalDocument, language: String): LegalReading? =
        LegalDocument.languages(language).firstNotNullOfOrNull { candidate ->
            text(assets, document.asset(candidate))?.let { LegalReading(candidate, Markdown.blocks(it)) }
        }

    private fun text(assets: AssetManager, path: String): String? =
        runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()
}
