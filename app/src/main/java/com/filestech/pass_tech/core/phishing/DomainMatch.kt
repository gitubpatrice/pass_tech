package com.filestech.pass_tech.core.phishing

import java.net.IDN
import java.util.Locale

/**
 * Comparing the domain an entry belongs to with the one the browser is showing, and nothing else:
 * no Android, no state, no clock. Every verdict of 2.7.1 is reproduced here except the ones listed
 * below, which are the defects this rewrite fixes — `app/src/test/resources/compat/2.7.1/phishing.json`
 * holds what 2.7.1 answered, produced by running its own code.
 */
object DomainMatch {

    enum class Verdict {
        /** Same domain, or a name under it. Copying goes ahead without a word. */
        OK,

        /**
         * Nothing to compare against: no browser was read, or what it showed was not a host. The
         * copy goes ahead, and the owner is told the check could not be made — a protection that
         * cannot see must not look like one that saw nothing wrong.
         */
        UNKNOWN,

        /** A name within [TYPO_DISTANCE] edits of the expected one. The owner may still copy. */
        TYPOSQUATTING,

        /** Another site altogether. No way through: closing the dialog is the only action. */
        MISMATCH,
    }

    data class Check(
        val verdict: Verdict,
        val expected: String? = null,
        val active: String? = null,
        val distance: Int? = null,
    )

    /** 2.7.1's threshold, kept: `paypal.com` against `paypa1.com` is one edit, `exarnple` two. */
    const val TYPO_DISTANCE = 2

    /**
     * The longest a DNS name can be. 2.7.1 cut both sides at 50 characters, and the cost of that cut
     * is paid in the wrong direction: two hosts differing only past the fiftieth character came out
     * **identical**, so the dialog offered "copy anyway" under the words "Distance: 0" — which reads
     * as "the same site" — between a real one and an impostor. Whole names cost 253 × 253 cells of
     * one row at a time, once per copy.
     */
    private const val MAX_COMPARED = 253

    /**
     * A plausible top-level label. Only what an address bar hands over is held to this: an entry
     * whose URL is an IP address keeps its value, so the comparison ends in [Verdict.UNKNOWN] — the
     * browser side could not be read — rather than in a silent [Verdict.OK].
     *
     * **A bare name does not, and this claimed it did** until the audit of 2026-09-24. [normalize]
     * refuses anything without a dot long before this pattern is reached, so an entry whose URL is
     * `mybank` or `intranet` leaves [check] nothing to compare and it answers [Verdict.OK]. The
     * password is copied either way — the `UNKNOWN` path copies it too, and the only difference is a
     * banner afterwards — so nothing is handed to an attacker by it. What is wrong is the comment:
     * "nothing to compare" and "compared, all is well" are not the same answer.
     *
     * **`xn--` belongs to this alphabet.** [normalize] has already punycoded the host, so an
     * internationalised top level arrives as `xn--p1ai` (`.рф`), `xn--fiqs8s` (`.中国`) or
     * `xn--3e0b707e` (`.한국`) — digits and a hyphen, which a letters-only pattern refuses. And
     * refusing it does not end in "could not check": the service keeps the LAST readable host for
     * fifteen seconds, so the page read just before answers in its place, and a bank on a `.рф`
     * address is compared against whatever came before it. No address bar can show a page title
     * ending in `.xn--…`, so nothing is lost by accepting it.
     */
    private val TOP_LEVEL = Regex("\\.(xn--[a-z0-9-]{2,59}|[a-z]{2,24})$")

