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
        // The answer is padded with decoy suffixes, so its LENGTH says nothing either. The
        // `User-Agent` is [HttpFetch]'s, set once for every call this app makes.
        headers = mapOf("Add-Padding" to "true"),
        budgetMillis = BUDGET_MILLIS,
    )

    private companion object {
        const val HOST = "api.pwnedpasswords.com"
        const val RANGE = "https://$HOST/range/"
        const val BUDGET_MILLIS = 8_000L
    }
}
