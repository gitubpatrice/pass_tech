package com.filestech.pass_tech.core.breach

import java.net.HttpURLConnection
import java.net.URL
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
class HibpApi @Inject constructor() : BreachApi {

    override suspend fun range(prefix: String): String? =
        try {
            val connection = open(RANGE + prefix)
            try {
                read(connection)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            // The answer is padded with decoy suffixes, so its LENGTH says nothing either.
            setRequestProperty("Add-Padding", "true")
        }

    private fun read(connection: HttpURLConnection): String? =
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }

    private companion object {
        const val RANGE = "https://api.pwnedpasswords.com/range/"
        const val TIMEOUT_MILLIS = 8_000

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
