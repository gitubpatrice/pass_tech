package com.filestech.pass_tech.core.update

import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FakeLauncherDisguise
import com.filestech.pass_tech.testing.InMemoryPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What is asked, when, and what is believed of the answer. */
class UpdateCheckTest {

    private val disguise = FakeLauncherDisguise()
    private val clock = FakeClock()
    private val preferences = AppPreferences(InMemoryPreferences())
    private val api = FakeReleaseApi()

    private class FakeReleaseApi(var body: String? = release("3.1.0")) : ReleaseApi {
        var calls = 0
            private set

        override suspend fun latest(): String? {
            calls++
            return body
        }
    }

    private fun check(installed: String = "3.0.0") = UpdateCheck(api, disguise, preferences, clock, installed)

    @Test
    fun `a newer version is reported`() = runTest {
        val release = check().newerRelease()
        assertThat(release?.version).isEqualTo("3.1.0")
    }

    @Test
    fun `the same version, or an older one, is not`() = runTest {
        assertThat(check(installed = "3.1.0").newerRelease()).isNull()
        api.body = release("2.9.9")
        assertThat(check(installed = "3.0.0").newerRelease()).isNull()
    }

    @Test
    fun `a version this app cannot read is never offered`() = runTest {
        // A debug build carries "-debug", so it is offered nothing — the safe side of the same rule.
        assertThat(check(installed = "3.0.0-debug").newerRelease()).isNull()
        api.body = release("3.1")
        assertThat(check().newerRelease()).isNull()
        api.body = release("3.1.0-rc1")
        assertThat(check().newerRelease()).isNull()
    }

    @Test
    fun `nothing is asked while the launcher is disguised`() = runTest {
        disguise.set(true)
        assertThat(check().newerRelease()).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `a system that will not say whether it is disguised counts as disguised`() = runTest {
        disguise.answers = false
        assertThat(check().newerRelease()).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `not asked twice within the delay, and asked again after it`() = runTest {
        check().newerRelease()
        assertThat(api.calls).isEqualTo(1)
        check().newerRelease()
        assertThat(api.calls).isEqualTo(1)

        clock.wall += UpdateCheck.BETWEEN_CHECKS_MILLIS
        check().newerRelease()
        assertThat(api.calls).isEqualTo(2)
    }

    @Test
    fun `a check asked for by hand skips the delay`() = runTest {
        check().newerRelease()
        check().newerRelease(force = true)
        assertThat(api.calls).isEqualTo(2)
    }

    @Test
    fun `a clock moved backwards does not hold the check off for ever`() = runTest {
        check().newerRelease()
        clock.wall -= 365L * 24 * 60 * 60 * 1000
        check().newerRelease()
        assertThat(api.calls).isEqualTo(2)
    }

    @Test
    fun `an answer that was not understood does not count as a check`() = runTest {
        // A captive portal answers "200 OK" with a page. 2.7.1 marked its cache on the status code,
        // so nothing was asked for twelve hours after the connection came back.
        api.body = "<html>Sign in to the hotel Wi-Fi</html>"
        assertThat(check().newerRelease()).isNull()
        assertThat(preferences.updateLastCheckMillis.first()).isEqualTo(0L)

        api.body = release("3.1.0")
        assertThat(check().newerRelease()?.version).isEqualTo("3.1.0")
        assertThat(preferences.updateLastCheckMillis.first()).isEqualTo(clock.wall)
    }

    @Test
    fun `no answer at all does not count as a check either`() = runTest {
        api.body = null
        assertThat(check().newerRelease()).isNull()
        assertThat(preferences.updateLastCheckMillis.first()).isEqualTo(0L)
    }

    private companion object {
        fun release(tag: String, assets: String = "[]", notes: String = "Fixes.") =
            """{"tag_name":"v$tag","body":"$notes","assets":$assets}"""
    }
}
