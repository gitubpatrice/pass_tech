import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class UpdateInfo {
  final String version;
  final String body;
  /// SHA-256 de l'APK arm64-v8a publié, extrait du body de la release
  /// (au format `SHA-256: <hex>` ou `SHA256: <hex>`). null si non publié.
  /// Sert à l'utilisateur pour vérifier manuellement l'intégrité de l'APK
  /// téléchargé avant install (defense en profondeur — V9 audit).
  final String? expectedSha256;
  const UpdateInfo(this.version, this.body, {this.expectedSha256});
}

class UpdateService {
  static const _repo    = 'gitubpatrice/pass_tech';
  static const _current = '1.10.0';
  static const _checkKey = 'last_update_check';

  Future<UpdateInfo?> checkForUpdate({bool force = false}) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final last  = prefs.getInt(_checkKey) ?? 0;
      final now   = DateTime.now().millisecondsSinceEpoch;
      if (!force && now - last < 3600000) return null;

      final resp = await http
          .get(Uri.parse('https://api.github.com/repos/$_repo/releases/latest'))
          .timeout(const Duration(seconds: 10));
      if (resp.statusCode != 200) return null;

      await prefs.setInt(_checkKey, now);
      final json   = jsonDecode(resp.body) as Map<String, dynamic>;
      final latest = (json['tag_name'] as String).replaceAll('v', '');

      if (_isNewer(latest, _current)) {
        final body = json['body'] as String? ?? '';
        return UpdateInfo(latest, body, expectedSha256: _extractSha256(body));
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Extrait le SHA-256 hex du body de la release GitHub. Cherche les
  /// patterns `SHA-256: <hex>` ou `SHA256: <hex>` (insensible à la casse).
  /// Retourne null si absent ou format invalide (≠ 64 chars hex).
  static String? _extractSha256(String body) {
    final match = RegExp(
      r'sha-?256\s*[:=]\s*([0-9a-fA-F]{64})',
      caseSensitive: false,
    ).firstMatch(body);
    return match?.group(1)?.toLowerCase();
  }

  static bool _isNewer(String a, String b) {
    final av = a.split('.').map(int.parse).toList();
    final bv = b.split('.').map(int.parse).toList();
    for (int i = 0; i < 3; i++) {
      final ai = i < av.length ? av[i] : 0;
      final bi = i < bv.length ? bv[i] : 0;
      if (ai > bi) return true;
      if (ai < bi) return false;
    }
    return false;
  }
}
