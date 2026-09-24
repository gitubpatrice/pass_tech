package com.filestech.pass_tech.core.model

import java.util.Locale

/**
 * What the home shows of the vault (2.7.1, `home_screen.dart` `_filtered`): one filter, a search, one
 * sort. The filter and the search narrow, the sort orders.
 */
data class EntryQuery(
    val filter: Filter = Filter.All,
    val search: String = "",
    val sort: Sort = Sort.RECENT,
) {
    sealed interface Filter {
        data object All : Filter

        data object Favorites : Filter

        data class OfType(val type: EntryType) : Filter

        /** A category by its canonical French name, see [Categories]. */
        data class InCategory(val category: String) : Filter

        companion object {
            /** The chips of the home, in 2.7.1's order: scope, then types, then categories. */
            val CHIPS: List<Filter> =
                listOf(All, Favorites) + EntryType.entries.map(::OfType) + Categories.ALL.map(::InCategory)
        }
    }

    /** [key] is what 2.7.1 stored under `sort_mode`. */
    enum class Sort(val key: String) {
        RECENT("recent"),
        OLDEST("oldest"),
        ALPHA("alpha"),
        ALPHA_DESC("alphaDesc"),
        ;

        companion object {
            fun fromKey(key: String?): Sort = entries.firstOrNull { it.key == key } ?: RECENT
        }
    }

    fun apply(entries: List<Entry>): List<Entry> {
        val needle = search.lowercase(Locale.ROOT)
        return entries
            .filter { matches(it) }
            .filter { needle.isEmpty() || it.searchable().any { field -> field.lowercase(Locale.ROOT).contains(needle) } }
            .sortedWith(
                when (sort) {
                    Sort.RECENT -> compareByDescending { it.updatedAt }
                    Sort.OLDEST -> compareBy { it.updatedAt }
                    Sort.ALPHA -> compareBy { it.title.lowercase(Locale.ROOT) }
                    Sort.ALPHA_DESC -> compareByDescending { it.title.lowercase(Locale.ROOT) }
                },
            )
    }

    private fun matches(entry: Entry): Boolean = when (filter) {
        Filter.All -> true
        Filter.Favorites -> entry.isFavorite
        is Filter.OfType -> entry.type == filter.type
        is Filter.InCategory -> entry.category == filter.category
    }

    /** 2.7.1 searches the title, the username, the URL and the notes: never a secret. */
    private fun Entry.searchable() = listOf(title, username, url, notes)
}
