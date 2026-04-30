package com.passtech.pass_tech

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService qui détecte le DOMAINE actif dans le navigateur frontal.
 *
 * Approche : à chaque WINDOW_STATE_CHANGED ou WINDOW_CONTENT_CHANGED d'un
 * package navigateur connu, on cherche le widget de la barre d'URL via son
 * resourceId (mapping browser-spécifique), on en extrait l'URL, on stocke
 * SEULEMENT le domaine (host) dans une variable statique partagée.
 *
 * **Privacy** :
 * - On ne stocke JAMAIS le path complet ni les paramètres de l'URL.
 * - On ne stocke pas non plus le contenu de la page.
 * - On ne stocke que le domaine racine (host normalisé), volatile (une seule
 *   valeur en mémoire, écrasée à chaque event).
 * - Aucune sortie réseau, aucun log persistant.
 *
 * **Sécurité** : l'AS est désactivable à tout moment depuis Réglages Android.
 * Dart vérifie via [getCurrentDomain] et reçoit null si AS désactivée ou
 * domaine introuvable.
 */
class PhishingDetectorService : AccessibilityService() {

    companion object {
        /**
         * Dernier domaine détecté (host uniquement, sans path ni query).
         * Volatile pour visibility cross-thread (l'AS tourne sur son propre
         * thread, Dart lit depuis le main isolate via channel).
         */
        @Volatile
        private var _lastDomain: String? = null
        @Volatile
        private var _lastDomainAtMs: Long = 0L

        /**
         * Fenêtre de fraîcheur. Au-delà, on considère le domaine comme stale
         * (l'utilisateur a peut-être quitté le navigateur). Le getter retourne
         * null après expiration → côté Dart : verdict `unknown` (fail-safe).
         */
        private const val FRESHNESS_MS = 60_000L

        /**
         * Dernier domaine détecté, valide uniquement pendant FRESHNESS_MS.
         * Au-delà, retourne null pour éviter de "valider" un domaine périmé
         * lorsque l'utilisateur n'est plus dans le navigateur.
         */
        val lastDomain: String?
            get() {
                val d = _lastDomain ?: return null
                if (System.currentTimeMillis() - _lastDomainAtMs > FRESHNESS_MS) return null
                return d
            }

        /**
         * Mapping resourceId → package navigateur. Ajouts simples pour
         * supporter de nouveaux navigateurs.
         *
         * Pour trouver le bon resourceId d'un nouveau navigateur :
         *   adb shell uiautomator dump  → ouvrir window_dump.xml → chercher
         *   le node avec contenu d'URL.
         */
        private val urlBarResourceIds: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf(
                "com.android.chrome:id/url_bar"
            ),
            "com.brave.browser" to listOf(
                "com.brave.browser:id/url_bar"
            ),
            "com.vivaldi.browser" to listOf(
                "com.vivaldi.browser:id/url_bar"
            ),
            "com.microsoft.emmx" to listOf(
                "com.microsoft.emmx:id/url_bar"
            ),
            "com.opera.browser" to listOf(
                "com.opera.browser:id/url_field"
            ),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            "org.mozilla.fenix" to listOf(
                "org.mozilla.fenix:id/mozac_browser_toolbar_url_view"
            ),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
            ),
            "com.duckduckgo.mobile.android" to listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput"
            )
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val resourceIds = urlBarResourceIds[pkg] ?: return

        // On ne traite que les changements de window/contenu pour limiter le coût.
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            for (rid in resourceIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(rid)
                if (nodes.isNullOrEmpty()) continue
                for (node in nodes) {
                    val raw = node.text?.toString() ?: continue
                    val domain = extractDomain(raw)
                    if (domain != null) {
                        _lastDomain = domain
                        _lastDomainAtMs = System.currentTimeMillis()
                        return
                    }
                }
            }
        } catch (_: Exception) {
            // Ne rien faire : l'erreur ne doit pas crasher le service.
        }
    }

    override fun onInterrupt() {
        // Service interrompu (pas de l'app). Reset par sécurité.
        _lastDomain = null
    }

    override fun onDestroy() {
        super.onDestroy()
        _lastDomain = null
    }

    /**
     * Extrait le domaine (host) d'une URL ou d'une chaîne d'URL bar.
     * Robuste face à :
     * - URLs complètes : https://www.example.com/path?q=1
     * - URLs sans schéma : example.com/path
     * - Recherches Google ne contenant pas d'URL : retourne null
     * - Champs vides : null
     */
    private fun extractDomain(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Si c'est une recherche (pas un domaine), retourne null
        if (!trimmed.contains('.')) return null
        if (trimmed.contains(' ')) return null

        // Ajoute schéma si absent pour parser
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return try {
            val uri = android.net.Uri.parse(withScheme)
            val host = uri.host?.lowercase() ?: return null
            // Retire un éventuel "www." pour normaliser
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (_: Exception) {
            null
        }
    }
}
