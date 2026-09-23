package com.filestech.pass_tech.core.legal

import com.filestech.pass_tech.core.legal.Markdown.Block
import com.filestech.pass_tech.core.legal.Markdown.Span
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The reader, and then the four documents that actually ship read through it.
 *
 * The second half is the half that matters. Made-up input proves the rules; the shipped documents
 * prove that those rules are the ones the documents are written to. A privacy policy that reaches a
 * phone as a wall of pipes and asterisks is not a privacy policy, and nothing about the build would
 * have said so — these files are data, so no compiler reads them.
 */
class MarkdownTest {

    // ---- the marks ----

    @Test
    fun `plain text is one span with nothing on it`() {
        assertThat(Markdown.spans("nothing here")).containsExactly(Span("nothing here"))
    }

    @Test
    fun `bold is a run of text, not a pair of marks`() {
        assertThat(Markdown.spans("plain **loud** again"))
            .containsExactly(Span("plain "), Span("loud", bold = true), Span(" again"))
            .inOrder()
    }

    @Test
    fun `a code run sits inside a bold one, because the documents write it that way`() {
        assertThat(Markdown.spans("**Backup `.ptbak`**"))
            .containsExactly(Span("Backup ", bold = true), Span(".ptbak", bold = true, code = true))
            .inOrder()
    }

    @Test
    fun `asterisks inside code are asterisks`() {
        assertThat(Markdown.spans("`a ** b`")).containsExactly(Span("a ** b", code = true))
    }

    @Test
    fun `a mark left open runs to the end rather than throwing`() {
        assertThat(Markdown.spans("start **rest"))
            .containsExactly(Span("start "), Span("rest", bold = true))
            .inOrder()
    }

    // ---- the blocks ----

    @Test
    fun `a paragraph wrapped over three lines comes back as one`() {
        val blocks = Markdown.blocks("one\ntwo\nthree")
        assertThat(blocks).hasSize(1)
        assertThat(text(blocks[0])).isEqualTo("one two three")
    }

    @Test
    fun `a blank line ends what was open`() {
        val blocks = Markdown.blocks("one\n\ntwo")
        assertThat(blocks.map(::text)).containsExactly("one", "two").inOrder()
    }

    @Test
    fun `headings carry the level they were written at`() {
        val blocks = Markdown.blocks("# Title\n\n## Section")
        assertThat(blocks.map { (it as Block.Heading).level }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `an item keeps its marker and takes the line indented under it`() {
        val blocks = Markdown.blocks("- first line\n  continued\n- second")
        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Item).marker).isEqualTo("-")
        assertThat(text(blocks[0])).isEqualTo("first line continued")
        assertThat(text(blocks[1])).isEqualTo("second")
    }

    @Test
    fun `a numbered item keeps the number the document wrote`() {
        val blocks = Markdown.blocks("1. one\n   still one\n2. two")
        assertThat((blocks[0] as Block.Item).marker).isEqualTo("1.")
        assertThat(text(blocks[0])).isEqualTo("one still one")
        assertThat((blocks[1] as Block.Item).marker).isEqualTo("2.")
    }

    @Test
    fun `a quote joins its lines and keeps none of its angles`() {
        val blocks = Markdown.blocks("> one\n> two")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(Block.Quote::class.java)
        assertThat(text(blocks[0])).isEqualTo("one two")
    }

    @Test
    fun `three dashes are a rule, a dash and a space are an item`() {
        assertThat(Markdown.blocks("---")).containsExactly(Block.Rule)
        assertThat(Markdown.blocks("- item").single()).isInstanceOf(Block.Item::class.java)
    }

    @Test
    fun `a table keeps its header and its rows, and drops its separator`() {
        val table = Markdown.blocks(
            """
            | File | What it holds |
            | --- | --- |
            | `pt_state.enc` | Counters |
            """.trimIndent(),
        ).single() as Block.Table
        assertThat(table.header.cells.map(::plain)).containsExactly("File", "What it holds").inOrder()
        assertThat(table.rows).hasSize(1)
        assertThat(table.rows[0].cells.map(::plain)).containsExactly("pt_state.enc", "Counters").inOrder()
    }

    @Test
    fun `a line without a pipe ends the table`() {
        val blocks = Markdown.blocks("| a |\nafter")
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isInstanceOf(Block.Table::class.java)
        assertThat(text(blocks[1])).isEqualTo("after")
    }

    // ---- the documents that ship ----

    @Test
    fun `every file in the folder is one of the documents, in a language`() {
        assertThat(documents).isNotEmpty()
        documents.forEach { file -> assertWithMessage(file.name).that(parts(file)).isNotNull() }
    }

