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

    /** 2.7.1's cut, kept: the cost of the distance is the product of the two lengths. */
    private const val MAX_COMPARED = 50

    /**
     * A plausible top-level label. Only what an address bar hands over is held to this: an entry
     * whose URL is an IP address or a bare name keeps its value, so the comparison ends in
     * [Verdict.UNKNOWN] — the browser side could not be read — rather than in a silent [Verdict.OK].
     */
    private val TOP_LEVEL = Regex("\\.[a-z]{2,24}$")

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
     * [url] is what the entry stores, [active] what the browser is showing. Both are normalised here,
     * so a caller cannot hand in one form and be compared against another.
     */
    fun check(url: String, active: String?): Check {
        val expected = normalize(url) ?: return Check(Verdict.OK)
        val seen = active?.let { normalize(it) } ?: return Check(Verdict.UNKNOWN, expected = expected)
        // A name under the expected domain is served by whoever holds that domain. The reverse is
        // not true and is refused: an entry noted `login.example.com` does not cover `example.com`.
        if (seen == expected || seen.endsWith(".$expected")) return Check(Verdict.OK, expected, seen)
        val distance = distance(seen, expected)
        val verdict = if (distance <= TYPO_DISTANCE) Verdict.TYPOSQUATTING else Verdict.MISMATCH
        return Check(verdict, expected, seen, distance)
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
