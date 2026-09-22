package com.filestech.pass_tech.core.model

import com.filestech.pass_tech.core.model.EntryQuery.Filter
import com.filestech.pass_tech.core.model.EntryQuery.Sort
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The home's filter, search and sort, as 2.7.1's `home_screen.dart` `_filtered` applies them. */
class EntryQueryTest {

    private fun at(minute: Int) = requireNotNull(DartDateTime.parseOrNull("2026-09-22T10:%02d:00.000".format(minute)))

    private fun entry(
        title: String,
        minute: Int,
        type: EntryType = EntryType.PASSWORD,
        category: String = Categories.OTHER,
        favorite: Boolean = false,
        username: String = "",
        url: String = "",
        notes: String = "",
        password: String = "",
    ) = Entry(
        id = title,
        type = type,
        title = title,
        category = category,
        username = username,
        url = url,
        notes = notes,
        password = password,
        isFavorite = favorite,
        createdAt = at(minute),
        updatedAt = at(minute),
    )

    private val mail = entry("mail", 1, category = Categories.EMAIL, username = "Alice@Example.com")
    private val bank = entry("Banque", 3, type = EntryType.CARD, category = Categories.BANK, favorite = true)
    private val wifi = entry("wifi", 2, type = EntryType.NOTE, notes = "Box du SALON")
    private val shop = entry("Zalando", 4, url = "https://shop.example", password = "secretword")
    private val all = listOf(mail, bank, wifi, shop)

    private fun titles(query: EntryQuery) = query.apply(all).map { it.title }

    @Test
    fun `the default is everything, most recently modified first`() {
        assertThat(titles(EntryQuery())).containsExactly("Zalando", "Banque", "wifi", "mail").inOrder()
    }

    @Test
    fun `the four sorts`() {
        assertThat(titles(EntryQuery(sort = Sort.OLDEST))).containsExactly("mail", "wifi", "Banque", "Zalando").inOrder()
        // Case does not count: "Banque" before "mail", "wifi" before "Zalando".
        assertThat(titles(EntryQuery(sort = Sort.ALPHA))).containsExactly("Banque", "mail", "wifi", "Zalando").inOrder()
        assertThat(titles(EntryQuery(sort = Sort.ALPHA_DESC))).containsExactly("Zalando", "wifi", "mail", "Banque").inOrder()
    }

    @Test
    fun `favourites, a type, a category`() {
        assertThat(titles(EntryQuery(Filter.Favorites))).containsExactly("Banque")
        assertThat(titles(EntryQuery(Filter.OfType(EntryType.NOTE)))).containsExactly("wifi")
        assertThat(titles(EntryQuery(Filter.OfType(EntryType.PASSWORD)))).containsExactly("Zalando", "mail").inOrder()
        assertThat(titles(EntryQuery(Filter.InCategory(Categories.EMAIL)))).containsExactly("mail")
    }

    @Test
    fun `the search reads title, username, URL and notes, whatever the case`() {
        assertThat(titles(EntryQuery(search = "ZAL"))).containsExactly("Zalando")
        assertThat(titles(EntryQuery(search = "alice@"))).containsExactly("mail")
        assertThat(titles(EntryQuery(search = "shop.example"))).containsExactly("Zalando")
        assertThat(titles(EntryQuery(search = "salon"))).containsExactly("wifi")
    }

    @Test
    fun `the search never reads a secret`() {
        assertThat(titles(EntryQuery(search = "secretword"))).isEmpty()
    }

    @Test
    fun `filter and search narrow together`() {
        assertThat(titles(EntryQuery(Filter.OfType(EntryType.NOTE), search = "zal"))).isEmpty()
    }

    @Test
    fun `the chips come in 2_7_1's order, and a stored sort key reads back`() {
        assertThat(Filter.CHIPS.take(5)).containsExactly(
            Filter.All,
            Filter.Favorites,
            Filter.OfType(EntryType.PASSWORD),
            Filter.OfType(EntryType.NOTE),
            Filter.OfType(EntryType.CARD),
        ).inOrder()
        assertThat(Filter.CHIPS.drop(5)).containsExactlyElementsIn(Categories.ALL.map(Filter::InCategory)).inOrder()
        assertThat(Sort.fromKey("alphaDesc")).isEqualTo(Sort.ALPHA_DESC)
        assertThat(Sort.fromKey("anything")).isEqualTo(Sort.RECENT)
        assertThat(Sort.fromKey(null)).isEqualTo(Sort.RECENT)
    }
}