    @Test
    fun `English is there for both documents, since it is what every other language falls back to`() {
        val names = documents.map { it.name }
        LegalDocument.entries.forEach { document ->
            assertThat(names).contains(File(document.asset(LegalDocument.FALLBACK)).name)
        }
    }

    /**
     * Terms and a policy travel together: a language that reads its rights in its own words and its
     * obligations in another has been given half a translation, which reads as an oversight because
     * it is one.
     */
    @Test
    fun `a language has both documents or neither`() {
        val languages = documents.mapNotNull(::parts).groupBy({ it.first }, { it.second })
        assertThat(languages["PRIVACY"].orEmpty().toSet()).isEqualTo(languages["TERMS"].orEmpty().toSet())
    }

    @TestFactory
    fun `nothing in a shipped document is left unread`(): List<DynamicTest> = documents.map { file ->
        DynamicTest.dynamicTest(file.name) {
            Markdown.blocks(file.readText()).forEach { block ->
                val text = text(block)
                assertWithMessage(text).that(text).doesNotContain("**")
                assertWithMessage(text).that(text).doesNotContain("`")
                assertWithMessage(text).that(text).doesNotContain("|")
                assertWithMessage(text).that(listOf("- ", "# ", "> ").any(text::startsWith)).isFalse()
            }
        }
    }

    /**
     * Counted over each blank-line group, which is what becomes one block: a stray asterisk turns
     * everything from it to the end of that block bold, and nothing else would ever say so.
     */
    @TestFactory
    fun `every mark in a shipped document is closed`(): List<DynamicTest> = documents.map { file ->
        DynamicTest.dynamicTest(file.name) {
            file.readText().split(BLANK_LINE).forEachIndexed { index, chunk ->
                val where = "${file.name}, group ${index + 1}"
                assertWithMessage(where).that(chunk.split("**").size % 2).isEqualTo(1)
                assertWithMessage(where).that(chunk.count { it == '`' } % 2).isEqualTo(0)
            }
        }
    }

    /** An independent recount: every pipe line of the source, minus the separators, is in a table. */
    @TestFactory
    fun `every table line of a shipped document ends up in a table`(): List<DynamicTest> = documents.map { file ->
        DynamicTest.dynamicTest(file.name) {
            val written = file.readLines()
                .map(String::trim)
                .filter { it.startsWith("|") }
                .filterNot { line -> line.trim('|').split('|').all { it.trim().matches(DASHES) } }
            val read = Markdown.blocks(file.readText()).filterIsInstance<Block.Table>()
            assertThat(read.sumOf { it.rows.size + 1 }).isEqualTo(written.size)
        }
    }

    /**
     * The count is the point, not an incident of the current text: the policy's two tables are the
     * files on the phone and the permissions measured on the built APK. They are the two places a
     * reader can check a claim rather than believe it, and they are the two most likely to be lost to
     * a rewrite that finds prose tidier.
     */
    @Test
    fun `the privacy policy still carries its two tables, in every language it has`() {
        documents.filter { it.name.startsWith("PRIVACY") }.forEach { file ->
            val tables = Markdown.blocks(file.readText()).filterIsInstance<Block.Table>()
            assertWithMessage(file.name).that(tables).hasSize(2)
            tables.forEach { assertWithMessage(file.name).that(it.rows).isNotEmpty() }
        }
    }

    private val documents: List<File> =
        File("src/main/assets/legal").listFiles().orEmpty().filter { it.name.endsWith(".md") }.sortedBy { it.name }

    private fun parts(file: File): Pair<String, String>? =
        NAMING.matchEntire(file.name)?.destructured?.let { (document, language) -> document to language }

    private fun plain(spans: List<Span>): String = spans.joinToString("") { it.text }

    private fun text(block: Block): String = when (block) {
        is Block.Heading -> plain(block.text)
        is Block.Paragraph -> plain(block.text)
        is Block.Item -> plain(block.text)
        is Block.Quote -> plain(block.text)
        Block.Rule -> ""
        is Block.Table -> (listOf(block.header) + block.rows).joinToString(" ") { row ->
            row.cells.joinToString(" ", transform = ::plain)
        }
    }

    private companion object {
        val NAMING = Regex("""(PRIVACY|TERMS)\.([a-z]{2})\.md""")
        val BLANK_LINE = Regex("""\r?\n[ \t]*\r?\n""")
        val DASHES = Regex(""":?-{3,}:?""")
    }
}
