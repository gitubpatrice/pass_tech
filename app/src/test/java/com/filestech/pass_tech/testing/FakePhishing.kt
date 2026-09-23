package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.phishing.ActiveDomain
import com.filestech.pass_tech.core.phishing.AntiPhishing
import com.filestech.pass_tech.core.phishing.DomainSnapshot
import com.filestech.pass_tech.core.phishing.PhishingComponent
import com.filestech.pass_tech.core.settings.AppPreferences

/** The accessibility service as the system would report it, with a switch for a system that refuses. */
class FakePhishingComponent(
    var listed: Boolean = false,
    var granted: Boolean = false,
) : PhishingComponent {

    /** When false, every call fails the way a package manager that refuses does. */
    var answers = true

    var settingsOpened = 0
        private set

    override fun listed(): Boolean? = if (answers) listed else null

    override fun setListed(listed: Boolean): Boolean {
        if (!answers) return false
        this.listed = listed
        // The system drops the grant along with the component, which is the point of unlisting it.
        if (!listed) granted = false
        return true
    }

    override fun granted(): Boolean? = if (answers) granted else null

    override fun openSystemSettings(): Boolean {
        settingsOpened++
        return answers
    }
}

/** A domain the test sets by hand, with no clock and no window: the window has its own tests. */
class FixedDomain(var host: String? = null) : ActiveDomain {

    var clears = 0
        private set

    override fun current(): String? = host

    override fun clear() {
        clears++
        host = null
    }
}

fun antiPhishing(
    preferences: AppPreferences,
    component: PhishingComponent = FakePhishingComponent(),
    domain: ActiveDomain = FixedDomain(),
) = AntiPhishing(preferences, component, domain)

fun snapshot(clock: FakeClock) = DomainSnapshot(clock)
