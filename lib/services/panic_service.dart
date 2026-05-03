import 'package:flutter/services.dart';
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
  static Future<void> panic({bool disguise = true}) async {
    // 1. Lock vault (synchrone, garanti)
    try {
      VaultService().lock();
    } catch (_) {}
    // 2. Clear clipboard (synchrone via channel)
    try {
      await ClipboardService.cancelAndClear();
    } catch (_) {}
    // 3. Disguise (Android 11+ : peut prendre 1-2s à se refléter sur le launcher)
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
