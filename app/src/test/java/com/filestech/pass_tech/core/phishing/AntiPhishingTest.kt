package com.filestech.pass_tech.core.phishing

import com.filestech.pass_tech.core.phishing.DomainMatch.Verdict
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.testing.FakeClock
import com.filestech.pass_tech.testing.FakePhishingComponent
import com.filestech.pass_tech.testing.FixedDomain
import com.filestech.pass_tech.testing.InMemoryPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The switch and what it carries with it. The point of this class is that "off" is not a preference
 * an owner has to trust: it withdraws the accessibility service, which is what was actually reading
 * their address bar.
 */
class AntiPhishingTest {

    private val component = FakePhishingComponent()
    private val domain = FixedDomain()
    private val preferences = AppPreferences(InMemoryPreferences())
    private val antiPhishing = AntiPhishing(preferences, component, domain)

    @Test
    fun `it ships off, and listed nowhere`() = runTest {
        assertThat(antiPhishing.enabled.first()).isFalse()
        assertThat(component.listed).isFalse()
    }

    @Test
    fun `turning it on lists the service, then remembers it`() = runTest {
        assertThat(antiPhishing.turnOn()).isTrue()
        assertThat(component.listed).isTrue()
        assertThat(antiPhishing.enabled.first()).isTrue()
    }

    @Test
    fun `a system that refuses to list it leaves the switch where it was`() = runTest {
        component.answers = false
        assertThat(antiPhishing.turnOn()).isFalse()
        assertThat(antiPhishing.enabled.first()).isFalse()
    }

    @Test
    fun `turning it off unlists the service, which is what takes the grant back`() = runTest {
        antiPhishing.turnOn()
        component.granted = true
        assertThat(antiPhishing.turnOff()).isTrue()
        assertThat(antiPhishing.enabled.first()).isFalse()
        assertThat(component.listed).isFalse()
        // 2.7.1 wrote the preference and stopped there, and the service went on reading every
        // address bar for an owner who had just been told the protection was off.
        assertThat(component.granted).isFalse()
    }

    @Test
    fun `turning it off forgets the site the browser was on`() = runTest {
        domain.host = "mabanque.fr"
        antiPhishing.turnOff()
        assertThat(domain.host).isNull()
    }

    @Test
    fun `off, nothing is compared`() = runTest {
        domain.host = "evil.com"
        assertThat(antiPhishing.check("mabanque.fr").verdict).isEqualTo(Verdict.OK)
    }

    @Test
    fun `on, the browser is compared`() = runTest {
        antiPhishing.turnOn()
        domain.host = "evil.com"
        assertThat(antiPhishing.check("mabanque.fr").verdict).isEqualTo(Verdict.MISMATCH)
        domain.host = "mabanque.fr"
        assertThat(antiPhishing.check("mabanque.fr").verdict).isEqualTo(Verdict.OK)
        domain.host = null
        assertThat(antiPhishing.check("mabanque.fr").verdict).isEqualTo(Verdict.UNKNOWN)
    }

    @Test
    fun `a system that does not answer is not a grant`() {
        component.granted = true
        assertThat(antiPhishing.granted()).isTrue()
        component.answers = false
        assertThat(antiPhishing.granted()).isNull()
    }
}

/** The reading itself: one host, and how long it is allowed to answer for. */
class DomainSnapshotTest {

    private val clock = FakeClock()
    private val snapshot = DomainSnapshot(clock)

    @Test
    fun `nothing read, nothing to answer`() {
        assertThat(snapshot.current()).isNull()
    }

    @Test
    fun `a reading answers until its window is over`() {
        snapshot.record("mabanque.fr")
        clock.advance(DomainSnapshot.FRESHNESS_MILLIS)
        assertThat(snapshot.current()).isEqualTo("mabanque.fr")
        clock.advance(1)
        // A tab the owner left fifteen seconds ago must not answer for the page they are on now.
        assertThat(snapshot.current()).isNull()
    }

    @Test
    fun `a new reading replaces the one before it`() {
        snapshot.record("mabanque.fr")
        clock.advance(10_000)
        snapshot.record("autre.fr")
        clock.advance(10_000)
        assertThat(snapshot.current()).isEqualTo("autre.fr")
    }

    @Test
    fun `it can be forgotten on the spot`() {
        snapshot.record("mabanque.fr")
        snapshot.clear()
        assertThat(snapshot.current()).isNull()
    }

    @Test
    fun `the window is measured on the clock nobody can move`() {
        snapshot.record("mabanque.fr")
        // Setting the date back a year does not give a stale reading its window again.
        clock.wall -= 365L * 24 * 3600 * 1000
        clock.elapsed += DomainSnapshot.FRESHNESS_MILLIS + 1
        assertThat(snapshot.current()).isNull()
    }
}
