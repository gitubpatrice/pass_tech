package com.filestech.pass_tech.core.legal

/**
 * The part of Markdown the four shipped legal documents actually use, turned into blocks a screen can
 * draw. It is not a Markdown engine and must not become one.
 *
 * **Why no library.** These documents come from `assets/`, never from the network, and their syntax
 * is ours because we write them: headings, bold, code, lists, one quote, one rule, and tables. A
 * library would add a dependency, its size, and a parser fed by files we control anyway — to support
 * syntax these documents do not contain.
 *
 * **Why the tables are kept.** They are where the verifiable facts are: the names of the files on the
 * phone, and the permission list measured on the built APK. A reader who cannot see them has to take
 * the prose on trust, which is the opposite of the point. On a narrow phone they are not drawn as
 * columns — see the screen — but nothing in them is dropped.
 *
 * **Why a pure function.** Nothing here touches Android or Compose, so the JVM tests run it over the
 * documents that actually ship. A table that stops being recognised then fails a test rather than
 * reaching a phone as a wall of pipes.
 */
object Markdown {

    /**
     * A run of text and the two marks the documents put on it. They nest — `**Backup `.ptbak`**` is
     * bold with a code run inside it — so the two are flags rather than two kinds of span.
     */
    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    /** One line of a table, already split on its pipes. */
    data class Row(val cells: List<List<Span>>)

    sealed interface Block {
        /** [level] is 1 for `#`, 2 for `##`. */
        data class Heading(val level: Int, val text: List<Span>) : Block

        data class Paragraph(val text: List<Span>) : Block

        /** [marker] is what the document wrote in front: `-`, or `2.` for a numbered step. */
        data class Item(val marker: String, val text: List<Span>) : Block

        data class Quote(val text: List<Span>) : Block

        data object Rule : Block

        data class Table(val header: Row, val rows: List<Row>) : Block
    }

    fun blocks(source: String): List<Block> = Reader().read(source)

    /**
     * The two marks, read left to right as switches rather than as pairs: `**` turns bold on and off,
     * a backtick does the same for code, and inside code `**` is two asterisks like any other
     * characters. That is what lets a code run sit inside a bold one, which the documents do write.
     *
     * A mark left open runs to the end of the block rather than throwing: a legal document has to
     * stay readable even when someone mistypes an asterisk in it. `MarkdownTest` holds the shipped
     * documents to balanced marks, so a mistyped one is caught before it is shipped, not after.
     */
    fun spans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val run = StringBuilder()
        var bold = false
        var code = false
        var index = 0
        fun cut() {
            if (run.isNotEmpty()) spans += Span(run.toString(), bold, code)
            run.setLength(0)
        }
        while (index < text.length) {
            when {
                !code && text.startsWith(BOLD, index) -> {
                    cut()
                    bold = !bold
                    index += BOLD.length
                }
                text[index] == CODE -> {
                    cut()
                    code = !code
                    index++
                }
                else -> {
                    run.append(text[index])
                    index++
                }
            }
        }
        cut()
        return spans
    }

    /** What the reader is in the middle of, and closes on a blank line or on the start of the next. */
    private enum class Open { PARAGRAPH, ITEM, QUOTE, TABLE }

    /**
     * One pass over the lines, with exactly one block open at a time.
     *
     * Everything these documents do across several lines — a paragraph hard-wrapped at a hundred
     * columns, a list item continued underneath, a quote, a table — is a line that joins what is
     * open. So there is one rule: a line either starts something, in which case what was open is
     * closed first, or it continues what is open.
     */
    private class Reader {

        private val blocks = mutableListOf<Block>()
        private var open: Open? = null
        private var marker = ""
        private val text = StringBuilder()
        private val rows = mutableListOf<String>()

        fun read(source: String): List<Block> {
            source.lineSequence().forEach(::line)
            close()
            return blocks.toList()
        }

        private fun line(raw: String) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> close()
                line.startsWith(PIPE) -> row(line)
                RULE.matches(line) -> rule()
                line.startsWith(QUOTE) -> quote(line)
                else -> body(line)
            }
        }

        private fun rule() {
            close()
            blocks += Block.Rule
        }

        private fun quote(line: String) {
            if (open != Open.QUOTE) close()
            append(Open.QUOTE, line.removePrefix(QUOTE).trim())
        }

        private fun row(line: String) {
            if (open != Open.TABLE) close()
            open = Open.TABLE
            rows += line
        }

        /**
         * A heading, the start of a list item, or prose.
         *
         * The item marker is only read at column zero. An indented `-` is a continuation line that
         * happens to begin with a dash, and these documents have no nested lists; reading it as a new
         * item would break a sentence in two rather than indent it.
         */
        private fun body(line: String) {
            val heading = HEADING.matchEntire(line)
            val item = ITEM.matchEntire(line)
            when {
                heading != null -> {
                    close()
                    blocks += Block.Heading(heading.groupValues[1].length, Markdown.spans(heading.groupValues[2]))
                }
                item != null -> {
                    close()
                    open = Open.ITEM
                    marker = item.groupValues[1]
                    text.append(item.groupValues[2].trim())
                }
                else -> append(Open.PARAGRAPH, line.trim())
            }
        }

        /** Continues what is open, or opens [kind] when nothing of that sort is. */
        private fun append(kind: Open, part: String) {
            if (open == null || open == Open.TABLE) {
                close()
                open = kind
            }
            if (text.isNotEmpty()) text.append(' ')
            text.append(part)
        }

        private fun close() {
            when (open) {
                Open.PARAGRAPH -> blocks += Block.Paragraph(Markdown.spans(text.toString()))
                Open.ITEM -> blocks += Block.Item(marker, Markdown.spans(text.toString()))
                Open.QUOTE -> blocks += Block.Quote(Markdown.spans(text.toString()))
                Open.TABLE -> blocks += table()
                null -> Unit
            }
            open = null
            marker = ""
            text.setLength(0)
            rows.clear()
        }

        /**
         * The `| --- | --- |` line is dropped: it carries no text, only the promise that the line
         * above it was the header. A table written without one still reads, its first line taken as
         * the header — losing that line instead would lose a fact.
         */
        private fun table(): Block.Table {
            val parsed = rows.map(::cells)
            return Block.Table(parsed.firstOrNull() ?: Row(emptyList()), parsed.drop(1).filterNot(::separator))
        }

        /** A pipe inside a cell would split it. None of these documents writes one, and none may. */
        private fun cells(line: String): Row = Row(line.trim().trim(PIPE).split(PIPE).map { Markdown.spans(it.trim()) })

        private fun separator(row: Row): Boolean =
            row.cells.isNotEmpty() && row.cells.all { cell -> SEPARATOR.matches(cell.joinToString("") { it.text }) }
    }

    private const val BOLD = "**"
    private const val CODE = '`'
    private const val QUOTE = ">"
    private const val PIPE = '|'
    private val HEADING = Regex("""(#{1,6})\s+(.*)""")
    private val ITEM = Regex("""([-*]|\d{1,3}\.)\s+(.*)""")
    private val RULE = Regex("""-{3,}""")
    private val SEPARATOR = Regex(""":?-{3,}:?""")
}
