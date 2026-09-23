package com.filestech.pass_tech.core.update

import com.filestech.pass_tech.core.net.HttpFetch
import com.filestech.pass_tech.core.panic.LauncherDisguise
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.state.Clock
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.abs

/** What GitHub answered about the latest release, or `null` if it could not be asked. */
interface ReleaseApi {
    suspend fun latest(): String?
}

@Singleton
class GithubReleaseApi @Inject constructor(private val http: HttpFetch) : ReleaseApi {

    override suspend fun latest(): String? = http.get(
        url = UpdateCheck.LATEST,
        allowedHosts = setOf(UpdateCheck.HOST),
        headers = mapOf("Accept" to "application/vnd.github+json"),
        maxRedirects = UpdateCheck.MAX_REDIRECTS,
    )
}

/**
 * Whether a newer version has been published, asked of GitHub at most twice a day.
 *
 * **And said out loud.** 2.7.1 ran this at every cold start and threw the answer away — the call
 * site is `await appUpdateService.checkForUpdate();`, with nothing on the left of it. So the app
 * reached the network before any unlock, used up its twelve-hour slot, and told nobody anything. A
 * check that warns no one is only a network trace.
 *
 * Two things follow from that:
 *
 * - it runs **once the vault is open**, not at start-up. Nothing at all leaves the phone until the
 *   owner has shown they are the owner, and the answer appears where they can act on it;
 * - it refuses to run while the launcher is disguised, fail-closed, as 2.7.1 already did here: it is
 *   the very existence of the traffic that contradicts a calculator.
 */
@Singleton
class UpdateCheck @Inject constructor(
    private val api: ReleaseApi,
    private val disguise: LauncherDisguise,
    private val preferences: AppPreferences,
    private val clock: Clock,
    @Named(INSTALLED_VERSION) private val installedVersion: String,
) {

    /**
     * The newer release, or `null`: nothing newer, nothing asked (too soon, or disguised), or nothing
     * understood. [force] skips the delay, for a check the owner asked for by hand.
     */
    suspend fun newerRelease(force: Boolean = false): Release? {
        if (!mayAsk(force)) return null
        val now = clock.wallMillis()
        val release = api.latest()?.let(ReleaseJson::parse) ?: return null
        // Marked only once the answer has been UNDERSTOOD. Marking it on the status code alone means
        // a captive portal's "200 OK" page counts as a check, and nothing is asked for twelve hours
        // after the connection comes back (2.7.1 fixed exactly this).
        preferences.setUpdateLastCheckMillis(now)
        return release.takeIf { Semver.isNewer(it.version, installedVersion) }
    }

    /**
     * Disguised, or asked too recently. A clock moved backwards would otherwise hold the check off
     * for as long as the owner's date is wrong, so the DISTANCE is what counts, in either direction.
     */
    private suspend fun mayAsk(force: Boolean): Boolean {
        if (disguise.disguised() != false) return false
        if (force) return true
        val since = abs(clock.wallMillis() - preferences.updateLastCheckMillis.first())
        return since >= BETWEEN_CHECKS_MILLIS
    }

    companion object {
        /** The qualifier of the version this build carries, so that no second copy of it exists. */
        const val INSTALLED_VERSION = "installedVersion"

        const val HOST = "api.github.com"
        const val LATEST = "https://$HOST/repos/gitubpatrice/pass_tech/releases/latest"

        /** 2.7.1's delay, kept: the anonymous API allows sixty calls an hour, shared with everything else. */
        const val BETWEEN_CHECKS_MILLIS = 12L * 60 * 60 * 1000

        const val MAX_REDIRECTS = 5
    }
}
