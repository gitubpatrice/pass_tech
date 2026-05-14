import 'dart:async';
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

/// Privacy-preserving breach check via HaveIBeenPwned k-anonymity API.
/// Only the first 5 chars of the SHA-1 hash leave the device.
class BreachService {
  /// F5 v2.4.4 — UA fixé, banal et **constant pour tous les utilisateurs**.
  ///
  /// Précédente approche (QW7 v2.4.0) : pool de 4 UAs choisi aléatoirement au
  /// démarrage de l'app. Mais `static final` = tiré UNE fois par lancement et
  /// conservé pour toute la session — un observateur réseau (HIBP logs, MITM
  /// non protégé par HSTS, ISP) qui voyait un même `_userAgent` sur plusieurs
  /// requêtes pouvait corréler une installation Pass Tech avec un compte HIBP
  /// ou une période d'activité. Avec 4 UAs, la combinaison `IP × UA` était
  /// quasi-unique.
  ///
  /// Désormais : UA strictement constant (`Mozilla/5.0 (compatible)`),
  /// identique entre toutes les installations Pass Tech, toutes les requêtes,
  /// toutes les sessions. HIBP voit le même UA banal qu'un crawler générique
  /// — pas de surface pour fingerprinter une installation distincte.
  static const String _userAgent = 'Mozilla/5.0 (compatible)';

  /// Returns the number of times the password was found in known breaches,
  /// or 0 if not found, or -1 on network error.
  static Future<int> checkPassword(String password) async {
    if (password.isEmpty) return 0;
    try {
      final hash = sha1.convert(utf8.encode(password)).toString().toUpperCase();
      final prefix = hash.substring(0, 5);
      final suffix = hash.substring(5);

      final resp = await http
          .get(
            Uri.parse('https://api.pwnedpasswords.com/range/$prefix'),
            headers: const {'User-Agent': _userAgent, 'Add-Padding': 'true'},
          )
          .timeout(const Duration(seconds: 8));

      if (resp.statusCode != 200) return -1;

      for (final line in resp.body.split('\n')) {
        final parts = line.trim().split(':');
        if (parts.length == 2 && parts[0] == suffix) {
          return int.tryParse(parts[1]) ?? 1;
        }
      }
      return 0;
    } catch (_) {
      return -1;
    }
  }

  /// QW1 v2.4.0 — Vérification par lots avec dédoublonnage et parallélisme.
  ///
  /// - Déduplique les passwords identiques (1 seule requête par valeur unique).
  /// - Lance les requêtes en parallèle bornées par [concurrency] (par défaut 6
  ///   pour rester courtois avec HIBP et éviter de saturer un réseau mobile).
  /// - Appelle [onProgress] après chaque résolution avec le compte traité.
  ///
  /// Gain typique : 50 entrées → ~6 s au lieu de ~30 s en séquentiel.
  static Future<Map<String, int>> checkPasswordsBatch(
    Iterable<String> passwords, {
    int concurrency = 6,
    void Function(int done, int total)? onProgress,
  }) async {
    final unique = passwords.where((p) => p.isNotEmpty).toSet().toList();
    final results = <String, int>{};
    if (unique.isEmpty) return results;

    int next = 0;
    int done = 0;
    Future<void> worker() async {
      while (true) {
        final i = next++;
        if (i >= unique.length) return;
        final pwd = unique[i];
        results[pwd] = await checkPassword(pwd);
        done++;
        onProgress?.call(done, unique.length);
      }
    }

    final workers = List.generate(
      concurrency.clamp(1, unique.length),
      (_) => worker(),
    );
    await Future.wait(workers);
    return results;
  }
}
