package com.filestech.pass_tech.core.backup

import com.filestech.pass_tech.core.json.DartCastException
import com.filestech.pass_tech.core.json.optBoolean
import com.filestech.pass_tech.core.json.optInt
import com.filestech.pass_tech.core.json.optString
import com.filestech.pass_tech.core.model.Categories
import com.filestech.pass_tech.core.model.DartDateTime
import com.filestech.pass_tech.core.model.Entry
import com.filestech.pass_tech.core.model.EntryJson
import com.filestech.pass_tech.core.model.EntryType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * A plain file someone brings in: a Pass Tech JSON export, a Bitwarden JSON export, or a CSV from any
 * password manager. The encrypted `.ptbak` is [PtbakCodec]'s, not this class's.
 *
 * Every rule below is 2.7.1's (`ImportExportService.parse`), pinned by vectors that the Flutter code
 * itself produced (`app/src/test/resources/compat/2.7.1/import.json`, generator in `tools/compat`):
 * which format is recognised, which entry is skipped, what a missing title becomes, and the category
 * guessed from a title and a URL.
 *
 * One deliberate difference, in [bitwarden]: 2.7.1 reads `type` and `name` outside its own try, so ONE
 * item with a number where a string belongs makes it refuse the WHOLE file as invalid JSON. Here such
 * an item is skipped, like every other item it cannot read, and the rest of the export comes in.
 */
object ImportParser {

    /** 50 MB, as 2.7.1, measured the same way: in UTF-16 code units. */
    const val MAX_FILE_CHARS = 50 * 1024 * 1024

    /**
     * The longest a single field may be, on EVERY route in: a CSV cell, a JSON value of a Pass Tech
     * or a Bitwarden export, and the entries of a `.ptbak`.
     *
     * Beyond this, the file was built to make the app grow a buffer without end — or to hand a very
     * large value to something that reads every entry afterwards. The vault audit runs
     * `PasswordStrength` over each password at every unlock, and its cost follows the LENGTH of the
     * value, not the number of entries; one oversized `password` froze the app at each opening, for
     * as long as the entry stayed in the vault.
     *
     * Until 3.0.0 this bound existed but lived INSIDE the CSV reader, so three of the four routes
     * walked past it. That is the shape of defect this project keeps meeting: a guard aimed at one
     * of two twins.
     *
     * 64 KB is far above any real field — about twenty pages of text in a single note.
     */
    const val MAX_FIELD_CHARS = 64 * 1024

    /**
     * How many entries one file may carry.
     *
     * Counted on the rows of a CSV and on the items of a JSON array, BEFORE a single [Entry] is
     * built. [MAX_FILE_CHARS] does not bound this: `a,a,a,…` fits millions of one-character cells
     * into a file well under 50 MB, and each one becomes an object.
     *
     * 50 000 is an order of magnitude past the largest real vault.
     */
    const val MAX_ENTRIES = 50_000

    /** A TOTP secret is a few dozen characters; a huge one is truncated, never a reason to refuse a file. */
    const val MAX_TOTP_CHARS = 512

    /** The wire names are 2.7.1's, which the vectors record. */
    enum class Format(val wireName: String) {
        PASS_TECH("pass_tech"),
        BITWARDEN("bitwarden"),
        CSV("csv"),
        UNKNOWN("unknown"),
    }

    /** Why a file was refused. The screen turns it into words; the parser never speaks a language. */
    enum class Problem(val wireName: String) {
        TOO_LARGE("tooLarge"),
        EMPTY_FILE("emptyFile"),
        JSON_UNKNOWN_FORMAT("jsonUnknownFormat"),
        JSON_INVALID("jsonInvalid"),
        CSV_INVALID("csvInvalid"),
        CSV_EMPTY("csvEmpty"),
        CSV_NO_PASSWORD_COLUMN("csvNoPasswordColumn"),
        FIELD_TOO_LARGE("fieldTooLarge"),
    }

    class Result(val entries: List<Entry>, val format: Format, val problem: Problem? = null)

    /** A file refused outright, and why. Thrown from inside the CSV reader, which cannot return. */
    private class Refused(val problem: Problem) : Exception()

    private fun refused(problem: Problem) = Result(emptyList(), Format.UNKNOWN, problem)

    private val json = Json

