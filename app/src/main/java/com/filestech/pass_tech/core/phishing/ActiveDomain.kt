package com.filestech.pass_tech.core.phishing

import com.filestech.pass_tech.core.state.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The domain the browser is showing, as far as this app can tell. */
interface ActiveDomain {

    /** `null` when nothing was read, or when what was read is older than the freshness window. */
    fun current(): String?

    fun clear()
}

/**
 * One host and the instant it was read, replaced whole at every reading and never written down.
 *
 * The accessibility service writes here from its own thread while a screen reads from the main one,
 * so the two travel together in a single reference rather than in two fields that could be read one
 * old and one new.
 *
 * The age is measured on [Clock.elapsedMillis], which counts from boot: on the wall clock, anyone
 * able to set the date back could keep a reading valid long past its window.
 */
@Singleton
class DomainSnapshot @Inject constructor(private val clock: Clock) : ActiveDomain {

    private data class Reading(val host: String, val atMillis: Long)

    @Volatile
    private var reading: Reading? = null

    fun record(host: String) {
        reading = Reading(host, clock.elapsedMillis())
    }

    override fun current(): String? {
        val seen = reading ?: return null
        return if (clock.elapsedMillis() - seen.atMillis > FRESHNESS_MILLIS) null else seen.host
    }

    override fun clear() {
        reading = null
    }

    companion object {
        /**
         * 2.7.1's window, kept. Wider, and a domain left behind by a tab the owner has since closed
         * would answer for the page they are actually on.
         */
        const val FRESHNESS_MILLIS = 15_000L
    }
}
