import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'anti_phishing_service.dart';
import 'clipboard_service.dart';
import 'vault_service.dart';

/// Mode panique : actions de protection en cas d'urgence
/// (vol, agression, frontière, contrôle).
///
/// Triple action :
/// 1. Verrouille immédiatement le coffre (efface la clé en mémoire)
/// 2. Vide le presse-papiers
/// 3. (Optionnel) Camoufle l'icône de l'app sur le launcher
///    en activant un alias "Calculatrice" et en désactivant l'alias normal.
///    Aucune trace visuelle de Pass Tech sur le launcher.
///
/// Le camouflage est réversible depuis les Réglages quand l'utilisateur
/// est en sécurité.
class PanicService {
  static const _channel = MethodChannel('com.passtech.pass_tech/disguise');

  /// Action panique complète : lock + clipboard + disguise.
  /// Best-effort sur chaque étape — un échec n'empêche pas les autres.
  /// A5 v2.3.8 — purge du snapshot anti-phishing (qui survivait à `panic()`
  /// jusqu'à expiration des 15 s `FRESHNESS_MS` côté Kotlin).
  /// QW5 v2.4.0 — délégué à `AntiPhishingService.clearSnapshot()` qui possède
  /// déjà le channel — supprime la duplication du `MethodChannel` ici.

  /// F15 v2.4.4 — clés du compteur d'échecs / lockout (miroir de
  /// `VaultService._failCountKey` / `_lockoutKey`). Le storage est partagé
  /// (même `FlutterSecureStorage` par défaut côté Android).
  static const _failCountKey = 'pt_fail_count';
  static const _lockoutKey = 'pt_lockout_until';
  static const _storage = FlutterSecureStorage();

  static Future<void> panic({bool disguise = true}) async {
    // 1. Lock vault (synchrone, garanti)
    try {
      VaultService().lock();
    } catch (_) {}
    // 2. Clear clipboard (synchrone via channel)
    try {
      await ClipboardService.cancelAndClear();
    } catch (_) {}
    // 3. A5 v2.3.8 — purge du snapshot du domaine anti-phishing.
    try {
      await AntiPhishingService.clearSnapshot();
    } catch (_) {
      /* AS désactivée, ignore */
    }
    // 4. F15 v2.4.4 — reset le compteur d'échecs ET le lockout.
    // Avant : après panic+disguise, un attaquant tombant sur le decoy
    // pouvait déclencher un lockout 30 min "anormal" via 5 tentatives
    // ratées sur le slot decoy — signal indirect qu'une situation
    // d'urgence vient d'avoir lieu (le compteur d'échecs antérieur de
    // l'utilisateur légitime persistait). Désormais : état post-panic
    // indistinguable d'un boot frais.
    try {
      await _storage.delete(key: _failCountKey);
      await _storage.delete(key: _lockoutKey);
    } catch (_) {}
    // 5. Disguise (Android 11+ : peut prendre 1-2s à se refléter sur le launcher)
    if (disguise) {
      try {
        await _channel.invokeMethod('setDisguised', {'disguised': true});
      } catch (_) {}
    }
  }

  /// Désactive le camouflage : restaure l'icône Pass Tech normale.
  static Future<void> revealApp() async {
    try {
      await _channel.invokeMethod('setDisguised', {'disguised': false});
    } catch (_) {}
  }

  /// Indique si l'app est actuellement camouflée.
  static Future<bool> isDisguised() async {
    try {
      final r = await _channel.invokeMethod<bool>('isDisguised');
      return r ?? false;
    } catch (_) {
      return false;
    }
  }
}
