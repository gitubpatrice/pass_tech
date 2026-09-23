package com.filestech.pass_tech.core.phishing

import com.filestech.pass_tech.core.phishing.DomainMatch.Verdict
import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test

/**
 * The comparison, against the verdicts of Pass Tech 2.7.1 itself: every row of
 * `compat/2.7.1/phishing.json` was produced by running its `AntiPhishingService.check`, through
 * `tools/compat/phishing_vectors_test.dart`.
 *
 * Every row must match, except the four listed in [CHANGED]. Those four are the defects: three
 * accented domains that 2.7.1 called impostors, and a name written with its root label. Listing them
 * one by one is the point — a rewrite that quietly disagreed anywhere else would fail here.
 */
class DomainMatchTest {

    private val vectors = Json.parseToJsonElement(Resources.text("compat/2.7.1/phishing.json")).jsonArray

    private companion object {
        /**
         * (entry URL, active domain) -> what this rewrite answers instead, and what 2.7.1 answered.
         * A [Verdict.MISMATCH] is the refusal with no way through: 2.7.1 gave it to an owner whose
         * bank simply has an accent in its name, on the very page they were meant to be on.
         */
        val CHANGED = mapOf(
            ("société.fr" to "xn--socit-esab.fr") to (Verdict.OK to Verdict.MISMATCH),
            ("https://société.fr/compte" to "xn--socit-esab.fr") to (Verdict.OK to Verdict.MISMATCH),
            ("münchen.de" to "xn--mnchen-3ya.de") to (Verdict.OK to Verdict.MISMATCH),
            ("example.com." to "example.com") to (Verdict.OK to Verdict.TYPOSQUATTING),
        )
    }

    @Test
    fun `every verdict of 2-7-1 is reproduced, but the four that were wrong`() {
        var changed = 0
        for (element in vectors) {
            val row = element as JsonObject
            val url = row.string("url")!!
            val active = row.string("active")
            val theirs = Verdict.valueOf(row.string("verdict")!!.uppercase())
            val ours = DomainMatch.check(url, active).verdict
            val change = CHANGED[url to active]
            if (change == null) {
                assertThat("$url | $active -> $ours").isEqualTo("$url | $active -> $theirs")
            } else {
                changed++
                assertThat(theirs).isEqualTo(change.second)
                assertThat(ours).isEqualTo(change.first)
            }
        }
        // Every listed change was met: a row that stopped being in the file would go unnoticed.
        assertThat(changed).isEqualTo(CHANGED.size)
        assertThat(vectors).hasSize(34)
    }

    @Test
    fun `the domain of the entry is read the same way as the browser's`() {
        val expected = "example.com"
        val forms = listOf(
            "example.com",
            "EXAMPLE.COM",
            "www.example.com",
            "https://www.EXAMPLE.com/x?y=1",
            " example.com ",
            "example.com.",
        )
        for (written in forms) {
            assertThat(DomainMatch.normalize(written)).isEqualTo(expected)
        }
    }

    @Test
    fun `an accented name is read in the form the address bar shows`() {
        // Measured, not guessed: the first version of this file carried a punycode form I had
        // written by hand, and it was wrong by one character (2026-09-23).
        assertThat(DomainMatch.normalize("société.fr")).isEqualTo("xn--socit-esab.fr")
        assertThat(DomainMatch.normalize("SOCIÉTÉ.FR")).isEqualTo("xn--socit-esab.fr")
        assertThat(DomainMatch.normalize("xn--socit-esab.fr")).isEqualTo("xn--socit-esab.fr")
    }

    @Test
    fun `nothing to compare against`() {
        for (nothing in listOf("", "   ", "not a url", "localhost", "example")) {
            assertThat(DomainMatch.normalize(nothing)).isNull()
            assertThat(DomainMatch.check(nothing, "evil.com").verdict).isEqualTo(Verdict.OK)
        }
    }

    @Test
    fun `a host is taken apart the way a browser does`() {
        assertThat(DomainMatch.normalize("https://user:secret@example.com:8443/path?q=1#top")).isEqualTo("example.com")
    }

    @Test
    fun `only the address bar is held to a top-level label`() {
        // A search, a page title, a half-typed name: nothing a domain can be built from.
        assertThat(DomainMatch.fromAddressBar("192.168.1.1")).isNull()
        assertThat(DomainMatch.fromAddressBar("Ma Banque — connexion")).isNull()
        assertThat(DomainMatch.fromAddressBar("")).isNull()
        assertThat(DomainMatch.fromAddressBar("https://example.com/login")).isEqualTo("example.com")
        // The entry's own URL keeps it, so the comparison ends in "could not check" and says so.
        assertThat(DomainMatch.normalize("192.168.1.1")).isEqualTo("192.168.1.1")
        assertThat(DomainMatch.check("192.168.1.1", null).verdict).isEqualTo(Verdict.UNKNOWN)
    }

    @Test
    fun `a name under the entry's domain is the entry's, and the reverse is not`() {
        assertThat(DomainMatch.check("example.com", "login.example.com").verdict).isEqualTo(Verdict.OK)
        assertThat(DomainMatch.check("login.example.com", "example.com").verdict).isEqualTo(Verdict.MISMATCH)
        // Not a suffix of the host: a domain that merely ends with the same letters is another site.
        assertThat(DomainMatch.check("example.com", "notexample.com").verdict).isEqualTo(Verdict.MISMATCH)
    }

    @Test
    fun `a public suffix is not a domain someone owns`() {
        assertThat(DomainMatch.check("victim.github.io", "attacker.github.io").verdict).isEqualTo(Verdict.MISMATCH)
    }

    @Test
    fun `the distance is measured on at most fifty characters of each side`() {
        val long = "a".repeat(60)
        assertThat(DomainMatch.distance(long + "x.com", long + "y.com")).isEqualTo(0)
        assertThat(DomainMatch.distance("paypal.com", "paypa1.com")).isEqualTo(1)
        assertThat(DomainMatch.distance("", "abc")).isEqualTo(3)
        assertThat(DomainMatch.distance("abc", "")).isEqualTo(3)
    }

    /** `contentOrNull` is null for a JSON null, which is how "no browser was read" is written. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