    /**
     * Reads [content]. [untitled] is the title given to an entry that has none: it is DATA, written into
     * the vault, so the caller passes it in the language the app speaks at that moment (2.7.1 v2.7.0).
     *
     * [now] and [newId] are the defaults of a new entry, and are handed in so that the tests can pin them.
     */
    fun parse(
        content: String,
        untitled: String,
        now: () -> DartDateTime = DartDateTime::nowLocal,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Result {
        if (content.length > MAX_FILE_CHARS) return Result(emptyList(), Format.UNKNOWN, Problem.TOO_LARGE)
        val trimmed = content.dartTrim()
        if (trimmed.isEmpty()) return Result(emptyList(), Format.UNKNOWN, Problem.EMPTY_FILE)
        // The CSV reader gets the file as it came: 2.7.1 only trims to decide which reader to use.
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            json(trimmed, untitled, now, newId)
        } else {
            csv(content, untitled, now, newId)
        }
    }

    private fun json(content: String, untitled: String, now: () -> DartDateTime, newId: () -> String): Result {
        val root = try {
            json.parseToJsonElement(content)
        } catch (_: Exception) {
            return Result(emptyList(), Format.UNKNOWN, Problem.JSON_INVALID)
        }
        val items = (root as? JsonObject)?.get("items") as? JsonArray
        val array = items ?: root as? JsonArray
        // The first two branches are the bounds the CSV reader applies as it goes, applied here to
        // the whole tree at once and before a single Entry exists.
        return when {
            hasOversizedField(root) -> refused(Problem.FIELD_TOO_LARGE)
            array == null -> refused(Problem.JSON_UNKNOWN_FORMAT)
            array.size > MAX_ENTRIES -> refused(Problem.TOO_LARGE)
            items != null -> Result(bitwarden(items, untitled, now, newId), Format.BITWARDEN)
            else -> Result(passTech(array, now, newId), Format.PASS_TECH)
        }
    }

