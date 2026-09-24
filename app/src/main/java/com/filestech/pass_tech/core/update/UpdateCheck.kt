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

    /** The `User-Agent` is [HttpFetch]'s, constant for every install: see why it is set there. */
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
     * What came of a check. The owner who presses a button is owed the difference between "I asked
     * and you are up to date" and "I never asked": 2.7.1's About screen answered "you already have
     * the latest version" to both, so a phone with no connection was told its version was current.
     */
    sealed interface Outcome {
        /** A version this app is willing to describe, and newer than the one running. */
        data class Newer(val release: Release) : Outcome

        /** Asked, answered, understood: nothing newer. */
        data object UpToDate : Outcome

        /** Nothing was asked: the launcher is disguised, so no traffic may exist (fail-closed). */
        data object Disguised : Outcome

        /** Nothing was asked: the last check is too recent. Never returned to a check asked for by hand. */
        data object TooSoon : Outcome

        /**
         * Nothing was asked: **this build's own version is not one that can be compared.** A debug
         * build carries `-debug`, and a `3.1.0-rc1` would be no better. Saying "up to date" here
         * would be the same lie as saying it to a phone with no connection, and it is the lie the
         * code fell into on its own: an unreadable version is never newer, so the comparison simply
         * answered no, and the screen turned that no into a tick.
         */
        data object UnreadableVersion : Outcome

        /** Asked, and no answer this app could read: no network, a captive portal, a shape it refuses. */
        data object Unreachable : Outcome
    }

    /**
     * Asks, or says why it did not. [force] skips the delay, for a check the owner asked for by hand.
     */
    suspend fun check(force: Boolean = false): Outcome {
        if (disguise.disguised() != false) return Outcome.Disguised
        // Before the network: whatever GitHub answers cannot be compared with a version nobody can read.
        if (!Semver.readable(installedVersion)) return Outcome.UnreadableVersion
        if (!force && !dueAgain()) return Outcome.TooSoon
        val now = clock.wallMillis()
        val release = api.latest()?.let(ReleaseJson::parse) ?: return Outcome.Unreachable
        // Marked only once the answer has been UNDERSTOOD. Marking it on the status code alone means
        // a captive portal's "200 OK" page counts as a check, and nothing is asked for twelve hours
        // after the connection comes back (2.7.1 fixed exactly this).
        preferences.setUpdateLastCheckMillis(now)
        return if (Semver.isNewer(release.version, installedVersion)) Outcome.Newer(release) else Outcome.UpToDate
    }

    /**
     * The newer release, or `null`: nothing newer, nothing asked (too soon, or disguised), or nothing
     * understood. For the check nobody asked for, which has only one thing to say.
     */
    suspend fun newerRelease(force: Boolean = false): Release? = (check(force) as? Outcome.Newer)?.release

    /**
     * Whether enough time has passed. A clock moved backwards would otherwise hold the check off for
     * as long as the owner's date is wrong, so the DISTANCE is what counts, in either direction.
     */
    private suspend fun dueAgain(): Boolean =
        abs(clock.wallMillis() - preferences.updateLastCheckMillis.first()) >= BETWEEN_CHECKS_MILLIS

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
