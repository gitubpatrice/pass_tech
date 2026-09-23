package com.filestech.pass_tech.core.breach

import com.filestech.pass_tech.core.net.HttpFetch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one request this app makes about a password: "which hashes do you know that start with these
 * five characters?".
 *
 * The password never leaves the phone, and neither does its hash: only the first five characters of
 * it go out, and the answer holds several hundred suffixes among which ours may or may not be. This
 * is HaveIBeenPwned's k-anonymity range API.
 */
interface BreachApi {

    /** The body of the answer, or `null` when the question could not be asked or was not answered. */
    suspend fun range(prefix: String): String?
}

@Singleton
class HibpApi @Inject constructor(private val http: HttpFetch) : BreachApi {

    /**
     * No redirect is followed. The service does not redirect, and a `302` to another host would
     * otherwise be followed by `HttpURLConnection` on its own, with nothing revalidated.
     */
    override suspend fun range(prefix: String): String? = http.get(
        url = RANGE + prefix,
        allowedHosts = setOf(HOST),
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            // The answer is padded with decoy suffixes, so its LENGTH says nothing either.
            "Add-Padding" to "true",
        ),
        budgetMillis = BUDGET_MILLIS,
    )

    private companion object {
        const val HOST = "api.pwnedpasswords.com"
        const val RANGE = "https://$HOST/range/"
        const val BUDGET_MILLIS = 8_000L

        /**
         * The same plain string for every install, every request, every session.
         *
         * 2.7.1 first drew one at random out of four at start-up and kept it for the session, which
         * is worse than none: an observer seeing the same agent on several requests could tie one
         * install together, and `IP × agent` was close to unique. A constant, unremarkable agent
         * looks like any other crawler.
         */
        const val USER_AGENT = "Mozilla/5.0 (compatible)"
    }
}
