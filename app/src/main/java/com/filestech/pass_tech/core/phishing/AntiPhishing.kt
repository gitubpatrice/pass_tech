package com.filestech.pass_tech.core.phishing

import com.filestech.pass_tech.core.settings.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the browser in front is on the site an entry belongs to, before its password is copied.
 *
 * **Off means off.** 2.7.1's switch wrote a preference and nothing else: the accessibility service
 * stayed granted and kept reading the address bar of every browser, for an owner who had just been
 * told the protection was off. Here the switch carries the component with it — turning it off makes
 * the service disappear from Settings › Accessibility, which withdraws the grant with it. Turning it
 * back on therefore asks for that grant again, and the consent dialog says so.
 *
 * That same withdrawal is what the panic mode needs: a phone whose launcher says "Calculator" while
 * Settings › Accessibility says "Pass Tech" is not disguised.
 */
@Singleton
class AntiPhishing @Inject constructor(
    private val preferences: AppPreferences,
    private val component: PhishingComponent,
    private val domain: ActiveDomain,
) {

    /** What the owner asked for. Says nothing about whether the system granted it: see [granted]. */
    val enabled: Flow<Boolean> = preferences.antiPhishing

    /**
     * Lists the service so the owner can grant it, then remembers the choice. The preference is only
     * written once the component is listed: a switch that moves while nothing under it changed would
     * promise a protection that cannot run.
     */
    suspend fun turnOn(): Boolean {
        if (!component.setListed(true)) return false
        preferences.setAntiPhishing(true)
        return true
    }

    /**
     * The preference first, so that a failure to unlist leaves the app refusing to trust the service
     * rather than trusting one it no longer controls; then the reading in memory; then the component.
     */
    suspend fun turnOff(): Boolean {
        preferences.setAntiPhishing(false)
        domain.clear()
        return component.setListed(false)
    }

    /** `null` when the system did not answer, which is neither granted nor refused. */
    fun granted(): Boolean? = component.granted()

    fun openSystemSettings(): Boolean = component.openSystemSettings()

    /**
     * The verdict for an entry whose URL is [url]. Switched off, it is always [DomainMatch.Verdict.OK]:
     * with no service running there is nothing to compare against, and warning on every copy would
     * teach the owner to ignore the warning.
     */
    suspend fun check(url: String): DomainMatch.Check =
        if (!enabled.first()) DomainMatch.Check(DomainMatch.Verdict.OK) else DomainMatch.check(url, domain.current())

    /** The vault closing, or the panic mode: the last site read has no reason to outlive either. */
    fun forget() = domain.clear()
}
