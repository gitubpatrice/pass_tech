package com.filestech.pass_tech.core.net

import com.filestech.pass_tech.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way this app fetches anything, for the two things it is allowed to fetch (PRIVACY §6).
 *
 * Everything here is a refusal:
 *
 * - **the host and the scheme are checked at every hop.** `HttpURLConnection` follows redirects by
 *   itself and revalidates nothing, so a `302` to another host — or to plain `http` — would feed the
 *   app a body from wherever. Redirects are followed by hand, or refused;
 * - **the answer is capped.** Reading a body with no bound is how a proxy, a captive portal or an
 *   authority installed on the phone brings the app down with a few hundred megabytes. The announced
 *   length is refused before reading, and the count is checked BEFORE each chunk is kept;
 * - **the whole call has a deadline**, not just the connection. A server that answers "200" at once
 *   and then sends nothing would otherwise be waited on for ever.
 *
 * All of it is 2.7.1's, which arrived at the same rules one audit at a time. What is new is that its
 * breach check did not go through any of this.
 */
@Singleton
class HttpFetch @Inject constructor(@IoDispatcher private val io: CoroutineDispatcher) {

    /**
     * The body, or `null` for anything at all going wrong: refused host, redirect where none is
     * allowed, status other than 200, body too large, deadline passed, no network.
     */
    @Suppress("ReturnCount")
    suspend fun get(
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_BYTES,
        maxRedirects: Int = 0,
        budgetMillis: Long = BUDGET_MILLIS,
    ): String? = withContext(io) {
        val deadline = System.nanoTime() + budgetMillis * NANOS_PER_MILLI
        var target = url
        repeat(maxRedirects + 1) {
            if (System.nanoTime() > deadline) return@withContext null
            val uri = HttpTarget.accept(target, allowedHosts) ?: return@withContext null
            val connection = runCatching { open(uri.toURL(), headers, deadline) }.getOrNull() ?: return@withContext null
            try {
                val code = runCatching { connection.responseCode }.getOrNull() ?: return@withContext null
                if (code in REDIRECTS) {
                    val next = HttpTarget.follow(uri, connection.getHeaderField("Location"), allowedHosts)
                    target = next?.toString() ?: return@withContext null
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return@withContext null
                if (connection.contentLengthLong > maxBytes) return@withContext null
                return@withContext runCatching { connection.inputStream.use { read(it, maxBytes, deadline) } }.getOrNull()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        null
    }

    private fun open(url: URL, headers: Map<String, String>, deadline: Long): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            val left = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            connectTimeout = minOf(left, STEP_TIMEOUT_MILLIS)
            readTimeout = minOf(left, STEP_TIMEOUT_MILLIS)
            // Before the caller's headers, so a caller may still override it — and so that no caller
            // has to remember. Left unset, Android fills in its own:
            // `Dalvik/… (Linux; U; Android <release>; <model> Build/<id>)`, which hands the phone's
            // model and build to a request that already names this project. The breach check set a
            // constant agent for that reason and said so; the update check simply never got the same
            // treatment, and the privacy policy promises "nothing of yours is sent" for both (audit
            // of 2026-09-24). It belongs here, on the one path out, where a third call cannot miss it.
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun read(stream: InputStream, maxBytes: Int, deadline: Long): String? {
        val bytes = ByteArray(maxBytes)
        var filled = 0
        val chunk = ByteArray(CHUNK)
        while (true) {
            if (System.nanoTime() > deadline) return null
            val read = stream.read(chunk)
            if (read < 0) break
            // Checked BEFORE the chunk is kept: after, a single 600 KiB chunk is allocated whole
            // before being refused.
            if (filled + read > maxBytes) return null
            chunk.copyInto(bytes, filled, 0, read)
            filled += read
        }
        return String(bytes, 0, filled, Charsets.UTF_8)
    }

    private companion object {
        /**
         * The same plain string for every install, every request, every session.
         *
         * 2.7.1 drew one at random out of four at start-up and kept it for the session, which is
         * worse than none: an observer seeing the same agent on several requests could tie one
         * install together, and `IP × agent` was close to unique. A constant, unremarkable agent
         * looks like any other crawler.
         */
        const val USER_AGENT = "Mozilla/5.0 (compatible)"

        /** A release, or a range of hashes, is a few kilobytes. */
        const val MAX_BYTES = 512 * 1024
        const val BUDGET_MILLIS = 20_000L
        const val STEP_TIMEOUT_MILLIS = 10_000
        const val CHUNK = 8 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
        val REDIRECTS = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            TEMPORARY_REDIRECT,
            PERMANENT_REDIRECT,
        )
        const val TEMPORARY_REDIRECT = 307
        const val PERMANENT_REDIRECT = 308
    }
}

/**
 * Which addresses this app is willing to open, and which redirects it is willing to follow. Kept
 * apart from the fetching itself because these are the rules worth checking one by one, and a test
 * cannot reach them through a real connection: every allowed address is `https`, and a test server
 * on this machine is not.
 */
object HttpTarget {

    /** The address, or `null` when it is not `https` on one of [allowedHosts]. */
    fun accept(url: String, allowedHosts: Set<String>): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "https" && it.host in allowedHosts }
    }

    /**
     * Where a redirect leads, or `null` to refuse it.
     *
     * An empty `Location` resolves to the address we are already on, which is five identical
     * requests before giving up. And the destination is held to the same rule as the first address:
     * `HttpURLConnection` follows redirects on its own and revalidates nothing, so a `302` to
     * another host — or to plain `http` — would otherwise feed the app a body from anywhere.
     */
    fun follow(current: URI, location: String?, allowedHosts: Set<String>): URI? {
        if (location.isNullOrBlank()) return null
        val next = runCatching { current.resolve(location) }.getOrNull() ?: return null
        return accept(next.toString(), allowedHosts)
    }
}
