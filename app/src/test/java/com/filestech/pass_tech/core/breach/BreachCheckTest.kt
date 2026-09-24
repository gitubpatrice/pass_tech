package com.filestech.pass_tech.core.breach

import com.filestech.pass_tech.core.crypto.Sha1
import com.filestech.pass_tech.testing.FakeLauncherDisguise
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The breach check: what leaves, what comes back, and the two cases where nothing is sent at all. */
class BreachCheckTest {

    private val disguise = FakeLauncherDisguise()
    private val api = FakeBreachApi()

    /** The service's answers, written the way it writes them: `SUFFIX:count`, padded with zeros. */
    private class FakeBreachApi : BreachApi {
        val asked = mutableListOf<String>()
        val found = mutableSetOf<String>()
        val padded = mutableSetOf<String>()
        var failing = setOf<String>()
        var failAll = false

        override suspend fun range(prefix: String): String? {
            synchronized(asked) { asked += prefix }
            if (failAll || prefix in failing) return null
            val lines = found.filter { Sha1.prefixOf(it) == prefix }.map { "${Sha1.suffixOf(it)}:42" }
            // Padding: the service answers about suffixes it was not asked about, with a count of
            // zero. THE ONE IT WAS ASKED ABOUT can be among them, and that means "not found".
            val zeros = padded.filter { Sha1.prefixOf(it) == prefix }.map { "${Sha1.suffixOf(it)}:0" }
            return (lines + zeros + listOf("0000000000000000000000000000000000000:0")).joinToString("\r\n")
        }

        fun knows(password: String) {
            found += Sha1.hex(password)
        }

        fun pads(password: String) {
            padded += Sha1.hex(password)
        }
    }

    @Test
    fun `nothing is sent while the launcher is disguised`() = runTest {
        disguise.set(true)
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("azerty"))
        assertThat(outcome).isEqualTo(BreachCheck.Outcome.Disguised)
        // The very existence of the traffic would contradict the calculator on the home screen.
        assertThat(api.asked).isEmpty()
    }

    @Test
    fun `a system that will not say whether it is disguised counts as disguised`() = runTest {
        disguise.answers = false
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("azerty"))
        assertThat(outcome).isEqualTo(BreachCheck.Outcome.Disguised)
        assertThat(api.asked).isEmpty()
    }

    @Test
    fun `only the first five characters of a hash are asked about`() = runTest {
        BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("azerty"))
        assertThat(api.asked).containsExactly(Sha1.prefixOf(Sha1.hex("azerty")))
        assertThat(api.asked.single()).hasLength(Sha1.PREFIX_LENGTH)
    }

    @Test
    fun `one request per distinct password`() = runTest {
        BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("a1", "a1", "b2", "", "b2"))
        assertThat(api.asked).hasSize(2)
    }

    @Test
    fun `a password in the list comes back, one that is not does not`() = runTest {
        api.knows("azerty")
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler))
            .check(listOf("azerty", "Fk3!mzPqrs7Lw")) as BreachCheck.Outcome.Done
        assertThat(outcome.breached).containsExactly(Sha1.hex("azerty"))
        assertThat(outcome.checked).containsExactly(Sha1.hex("azerty"), Sha1.hex("Fk3!mzPqrs7Lw"))
        assertThat(outcome.failed).isEqualTo(0)
    }

    @Test
    fun `a suffix answered with a count of zero is padding, not a breach`() = runTest {
        // Its OWN suffix comes back, with a count of zero. Reading the line without reading the
        // count would report it as breached — and the first version of this test could not tell,
        // because its padding line carried a suffix that could never match (2026-09-23).
        api.pads("Fk3!mzPqrs7Lw")
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler))
            .check(listOf("Fk3!mzPqrs7Lw")) as BreachCheck.Outcome.Done
        assertThat(outcome.breached).isEmpty()
        assertThat(outcome.checked).hasSize(1)
    }

    @Test
    fun `one request that fails does not throw away the answers that came back`() = runTest {
        api.knows("azerty")
        api.failing = setOf(Sha1.prefixOf(Sha1.hex("Fk3!mzPqrs7Lw")))
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler))
            .check(listOf("azerty", "Fk3!mzPqrs7Lw")) as BreachCheck.Outcome.Done
        // 2.7.1 dropped the whole run on the first failure: an owner with fifty passwords and one
        // flaky request learned nothing about the other forty-nine, breached ones included.
        assertThat(outcome.breached).containsExactly(Sha1.hex("azerty"))
        assertThat(outcome.failed).isEqualTo(1)
        // The one that failed is NOT in `checked`, so the score stays partial and says so.
        assertThat(outcome.checked).containsExactly(Sha1.hex("azerty"))
    }

    @Test
    fun `not one answer is told apart from none breached`() = runTest {
        api.failAll = true
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("azerty"))
        assertThat(outcome).isEqualTo(BreachCheck.Outcome.NoNetwork)
    }

    @Test
    fun `nothing to check is not a network failure`() = runTest {
        val outcome = BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("", "  ").filter { it.isEmpty() })
        assertThat(outcome).isEqualTo(BreachCheck.Outcome.Done(emptySet(), emptySet(), failed = 0))
        assertThat(api.asked).isEmpty()
    }

    @Test
    fun `progress is reported up to the number of distinct passwords`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        BreachCheck(api, disguise, StandardTestDispatcher(testScheduler)).check(listOf("a1", "b2", "a1")) { done, total ->
            seen += done to total
        }
        assertThat(seen.map { it.second }.distinct()).containsExactly(2)
        assertThat(seen.map { it.first }).containsExactly(1, 2)
    }

    @Test
    fun `the hash is the one the protocol is defined on`() {
        // The published vector of the service's own documentation.
        assertThat(Sha1.hex("password")).isEqualTo("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8")
        assertThat(Sha1.prefixOf(Sha1.hex("password"))).isEqualTo("5BAA6")
        assertThat(Sha1.suffixOf(Sha1.hex("password"))).isEqualTo("1E4C9B93F3F0682250B6CF8331B7EE68FD8")
    }
}