    /**
     * Whether any text anywhere in [root] is longer than [MAX_FIELD_CHARS].
     *
     * It walks whatever the file holds rather than a list of field names, so a format added later is
     * covered without anyone remembering to come back here. Iterative, never recursive: kotlinx
     * parses arbitrarily deep JSON, and a recursive walk would meet its own stack before it met an
     * oversized field.
     *
     * Public because [PtbakCodec] needs the same bound: an encrypted backup is still a file somebody
     * hands over, and its passphrase is handed over with it.
     */
    fun hasOversizedField(root: JsonElement): Boolean {
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonPrimitive -> if (element.isString && element.content.length > MAX_FIELD_CHARS) return true
                is JsonArray -> element.forEach(pending::addLast)
                is JsonObject -> element.values.forEach(pending::addLast)
            }
        }
        return false
    }

    /**
     * A Pass Tech export, read leniently: a file written by hand or by another tool may have no `id` and
     * no dates, which the vault's own reader requires. The defaults are injected for the import only.
     */
    private fun passTech(items: JsonArray, now: () -> DartDateTime, newId: () -> String): List<Entry> =
        items.filterIsInstance<JsonObject>().mapNotNull { item ->
            val stamp = now().toIso8601String()
            val filled = buildJsonObject {
                item.forEach { (key, value) -> put(key, value) }
                if ("id" !in item) put("id", newId())
                if ("createdAt" !in item) put("createdAt", stamp)
                if ("updatedAt" !in item) put("updatedAt", stamp)
            }
            EntryJson.fromJsonOrNull(filled)
        }

    private fun bitwarden(items: JsonArray, untitled: String, now: () -> DartDateTime, newId: () -> String): List<Entry> =
        items.filterIsInstance<JsonObject>().mapNotNull { item ->
            try {
                bitwardenItem(item, untitled, now, newId)
            } catch (_: DartCastException) {
                // A field of the wrong type: this item is skipped, where 2.7.1 refused the whole export.
                null
            }
        }

    private fun bitwardenItem(item: JsonObject, untitled: String, now: () -> DartDateTime, newId: () -> String): Entry? {
        val name = item.optString("name") ?: untitled
        val notes = item.optString("notes") ?: ""
        val favorite = item.optBoolean("favorite") ?: false
        return when (item.optInt("type") ?: BITWARDEN_LOGIN) {
            BITWARDEN_LOGIN -> bitwardenLogin(item, name, notes, favorite, now, newId)
            BITWARDEN_NOTE -> newEntry(
                type = EntryType.NOTE,
                title = name,
                category = Categories.OTHER,
                notes = notes,
                favorite = favorite,
                now = now,
                newId = newId,
            )
            BITWARDEN_CARD -> bitwardenCard(item, name, notes, favorite, now, newId)
            // Type 4 is an identity: 2.7.1 ignores it, and so does every other type to come.
            else -> null
        }
    }

    private fun bitwardenLogin(
        item: JsonObject,
        name: String,
        notes: String,
        favorite: Boolean,
        now: () -> DartDateTime,
        newId: () -> String,
    ): Entry {
        val login = item["login"] as? JsonObject
        val uris = login?.get("uris") as? JsonArray
        // 2.7.1 kept the first and dropped the rest. Bitwarden lists exactly what an entry needs to
        // be usable — `office.com` AND `login.microsoftonline.com` — and dropping them is what made
        // an imported entry unable to copy its own password on its own sign-in page.
        val addresses = uris.orEmpty()
            .mapNotNull { (it as? JsonObject)?.optString("uri")?.dartTrim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val url = addresses.firstOrNull() ?: ""
        return newEntry(
            type = EntryType.PASSWORD,
            title = name,
            category = guessCategory(name, url),
            username = login?.optString("username") ?: "",
            password = login?.optString("password") ?: "",
            url = url,
            otherDomains = addresses.drop(1).take(Entry.MAX_OTHER_DOMAINS),
            totp = boundTotp(login?.optString("totp") ?: ""),
            notes = notes,
            favorite = favorite,
            now = now,
            newId = newId,
        )
    }

    private fun bitwardenCard(
        item: JsonObject,
        name: String,
        notes: String,
        favorite: Boolean,
        now: () -> DartDateTime,
        newId: () -> String,
    ): Entry {
        val card = item["card"] as? JsonObject
        val month = (card?.optString("expMonth") ?: "").padStart(2, '0')
        val year = (card?.optString("expYear") ?: "").removePrefix("20")
        return newEntry(
            type = EntryType.CARD,
            title = name,
            category = Categories.BANK,
            notes = notes,
            favorite = favorite,
            cardholderName = card?.optString("cardholderName") ?: "",
            cardNumber = (card?.optString("number") ?: "").replace(" ", ""),
            cardExpiry = if (month.isEmpty() || year.isEmpty()) "" else "$month/$year",
            cardCvv = card?.optString("code") ?: "",
            cardIssuer = card?.optString("brand") ?: "",
            now = now,
            newId = newId,
        )
    }

    private fun csv(content: String, untitled: String, now: () -> DartDateTime, newId: () -> String): Result {
        val rows = try {
            CsvReader(content).read()
        } catch (refusal: Refused) {
            return refused(refusal.problem)
        }
        return csvRows(rows, untitled, now, newId)
    }

    private fun csvRows(
        rows: List<List<String>>,
        untitled: String,
        now: () -> DartDateTime,
        newId: () -> String,
    ): Result {
        // Kept as 2.7.1 has it, and never reached: a file with one character that is not whitespace
        // makes one row, and a file without one was refused as empty above.
        if (rows.isEmpty()) return Result(emptyList(), Format.UNKNOWN, Problem.CSV_EMPTY)
        val header = rows.first().map { it.lowercase().dartTrim() }
        fun column(vararg names: String): Int? = names.firstNotNullOfOrNull { header.indexOf(it).takeIf { i -> i >= 0 } }

        val name = column("name", "title", "site", "label")
        val url = column("url", "login_uri", "website", "web site")
        val user = column("username", "user", "login", "login_username", "email")
        val password = column("password", "login_password", "pass")
            ?: return Result(emptyList(), Format.UNKNOWN, Problem.CSV_NO_PASSWORD_COLUMN)
        val note = column("note", "notes", "comment", "comments")
        val totp = column("totp", "otp", "login_totp")

        val entries = rows.drop(1).mapNotNull { row ->
            // A line with a single cell is not a record: 2.7.1 skips it before reading anything.
            if (row.size < 2) return@mapNotNull null
            fun at(index: Int?): String = if (index == null || index >= row.size) "" else row[index].dartTrim()
            val title = at(name).ifEmpty { at(url) }
            if (title.isEmpty() && at(password).isEmpty()) return@mapNotNull null
            newEntry(
                type = EntryType.PASSWORD,
                title = title.ifEmpty { untitled },
                category = guessCategory(title, at(url)),
                username = at(user),
                password = at(password),
                url = at(url),
                totp = boundTotp(at(totp)),
                notes = at(note),
                now = now,
                newId = newId,
            )
        }
        return Result(entries, Format.CSV)
    }

    /** Quoted fields, doubled quotes inside them, and either line ending. Empty lines are not rows. */
    private class CsvReader(private val content: String) {

        private val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val cell = StringBuilder()
        private var inQuotes = false

        fun read(): List<List<String>> {
            var i = 0
            while (i < content.length) {
                i += if (inQuotes) quoted(i) else plain(i)
                if (cell.length > MAX_FIELD_CHARS) throw Refused(Problem.FIELD_TOO_LARGE)
            }
            endRow()
            return rows
        }

        /** Inside quotes, every character belongs to the cell; two quotes are one, and one ends them. */
        private fun quoted(i: Int): Int {
            val c = content[i]
            return when {
                c != QUOTE -> {
                    cell.append(c)
                    1
                }
                content.getOrNull(i + 1) == QUOTE -> {
                    cell.append(QUOTE)
                    2
                }
                else -> {
                    inQuotes = false
                    1
                }
            }
        }

        /** Outside quotes: a comma ends a cell, a line ending ends a row, and CRLF counts once. */
        private fun plain(i: Int): Int {
            when (val c = content[i]) {
                QUOTE -> inQuotes = true
                ',' -> endCell()
                '\n' -> endRow()
                '\r' -> {
                    endRow()
                    if (content.getOrNull(i + 1) == '\n') return 2
                }
                else -> cell.append(c)
            }
            return 1
        }

        private fun endCell() {
            row.add(cell.toString())
            cell.clear()
        }

        private fun endRow() {
            if (cell.isEmpty() && row.isEmpty()) return
            endCell()
            // Counted on the ROWS, not on the size of the file: a file of `a,a,a,…` stays well
            // under MAX_FILE_CHARS while building millions of one-character cells.
            if (rows.size >= MAX_ENTRIES) throw Refused(Problem.TOO_LARGE)
            rows.add(row)
            row = mutableListOf()
        }
    }

    /** 2.7.1's guess, word for word: the first list that matches wins, and a URL alone means the web. */
    private fun guessCategory(title: String, url: String): String {
        val text = "${title.lowercase()} ${url.lowercase()}"
        return when {
            BANK_WORDS.any(text::contains) -> Categories.BANK
            EMAIL_WORDS.any(text::contains) -> Categories.EMAIL
            SOCIAL_WORDS.any(text::contains) -> Categories.SOCIAL
            url.startsWith("http") -> Categories.WEB
            else -> Categories.OTHER
        }
    }

    private fun boundTotp(raw: String): String = if (raw.length <= MAX_TOTP_CHARS) raw else raw.substring(0, MAX_TOTP_CHARS)

    @Suppress("LongParameterList")
    private fun newEntry(
        type: EntryType,
        title: String,
        category: String,
        username: String = "",
        password: String = "",
        url: String = "",
        otherDomains: List<String> = emptyList(),
        totp: String = "",
        notes: String = "",
        favorite: Boolean = false,
        cardholderName: String = "",
        cardNumber: String = "",
        cardExpiry: String = "",
        cardCvv: String = "",
        cardIssuer: String = "",
        now: () -> DartDateTime,
        newId: () -> String,
    ): Entry {
        val stamp = now()
        return Entry(
            id = newId(),
            type = type,
            title = title,
            category = category,
            username = username,
            password = password,
            url = url,
            otherDomains = otherDomains,
            totpSecret = totp,
            notes = notes,
            isFavorite = favorite,
            cardholderName = cardholderName,
            cardNumber = cardNumber,
            cardExpiry = cardExpiry,
            cardCvv = cardCvv,
            cardIssuer = cardIssuer,
            createdAt = stamp,
            updatedAt = stamp,
        )
    }

    /**
     * Dart's `String.trim`: Unicode whitespace plus the byte order mark, where Java's `trim` leaves a
     * non-breaking space and a BOM in place. A CSV saved by a Windows editor starts with a BOM, and its
     * first column header would otherwise never match.
     */
    private fun String.dartTrim(): String = trim { it in DART_WHITESPACE }

    /** Unicode whitespace, as `String.trim` defines it in Dart, plus the byte order mark. */
    private val DART_WHITESPACE: Set<Char> =
        ((0x09..0x0d) + (0x2000..0x200a) + listOf(0x20, 0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff))
            .mapTo(mutableSetOf(), ::Char)

    private const val QUOTE = '"'
    private const val BITWARDEN_LOGIN = 1
    private const val BITWARDEN_NOTE = 2
    private const val BITWARDEN_CARD = 3

    private val BANK_WORDS = listOf("bank", "banque", "boursorama", "credit", "paypal", "revolut")
    private val EMAIL_WORDS = listOf("mail", "outlook", "gmail", "proton", "yahoo")
    private val SOCIAL_WORDS = listOf("facebook", "twitter", "insta", "linkedin", "tiktok", "snap")
}
