package com.filestech.pass_tech.core.model

import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test

class EntryJsonTest {

    private val dartJson = Json.parseToJsonElement(Resources.text("compat/2.7.1/entries.json")).jsonArray

    @Test
    fun `entries written by Flutter 2_7_1 read back and write again to the same JSON`() {
        val entries = dartJson.map { requireNotNull(EntryJson.fromJsonOrNull(it)) }
        assertThat(entries.map { it.type })
            .containsExactly(EntryType.PASSWORD, EntryType.NOTE, EntryType.CARD, EntryType.PASSWORD)
            .inOrder()
        assertThat(EntryJson.toJsonArray(entries)).isEqualTo(dartJson)
    }

    @Test
    fun `the tricky fixture characters survive`() {
        val password = requireNotNull(EntryJson.fromJsonOrNull(dartJson[0]))
        assertThat(password.password).isEqualTo("p@ss \"wörd\" \\ 😀 <tag> & 'q'")
        assertThat(password.notes).isEqualTo("Ligne 1\nLigne 2\ttab\r\nfin \u0000 nul")
        assertThat(password.isFavorite).isTrue()
        assertThat(password.updatedAt.toIso8601String()).isEqualTo("2026-09-21T19:47:00.000123")
    }

    @Test
    fun `missing optional fields take the Flutter defaults`() {
        val entry = parse("""{"id":"a","title":"t","createdAt":"2024-01-01","updatedAt":"2024-01-01"}""")!!
        assertThat(entry.type).isEqualTo(EntryType.PASSWORD)
        assertThat(entry.category).isEqualTo("Autres")
        assertThat(entry.username).isEmpty()
        assertThat(entry.isFavorite).isFalse()
    }

    @Test
    fun `an unknown type reads as a password, as in Flutter`() {
        assertThat(parse("""{"id":"a","type":"identity","title":"t","createdAt":"2024-01-01","updatedAt":"2024-01-01"}""")?.type)
            .isEqualTo(EntryType.PASSWORD)
    }

    @Test
    fun `what Entry fromJson rejects is rejected`() {
        val base = """"createdAt":"2024-01-01","updatedAt":"2024-01-01""""
        assertThat(parse("""{"title":"t",$base}""")).isNull() // no id
        assertThat(parse("""{"id":"a",$base}""")).isNull() // no title
        assertThat(parse("""{"id":1,"title":"t",$base}""")).isNull() // id not a string
        assertThat(parse("""{"id":"a","title":"t","isFavorite":"true",$base}""")).isNull() // "true" is a string
        assertThat(parse("""{"id":"a","title":"t","isFavorite":1,$base}""")).isNull()
        assertThat(parse("""{"id":"a","title":"t","username":42,$base}""")).isNull()
        assertThat(parse("""{"id":"a","title":"t","createdAt":"yesterday","updatedAt":"2024-01-01"}""")).isNull()
        assertThat(parse("""{"id":"a","title":"t","createdAt":"2024-01-01"}""")).isNull() // no updatedAt
        assertThat(parse("""["not","an","object"]""")).isNull()
    }

    @Test
    fun `JSON null reads as absent, as a Dart cast to a nullable type does`() {
        val entry = parse("""{"id":"a","title":"t","category":null,"isFavorite":null,"createdAt":"2024-01-01","updatedAt":"2024-01-01"}""")
        assertThat(entry?.category).isEqualTo("Autres")
        assertThat(entry?.isFavorite).isFalse()
    }

    @Test
    fun `toString never prints a secret`() {
        val entry = requireNotNull(EntryJson.fromJsonOrNull(dartJson[0]))
        assertThat(entry.toString()).doesNotContain(entry.password)
        assertThat(entry.toString()).doesNotContain(entry.username)
    }

    private fun parse(text: String): Entry? = EntryJson.fromJsonOrNull(Json.parseToJsonElement(text))
}
