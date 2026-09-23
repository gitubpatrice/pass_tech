package com.filestech.pass_tech.core.phishing

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Reads the address bar of a browser in front, and keeps the host of it. Nothing else leaves this
 * class: not the path, not the query, not a word of the page, and nothing is written to disk or sent
 * anywhere. One host at a time, in memory, replaced at each reading and gone fifteen seconds later.
 *
 * The system only ever hands it events from the browsers listed in `res/xml/phishing_detector.xml`;
 * the same list appears below, since a browser without a known address-bar widget could not be read
 * anyway.
 *
 * **The service component ships disabled** (`android:enabled="false"`) and is enabled only while the
 * owner has the protection on. An app that declares an accessibility service is listed under
 * Settings › Accessibility whatever its state, under the service's own name — which would show
 * "Pass Tech" on a phone whose launcher has been turned into a calculator, and would tell anyone
 * looking that the protection is off while the system still let it read every address bar. See
 * [AntiPhishing].
 */
class PhishingDetectorService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun snapshot(): DomainSnapshot
    }

    // The system builds this service, so it cannot be constructor-injected; the one snapshot the rest
    // of the app reads is fetched from the same graph instead.
    private val snapshot: DomainSnapshot by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Parts::class.java).snapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val addressBars = ADDRESS_BARS[packageName] ?: return
        if (event.eventType !in WATCHED_EVENTS) return
        val root = rootInActiveWindow ?: return
        val found = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (id in addressBars) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                found += nodes
                for (node in nodes) {
                    val host = DomainMatch.fromAddressBar(node.text?.toString().orEmpty()) ?: continue
                    snapshot.record(host)
                    return
                }
            }
        } catch (_: Exception) {
            // A window that went away between the event and the reading. There is nothing to report
            // to, and crashing here would take the accessibility service down for good.
        } finally {
            recycle(found, root)
        }
        // A bar showing a search, or a half-typed name, leaves the last reading alone rather than
        // clearing it: it is cleared anyway fifteen seconds after it was taken, and clearing on every
        // keystroke would answer "could not check" to an owner who is exactly where they think.
    }

    /** Until API 34 a node holds a system handle until it is given back. From 34 on it does nothing. */
    @Suppress("DEPRECATION")
    private fun recycle(nodes: List<AccessibilityNodeInfo>, root: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= RECYCLING_ENDED) return
        nodes.forEach { runCatching { it.recycle() } }
        runCatching { root.recycle() }
    }

    override fun onInterrupt() {
        snapshot.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        snapshot.clear()
    }

    private companion object {
        /** `AccessibilityNodeInfo.recycle` does nothing from API 34 on, and is deprecated. */
        const val RECYCLING_ENDED = 34

        val WATCHED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )

        /**
         * 2.7.1's list, unchanged. The identifier must be the one of the **address bar**: the field
         * next to it in several of these browsers holds the page TITLE, which the page chooses — a
         * fake page titled `mabanque.fr` was read as that domain and answered for, which turned the
         * protection against its owner (2.7.1 removed it on 2026-08-03).
         */
        val ADDRESS_BARS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
            "org.mozilla.fenix" to listOf("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
            "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
        )
    }
}
