package com.filestech.pass_tech.core.backup

import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The import reader against the answers of 2.7.1's own `ImportExportService.parse`, case by case:
 * `app/src/test/resources/compat/2.7.1/import.json`, written by `tools/compat/import_vectors_test.dart`.
 *
 * The three generated fields (id, dates) are not compared: they are new on every run, by design.
 */
class ImportParserTest {

    private val vectors = Json.parseToJsonElement(Resources.text("compat/2.7.1/import.json")).let { it as JsonObject }

    private val fixedDate = DartDateTime.parseOrNull("2026-09-22T18:00:00.000")!!

    private fun parse(content: String) = ImportParser.parse(content, UNTITLED, now = { fixedDate }, newId = { "generated" })

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** Every field 2.7.1 records, in its own words, so a mismatch names the field. */
    private fun fields(entry: Entry): Map<String, Any> = mapOf(
        "type" to entry.type.wireName,
        "title" to entry.title,
        "category" to entry.category,
        "username" to entry.username,
        "password" to entry.password,
        "url" to entry.url,
        "totpSecret" to entry.totpSecret,
        "notes" to entry.notes,
        "isFavorite" to entry.isFavorite,
        "cardholderName" to entry.cardholderName,
        "cardNumber" to entry.cardNumber,
        "cardExpiry" to entry.cardExpiry,
        "cardCvv" to entry.cardCvv,
        "cardPin" to entry.cardPin,
        "cardIssuer" to entry.cardIssuer,
    )

    private fun expectedFields(entry: JsonObject): Map<String, Any> =
        entry.mapValues { (_, value) ->
            val primitive = value.jsonPrimitive
            if (primitive.isString) primitive.content else primitive.boolean
        }

    @TestFactory
    fun `every file reads exactly as 2_7_1 read it`(): List<DynamicTest> = vectors.map { (name, element) ->
        val expected = element as JsonObject
        DynamicTest.dynamicTest(name) {
            val result = parse(expected.text("input"))
            assertThat(result.format.wireName).isEqualTo(expected.text("format"))
            assertThat(result.problem?.wireName).isEqualTo(expected.getValue("error").jsonPrimitive.contentOrNull)
            val entries = (expected.getValue("entries") as JsonArray).map { expectedFields(it as JsonObject) }
            assertThat(result.entries.map(::fields)).isEqualTo(entries)
        }
    }

    @Test
    fun `the vectors cover every format and every refusal`() {
        val formats = vectors.values.map { (it as JsonObject).text("format") }.toSet()
        assertThat(formats).containsExactly("csv", "pass_tech", "bitwarden", "unknown")
        val refusals = vectors.values.mapNotNull { (it as JsonObject).getValue("error").jsonPrimitive.contentOrNull }.toSet()
        // csvEmpty and the two caps are not here: no file can reach csvEmpty (see the parser), and a
        // 50 MB file has no business in a vector. Both have their own test below.
        assertThat(refusals).containsExactly("emptyFile", "csvNoPasswordColumn", "jsonUnknownFormat", "jsonInvalid")
    }

    @Test
    fun `a file above the cap is refused before anything is read`() {
        val huge = "a".repeat(ImportParser.MAX_FILE_CHARS + 1)
        assertThat(parse(huge).problem).isEqualTo(ImportParser.Problem.TOO_LARGE)
    }

    @Test
    fun `a cell built to grow without end stops the import, and says which cell`() {
        val cell = "x".repeat(ImportParser.MAX_CELL_CHARS + 1)
        val result = parse("name,password\n\"$cell\",pw\n")
        assertThat(result.problem).isEqualTo(ImportParser.Problem.CELL_TOO_LARGE)
        assertThat(result.entries).isEmpty()
        // One character less is a cell like any other.
        assertThat(parse("name,password\n\"${cell.dropLast(1)}\",pw\n").entries).hasSize(1)
    }

    @Test
    fun `a huge secret is truncated, and the rest of the entry comes in`() {
        val secret = "A".repeat(ImportParser.MAX_TOTP_CHARS + 100)
        val entry = parse("name,password,totp\nSite,pw,$secret\n").entries.single()
        assertThat(entry.totpSecret).hasLength(ImportParser.MAX_TOTP_CHARS)
        assertThat(entry.password).isEqualTo("pw")
    }

    @Test
    fun `an entry of a Pass Tech export keeps its own id and dates`() {
        val file = """[{"id":"kept","title":"T","category":"Autres",""" +
            """"createdAt":"2020-02-03T04:05:06.000","updatedAt":"2021-02-03T04:05:06.000"}]"""
        val entry = parse(file)
            .entries
            .single()
        assertThat(entry.id).isEqualTo("kept")
        assertThat(entry.createdAt.toIso8601String()).isEqualTo("2020-02-03T04:05:06.000")
        assertThat(entry.updatedAt.toIso8601String()).isEqualTo("2021-02-03T04:05:06.000")
    }

    @Test
    fun `an entry with no id or dates is given them`() {
        val entry = parse("""[{"title":"T","category":"Autres"}]""").entries.single()
        assertThat(entry.id).isEqualTo("generated")
        assertThat(entry.createdAt).isEqualTo(fixedDate)
        assertThat(entry.updatedAt).isEqualTo(fixedDate)
    }

    /**
     * 2.7.1 reads a Bitwarden item's `type` and `name` outside its own try: one item with a number where
     * a string belongs makes it refuse the whole export. Here that item is skipped, and the rest comes in.
     */
    @Test
    fun `a Bitwarden item that cannot be read is skipped, and the export still imports`() {
        val result = parse("""{"items":[{"type":"1","name":5},{"type":2,"name":"Kept"}]}""")
        assertThat(result.format).isEqualTo(ImportParser.Format.BITWARDEN)
        assertThat(result.entries.map { it.title }).containsExactly("Kept")
        assertThat(result.entries.single().type).isEqualTo(EntryType.NOTE)
    }

    /**
     * 2.7.1 kept the first address of a Bitwarden item and dropped the rest — and the rest is exactly
     * what a federated sign-in needs, so an imported entry could not copy its own password on its own
     * sign-in page. They are all kept now, in order, without blanks or repeats.
     */
    @Test
    fun `every address of a Bitwarden login is kept, not only the first`() {
        val file = """{"items":[{"type":1,"name":"Office","login":{"uris":[""" +
            """{"uri":"https://office.com"},{"uri":"https://login.microsoftonline.com"},""" +
            """{"uri":" https://office.com "},{"uri":""},{"uri":"https://portal.office.com"}]}}]}"""
        val entry = parse(file).entries.single()
        assertThat(entry.url).isEqualTo("https://office.com")
        assertThat(entry.otherDomains)
            .containsExactly("https://login.microsoftonline.com", "https://portal.office.com")
            .inOrder()
    }

    @Test
    fun `a Bitwarden login with no address at all keeps none`() {
        assertThat(parse("""{"items":[{"type":1,"name":"Bare","login":{"username":"u"}}]}""").entries.single().otherDomains)
            .isEmpty()
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
