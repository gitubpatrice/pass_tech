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
            for (bar in addressBars) {
                val nodes = root.findAccessibilityNodeInfosByViewId(bar.viewId) ?: continue
                found += nodes
                for (node in nodes) {
                    val host = bar.hostOrNull(node, found) ?: continue
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

    /**
     * One address bar of one browser, and **where in it the address is written**.
     *
     * Reading a node's DESCRIPTION is declared browser by browser and never used as a fallback when
     * the text is empty. In several browsers the field beside the address bar holds the page TITLE,
     * which the page itself chooses: 2.7.1 read one of those, and a fake page titled `mabanque.fr`
     * was read as that domain and answered for it (removed on 2026-08-03). A fallback would bring
     * that back for every browser at once.
     */
    private class AddressBar private constructor(
        val viewId: String,
        private val insideTag: String?,
        private val inDescription: Boolean,
    ) {

        /** [opened] collects every node this walks, so the caller can give them all back. */
        fun hostOrNull(node: AccessibilityNodeInfo, opened: MutableList<AccessibilityNodeInfo>): String? {
            val target = if (insideTag == null) node else taggedOrNull(node, insideTag, opened) ?: return null
            return if (inDescription) {
                DomainMatch.fromAddressBarDescription(target.contentDescription?.toString().orEmpty())
            } else {
                DomainMatch.fromAddressBar(target.text?.toString().orEmpty())
            }
        }

        companion object {
            fun text(viewId: String) = AddressBar(viewId, insideTag = null, inDescription = false)

            /**
             * [viewId] is the real view around it — a Compose test tag is not a resource the
             * framework can resolve, so it is found by walking that node's own small subtree.
             */
            fun describedTag(viewId: String, tag: String) =
                AddressBar(viewId, insideTag = tag, inDescription = true)
        }
    }

    private companion object {
        /** `AccessibilityNodeInfo.recycle` does nothing from API 34 on, and is deprecated. */
        const val RECYCLING_ENDED = 34

        val WATCHED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )

        /**
         * Firefox's Compose toolbar names its address box by a **test tag**, which carries no
         * package prefix and is not a resource the framework can resolve — so
         * `findAccessibilityNodeInfosByViewId` cannot reach it. The toolbar around it is a real
         * view, and the box is found by walking that.
         */
        const val FIREFOX_URL_BOX = "ADDRESSBAR_URL_BOX"

        /**
         * 2.7.1's list, with Firefox corrected. **An identifier goes stale in silence**: Firefox
         * moved to a Compose toolbar, `mozac_browser_toolbar_url_view` stopped existing, and the
         * protection answered "could not check" on every copy — measured on a Galaxy S24 on
         * 2026-09-23, where Firefox is the default browser. Chrome was measured the same day and
         * still puts the address in the text of `url_bar`. **The seven others have never been
         * checked on a device**: open the browser on a page, withdraw the accessibility grant so
         * `uiautomator dump` can read the screen, and look at the `resource-id` of the address bar.
         *
         * Both Firefox identifiers are kept, the new one first: a phone still on an older build
         * reads the old one.
         */
        val ADDRESS_BARS: Map<String, List<AddressBar>> = mapOf(
            "com.android.chrome" to listOf(AddressBar.text("com.android.chrome:id/url_bar")),
            "com.brave.browser" to listOf(AddressBar.text("com.brave.browser:id/url_bar")),
            "com.vivaldi.browser" to listOf(AddressBar.text("com.vivaldi.browser:id/url_bar")),
            "com.microsoft.emmx" to listOf(AddressBar.text("com.microsoft.emmx:id/url_bar")),
            "com.opera.browser" to listOf(AddressBar.text("com.opera.browser:id/url_field")),
            "org.mozilla.firefox" to listOf(
                AddressBar.describedTag("org.mozilla.firefox:id/composable_toolbar", FIREFOX_URL_BOX),
                AddressBar.text("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
            ),
            "org.mozilla.fenix" to listOf(
                AddressBar.describedTag("org.mozilla.fenix:id/composable_toolbar", FIREFOX_URL_BOX),
                AddressBar.text("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
            ),
            "com.sec.android.app.sbrowser" to listOf(
                AddressBar.text("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            ),
            "com.duckduckgo.mobile.android" to listOf(
                AddressBar.text("com.duckduckgo.mobile.android:id/omnibarTextInput"),
            ),
        )
    }
}

/** How deep inside a browser's toolbar a Compose test tag is looked for. A toolbar is shallow. */
private const val MAX_TAG_DEPTH = 6

/**
 * The node named [tag] inside [node], or `null`. Depth-first and bounded: this is handed a browser's
 * toolbar, never a page, and it must not become a walk of one. [opened] collects every node it takes
 * hold of, so the caller can give them all back on the versions where that still matters.
 */
private fun taggedOrNull(
    node: AccessibilityNodeInfo,
    tag: String,
    opened: MutableList<AccessibilityNodeInfo>,
    depth: Int = 0,
): AccessibilityNodeInfo? {
    if (node.viewIdResourceName == tag) return node
    if (depth >= MAX_TAG_DEPTH) return null
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        opened += child
        taggedOrNull(child, tag, opened, depth + 1)?.let { return it }
    }
    return null
}
