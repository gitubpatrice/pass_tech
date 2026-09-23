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

    /**
     * The real sign-in pages of the accounts people keep here, every one of them a dead end before an
     * entry could declare more than one domain — and none of them within [DomainMatch.TYPO_DISTANCE],
     * so there was not even a "copy anyway" to fall back on.
     */
    @Test
    fun `a domain the entry declares is the entry's, whatever the distance`() {
        val federated = listOf(
            "office.com" to "login.microsoftonline.com",
            "icloud.com" to "appleid.apple.com",
            "steampowered.com" to "steamcommunity.com",
            "sosh.fr" to "orange.fr",
            "twitter.com" to "x.com",
            "boursorama.com" to "boursobank.com",
            "amazon.fr" to "amazon.com",
        )
        federated.forEach { (entry, signIn) ->
            assertThat(DomainMatch.check(entry, signIn).verdict).isEqualTo(Verdict.MISMATCH)
            assertThat(DomainMatch.check(entry, signIn, listOf(signIn)).verdict).isEqualTo(Verdict.OK)
        }
        // A name under a declared domain is covered as one under the URL is.
        assertThat(DomainMatch.check("office.com", "eu.login.microsoftonline.com", listOf("login.microsoftonline.com")).verdict)
            .isEqualTo(Verdict.OK)
        // And the domain reported is the one that matched, not the entry's first.
        val ok = DomainMatch.check("office.com", "login.microsoftonline.com", listOf("login.microsoftonline.com"))
        assertThat(ok.expected).isEqualTo("login.microsoftonline.com")
    }

    /**
     * Declaring a domain adds one, it does not lower the bar. The distance is untouched and the top
     * level is still compared, so the two shapes an attack takes stay refused: the look-alike name and
     * the same name under another extension.
     */
    @Test
    fun `declaring other domains refuses no less than before`() {
        val declared = listOf("appleid.apple.com", "icloud.com")
        assertThat(DomainMatch.check("apple.com", "paypal.tk", declared).verdict).isEqualTo(Verdict.MISMATCH)
        // The same name under another extension is not the same site, whatever else is declared.
        assertThat(DomainMatch.check("paypal.com", "paypal.tk", listOf("paypal.co.uk")).verdict).isEqualTo(Verdict.MISMATCH)
        // A declared domain used as a PREFIX by someone else is not that domain.
        assertThat(DomainMatch.check("apple.com", "appleid.apple.com.evil.net", declared).verdict).isEqualTo(Verdict.MISMATCH)
        // The closest declared domain is what the dialog holds up, and it is still a warning.
        val typo = DomainMatch.check("apple.com", "icloud.co", declared)
        assertThat(typo.verdict).isEqualTo(Verdict.TYPOSQUATTING)
        assertThat(typo.expected).isEqualTo("icloud.com")
        assertThat(typo.distance).isEqualTo(1)
        // A declared domain that is not a host at all is dropped, and changes no verdict.
        assertThat(DomainMatch.check("apple.com", "evil.com", listOf("", "   ", "not a host")).verdict)
            .isEqualTo(Verdict.MISMATCH)
    }

    @Test
    fun `an entry with no URL is still checked against the domains it declares`() {
        assertThat(DomainMatch.check("", "evil.com", listOf("mabanque.fr")).verdict).isEqualTo(Verdict.MISMATCH)
        assertThat(DomainMatch.check("", "mabanque.fr", listOf("mabanque.fr")).verdict).isEqualTo(Verdict.OK)
        // Nothing to compare against on either side keeps its 2.7.1 answer: the copy goes ahead.
        assertThat(DomainMatch.check("", "evil.com").verdict).isEqualTo(Verdict.OK)
        assertThat(DomainMatch.check("", "evil.com", listOf("")).verdict).isEqualTo(Verdict.OK)
        // No browser read, and the entry's own URL is what "expected" names.
        assertThat(DomainMatch.check("office.com", null, listOf("login.microsoftonline.com")).expected).isEqualTo("office.com")
    }

    /**
     * 2.7.1 cut both sides at fifty characters, and this test used to assert that cut as intended:
     * `distance(long + "x.com", long + "y.com") == 0`. Two different hosts reading as identical is
     * not a saving, it is the wrong answer — and the owner was shown "Distance: 0" under a "copy
     * anyway" button, which reads as "the same site". The cut is now the longest a DNS name can be.
     */
    @Test
    fun `two hosts that differ only past the fiftieth character are not the same host`() {
        val long = "a".repeat(60)
        assertThat(DomainMatch.distance(long + "x.com", long + "y.com")).isEqualTo(1)
        assertThat(DomainMatch.check(long + "x.com", long + "y.com").verdict).isEqualTo(Verdict.TYPOSQUATTING)
        assertThat(DomainMatch.check(long + "x.com", long + "y.com").distance).isEqualTo(1)
        assertThat(DomainMatch.distance("paypal.com", "paypa1.com")).isEqualTo(1)
        assertThat(DomainMatch.distance("", "abc")).isEqualTo(3)
        assertThat(DomainMatch.distance("abc", "")).isEqualTo(3)
    }

    /**
     * An internationalised top level arrives punycoded — digits and a hyphen — and a letters-only
     * pattern refused it. What followed was not "could not check": the service keeps the last
     * readable host for fifteen seconds, so the page read just before answered in its place.
     */
    @Test
    fun `an internationalised top level is a top level`() {
        assertThat(DomainMatch.fromAddressBar("сбербанк.рф")).isEqualTo("xn--80abap1arsf.xn--p1ai")
        assertThat(DomainMatch.fromAddressBar("https://shop.中国/panier")).isEqualTo("shop.xn--fiqs8s")
        // Measured, not written by hand: guessing a punycode value is how a vector was wrong once.
        assertThat(DomainMatch.fromAddressBar("청와대.한국")).isEqualTo("xn--vk1b187a8ue.xn--3e0b707e")
        // And an entry noting the same address still matches it, in either form.
        assertThat(DomainMatch.check("сбербанк.рф", "xn--80abap1arsf.xn--p1ai").verdict).isEqualTo(Verdict.OK)
        assertThat(DomainMatch.check("сбербанк.рф", "сбербанк.рф").verdict).isEqualTo(Verdict.OK)
        // What is still not a host stays not a host: a page title, a search, an IP address.
        assertThat(DomainMatch.fromAddressBar("192.168.1.1")).isNull()
        assertThat(DomainMatch.fromAddressBar("Ma Banque — connexion")).isNull()
    }

    /** `contentOrNull` is null for a JSON null, which is how "no browser was read" is written. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
