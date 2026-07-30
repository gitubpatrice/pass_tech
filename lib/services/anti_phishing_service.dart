import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Verdict d'une vérification anti-phishing.
enum PhishingVerdict {
  /// Domaine actif identique à l'entrée (ou normalisé identique).
  ok,

  /// Domaine actif inconnu (AS désactivée, navigateur non supporté, ou champ
  /// d'URL vide). On laisse passer mais on n'a pas pu vérifier.
  unknown,

  /// Domaine actif ressemble à celui de l'entrée (Levenshtein faible) →
  /// typosquatting probable. Alerte critique.
  typosquatting,

  /// Domaine actif complètement différent → phishing classique. Blocage.
  mismatch,
}

/// Résultat détaillé d'une vérification.
class PhishingCheck {
  final PhishingVerdict verdict;
  final String? activeDomain;
  final String? expectedDomain;
  final int? distance; // distance Levenshtein si applicable
  const PhishingCheck({
    required this.verdict,
    this.activeDomain,
    this.expectedDomain,
    this.distance,
  });
}

/// Service anti-phishing : compare le domaine du navigateur frontal
/// (détecté par PhishingDetectorService Kotlin) avec le domaine de l'entrée
/// avant que l'utilisateur copie son mot de passe vers le clipboard.
///
/// Usage :
/// ```dart
/// final svc = AntiPhishingService();
/// if (await svc.isEnabled) {
///   final check = await svc.check(entry.url);
///   if (check.verdict == PhishingVerdict.mismatch) { ... bloque ... }
/// }
/// ```
class AntiPhishingService {
  static const _channel = MethodChannel('com.passtech.pass_tech/antiphishing');
  static const _prefsKey = 'anti_phishing_enabled';

  /// Distance Levenshtein max sous laquelle on considère un typosquatting.
  /// 2 est conservateur : `paypal` vs `paypaI` (1) et `paypal` vs `paypaL` (1)
  /// déclenchent. `paypal` vs `paipal` (1). Au-delà de 2 pour des domaines
  /// courts (<8 chars), on bascule en mismatch direct.
  static const _typosquattingThreshold = 2;

