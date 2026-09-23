package com.filestech.pass_tech.core.breach

import com.filestech.pass_tech.core.crypto.Sha1
import com.filestech.pass_tech.core.di.IoDispatcher
import com.filestech.pass_tech.core.panic.LauncherDisguise
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Putting the vault's passwords against the list of public breaches, without any of them leaving the
 * phone. Each distinct password is asked about once; the answers come back as the hashes that were
 * found, so nothing here holds a second copy of a password.
 *
 * **It refuses to run while the launcher is disguised.** 2.7.1 held the update check back for that
 * reason — "it is the very existence of the traffic that contradicts the disguise" — and left this
 * one, which reaches the same kind of service over the same network, with no guard at all. Same app,
 * same danger, one path covered and its twin not: the shape of defect this portfolio keeps paying
 * for. Unknown counts as disguised, as it does there.
 */
@Singleton
class BreachCheck @Inject constructor(
    private val api: BreachApi,
    private val disguise: LauncherDisguise,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    sealed interface Outcome {
        /**
         * [breached] and [checked] are hashes. [failed] counts the passwords whose own request did
         * not come back: they are simply not in [checked], so the score stays partial and says so.
         *
         * 2.7.1 threw the whole run away on the first failure. An owner with fifty passwords and one
         * flaky request learned nothing about the other forty-nine, breached ones included.
         */
        data class Done(val breached: Set<String>, val checked: Set<String>, val failed: Int) : Outcome

        /** Nothing was sent: see the class comment. */
        data object Disguised : Outcome

        /** Not one request came back. Telling this apart from "none breached" is the whole point. */
        data object NoNetwork : Outcome
    }

    /**
     * [onProgress] is called with how many distinct passwords have been answered for, out of how
     * many there are.
     */
    suspend fun check(passwords: Collection<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        if (disguise.disguised() != false) return Outcome.Disguised
        val distinct = passwords.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return Outcome.Done(emptySet(), emptySet(), failed = 0)

        val breached = mutableSetOf<String>()
        val checked = mutableSetOf<String>()
        var done = 0
        withContext(io) {
            coroutineScope {
                distinct.chunked(chunk(distinct.size)).map { group ->
                    async {
                        for (password in group) {
                            val hash = Sha1.hex(password)
                            val answer = api.range(Sha1.prefixOf(hash))
                            synchronized(checked) {
                                if (answer != null) {
                                    checked += hash
                                    if (found(answer, Sha1.suffixOf(hash))) breached += hash
                                }
                                done++
                                onProgress(done, distinct.size)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val failed = distinct.size - checked.size
        return if (checked.isEmpty()) Outcome.NoNetwork else Outcome.Done(breached, checked, failed)
    }

    /** One line per suffix, `SUFFIX:count`. A count of zero is padding, and means nothing was found. */
    private fun found(answer: String, suffix: String): Boolean =
        answer.lineSequence().any { line ->
            val parts = line.trim().split(':')
            parts.size == 2 && parts[0].equals(suffix, ignoreCase = true) && (parts[1].trim().toLongOrNull() ?: 0L) > 0L
        }

    private fun chunk(total: Int): Int = ((total + CONCURRENCY - 1) / CONCURRENCY).coerceAtLeast(1)

    private companion object {
        /** 2.7.1's figure, kept: enough to turn thirty seconds into six, gentle enough for the service. */
        const val CONCURRENCY = 6
    }
}