    /**
     * The host of [url], in the one form both sides can be compared in: lower case, no `www.`, no
     * trailing root label, and ASCII.
     *
     * **The ASCII form is the first fix.** 2.7.1 punycoded the browser's side and not the entry's,
     * and its URL parser percent-encoded what it could not represent: an entry reading `société.fr`
     * became `soci%c3%a9t%c3%a9.fr` and was compared against the `xn--socit-esab.fr` the address bar
     * gives. Fifteen edits apart, so the verdict was [Verdict.MISMATCH] — the one with no way
     * through. Anyone whose bank has an accent in its name could never copy their password while
     * standing on the right page, and the app told them they were on a fake one.
     *
     * **The trailing dot is the second.** `example.com.` names the same host as `example.com`; one
     * edit apart, 2.7.1 called it typosquatting.
     */
    fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ') || !trimmed.contains('.')) return null
        val host = trimmed
            .substringAfter("://", trimmed)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            // Everything before `@` is the user information, and a `:` after the host is its port.
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase(Locale.ROOT)
            .trimEnd('.')
            .removePrefix("www.")
        if (host.isEmpty()) return null
        return try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT).ifEmpty { null }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * What an address bar is showing, or `null` when its text is not a host — a search, a page title,
     * an empty field. The top-level label is required here and only here: the text comes from another
     * app's widget, and a page chooses its own title.
     */
    fun fromAddressBar(raw: String): String? = normalize(raw)?.takeIf { TOP_LEVEL.containsMatchIn(it) }

    /**
     * The same, for a browser that keeps its address in a node's **description** rather than its
     * text. Firefox's address box does: since it became a Compose toolbar its text is empty and it
     * describes itself as `" example.com. Search or enter address"` — the address first, then the
     * browser's own hint in the phone's language.
     *
     * Only the first run of non-space characters is read, because no host holds a space and
     * everything after it is that translated hint. What is left still has to pass [fromAddressBar],
     * so a bar showing a search or a half-typed name reads as nothing at all.
     */
    fun fromAddressBarDescription(raw: String): String? = fromAddressBar(raw.trim().substringBefore(' '))

    /**
     * [url] is what the entry stores, [otherDomains] the other addresses it declares, [active] what
     * the browser is showing. Every side is normalised here, so a caller cannot hand in one form and
     * be compared against another.
     *
     * **Any declared domain is enough**, and that is the defect this fixes. An entry held ONE
     * address, while a federated sign-in moves the browser to another: `office.com` signs in at
     * `login.microsoftonline.com`, an Apple account at `appleid.apple.com`, a company at its Okta or
     * Auth0 host, `twitter.com` at `x.com`. Those are far more than [TYPO_DISTANCE] edits away, so
     * the verdict was [Verdict.MISMATCH] — the one with no way through at all. The owner could not
     * copy their own password on their own sign-in page, and the import made these entries itself:
     * the browsers and the other managers export the address of the login FORM.
     *
     * **Nothing is loosened to get there.** The distance is not relaxed and the top-level label is
     * still compared, so `paypal.tk` stays refused for a `paypal.com` entry: what changes is that the
     * owner can say, once and deliberately, which other domains are theirs. The distance reported is
     * the smallest of them and [Check.expected] the domain that gave it, so the dialog holds up the
     * closest thing the entry knows.
     */
    fun check(url: String, active: String?, otherDomains: List<String> = emptyList()): Check {
        val expected = (listOf(url) + otherDomains).mapNotNull(::normalize).distinct()
        if (expected.isEmpty()) return Check(Verdict.OK)
        val seen = active?.let { normalize(it) }
        // The entry's own URL is what "expected" names when there was nothing to compare against.
        return if (seen == null) Check(Verdict.UNKNOWN, expected = expected.first()) else compare(expected, seen)
    }

    /** [expected] holds at least one normalised host, and [seen] is one. */
    private fun compare(expected: List<String>, seen: String): Check {
        // A name under a declared domain is served by whoever holds that domain. The reverse is not
        // true and is refused: an entry noted `login.example.com` does not cover `example.com`.
        val covering = expected.firstOrNull { seen == it || seen.endsWith(".$it") }
        if (covering != null) return Check(Verdict.OK, covering, seen)
        val (closest, distance) = expected.map { it to distance(seen, it) }.minBy { it.second }
        val verdict = if (distance <= TYPO_DISTANCE) Verdict.TYPOSQUATTING else Verdict.MISMATCH
        return Check(verdict, closest, seen, distance)
    }

    /** Levenshtein, one row at a time, on at most [MAX_COMPARED] characters of each side. */
    fun distance(a: String, b: String): Int {
        val s = a.take(MAX_COMPARED)
        val t = b.take(MAX_COMPARED)
        if (s == t) return 0
        // They differ, so an empty side costs every character of the other.
        if (s.isEmpty() || t.isEmpty()) return maxOf(s.length, t.length)
        var previous = IntArray(t.length + 1) { it }
        var current = IntArray(t.length + 1)
        for (i in 1..s.length) {
            current[0] = i
            for (j in 1..t.length) {
                val substitution = previous[j - 1] + if (s[i - 1] == t[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[t.length]
    }
}