  /// True si l'utilisateur a activé la protection anti-phishing dans Pass Tech.
  /// L'AccessibilityService côté Android peut être par ailleurs activé/désactivé
  /// indépendamment via Réglages Android — voir [isAccessibilityServiceActive].
  Future<bool> get isEnabled async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_prefsKey) ?? false;
  }

  Future<void> setEnabled(bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_prefsKey, enabled);
  }

  /// v2.4.0 / P0-1 — Purge le snapshot (domaine bancaire courant) côté
  /// natif. À appeler depuis `VaultService.lock()` et `PanicService.panic()` :
  /// sans ça, un domaine sensible reste en RAM jusqu'à 15 s après lock,
  /// récupérable par instrumentation native.
  ///
  /// Statique car appelable depuis n'importe quel contexte sans instance ;
  /// best-effort si le channel n'est pas joignable (non bloquant).
  static Future<void> clearSnapshot() async {
    try {
      await _channel.invokeMethod('clearSnapshot');
    } catch (_) {
      /* silent — best-effort */
    }
  }

  /// True si l'AccessibilityService est activé côté Réglages Android.
  /// L'utilisateur DOIT l'activer manuellement (sécurité système).
  Future<bool> get isAccessibilityServiceActive async {
    try {
      final r = await _channel.invokeMethod<bool>(
        'isAccessibilityServiceEnabled',
      );
      return r ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Ouvre Réglages > Accessibilité pour que l'utilisateur active le service.
  Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod('openAccessibilitySettings');
    } catch (_) {
      /* silent */
    }
  }

  /// Lit le dernier domaine détecté par PhishingDetectorService.
  /// Retourne null si AS désactivée, aucun navigateur frontal, ou
  /// champ URL vide.
  Future<String?> getCurrentDomain() async {
    try {
      return await _channel.invokeMethod<String>('getCurrentDomain');
    } catch (_) {
      return null;
    }
  }

  /// Vérifie si le domaine actif correspond à [expectedUrl] (URL d'entrée).
  /// Retourne un [PhishingCheck] avec le verdict.
  ///
  /// Si la protection est désactivée OU si l'expectedUrl est vide, retourne
  /// toujours [PhishingVerdict.ok] (laisse passer sans vérifier).
  Future<PhishingCheck> check(String expectedUrl) async {
    if (!await isEnabled) {
      return const PhishingCheck(verdict: PhishingVerdict.ok);
    }
    final expectedDomain = _normalizeDomain(expectedUrl);
    if (expectedDomain == null) {
      // Pas d'URL dans l'entrée → on ne peut pas comparer, on laisse passer.
      return const PhishingCheck(verdict: PhishingVerdict.ok);
    }
    final activeDomain = await getCurrentDomain();
    if (activeDomain == null || activeDomain.isEmpty) {
      return PhishingCheck(
        verdict: PhishingVerdict.unknown,
        expectedDomain: expectedDomain,
      );
    }

    // Match exact (déjà normalisé côté Kotlin : lowercase + retrait www.)
    if (activeDomain == expectedDomain) {
      return PhishingCheck(
        verdict: PhishingVerdict.ok,
        activeDomain: activeDomain,
        expectedDomain: expectedDomain,
      );
    }

    // Sous-domaine légitime : example.com couvre login.example.com etc.
    //
    // SEC F11 v2.5.2 — Avant : `_sameRootDomain`, qui comparait un eTLD+1
    // « simplifié » (2 derniers labels, plus une liste figée de 8 labels de
    // second niveau). Sans Public Suffix List, tout suffixe public hors de
    // cette liste était traité comme un domaine ENREGISTRABLE :
    // `attacker.github.io` et `victim.github.io` réduisaient tous deux à
    // `github.io`, donc verdict `ok` — sans même atteindre la détection de
    // typosquattage. Idem pour *.pages.dev, *.netlify.app, *.web.app,
    // *.firebaseapp.com, *.blogspot.com, *.herokuapp.com, *.myshopify.com.
    //
    // Désormais : véritable relation de sous-domaine. `a.endsWith('.' + b)`
    // ne franchit JAMAIS une frontière d'enregistrement, et ne dépend
    // d'aucune liste à maintenir. Le cas d'égalité exacte est déjà traité
    // au-dessus. Conséquence assumée : un domaine racine différent mais
    // apparenté (ex. `example.co.uk` vs `www.example.co.uk`) reste couvert,
    // alors qu'une entrée notée `login.example.com` ne couvrira plus
    // `example.com` — restriction volontaire, dans le sens sûr.
    if (activeDomain.endsWith('.$expectedDomain')) {
      return PhishingCheck(
        verdict: PhishingVerdict.ok,
        activeDomain: activeDomain,
        expectedDomain: expectedDomain,
      );
    }

    // Levenshtein sur les domaines complets (host)
    final dist = _levenshtein(activeDomain, expectedDomain);
    if (dist <= _typosquattingThreshold) {
      return PhishingCheck(
        verdict: PhishingVerdict.typosquatting,
        activeDomain: activeDomain,
        expectedDomain: expectedDomain,
        distance: dist,
      );
    }
    return PhishingCheck(
      verdict: PhishingVerdict.mismatch,
      activeDomain: activeDomain,
      expectedDomain: expectedDomain,
      distance: dist,
    );
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  /// Normalise une URL ou un domaine en host lowercase sans www.
  /// Retourne null si l'entrée n'est pas analysable.
  static String? _normalizeDomain(String url) {
    final trimmed = url.trim();
    if (trimmed.isEmpty) return null;
    if (!trimmed.contains('.')) return null;
    if (trimmed.contains(' ')) return null;
    final withScheme =
        trimmed.startsWith('http://') || trimmed.startsWith('https://')
        ? trimmed
        : 'https://$trimmed';
    try {
      final uri = Uri.parse(withScheme);
      var host = uri.host.toLowerCase();
      if (host.startsWith('www.')) host = host.substring(4);
      return host.isEmpty ? null : host;
    } catch (_) {
      return null;
    }
  }

  // SEC F11 v2.5.2 — `_sameRootDomain` et `_eTldPlusOne` retirés : leur
  // heuristique d'eTLD+1 sans Public Suffix List traitait les suffixes publics
  // (github.io, pages.dev, netlify.app…) comme des domaines enregistrables et
  // faisait passer `attacker.pages.dev` pour `victim.pages.dev`. Remplacés
  // par une relation de sous-domaine stricte dans `check()`.

  /// Distance d'édition de Levenshtein entre 2 chaînes (insertion / suppression
  /// / substitution coûtent 1). Implémentation standard O(m*n).
  /// Limité à 50 chars pour éviter le coût sur des domaines absurdement longs.
  static int _levenshtein(String s, String t) {
    if (s.length > 50) s = s.substring(0, 50);
    if (t.length > 50) t = t.substring(0, 50);
    if (s == t) return 0;
    if (s.isEmpty) return t.length;
    if (t.isEmpty) return s.length;
    final m = s.length, n = t.length;
    var prev = List<int>.generate(n + 1, (i) => i);
    var curr = List<int>.filled(n + 1, 0);
    for (var i = 1; i <= m; i++) {
      curr[0] = i;
      for (var j = 1; j <= n; j++) {
        final cost = s.codeUnitAt(i - 1) == t.codeUnitAt(j - 1) ? 0 : 1;
        curr[j] = [
          curr[j - 1] + 1, // insertion
          prev[j] + 1, // suppression
          prev[j - 1] + cost, // substitution
        ].reduce((a, b) => a < b ? a : b);
      }
      final tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[n];
  }
}
