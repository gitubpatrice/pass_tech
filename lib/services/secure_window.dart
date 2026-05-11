import 'package:flutter/services.dart';

/// Désactive / réactive `WindowManager.LayoutParams.FLAG_SECURE` sur la
/// fenêtre principale via un MethodChannel Kotlin.
///
/// Contexte :
/// FLAG_SECURE est posé au boot dans `MainActivity.onCreate` pour
/// bloquer screenshots, enregistrement écran et aperçu Recent Apps —
/// c'est la bonne valeur par défaut pour un gestionnaire de mots de
/// passe.
///
/// Effet de bord identifié v2.3.7 :
/// sur Samsung One UI + Knox, FLAG_SECURE bloque la **suggestion
/// clipboard** de l'IME ET parfois le **menu contextuel long-press
/// Coller**. Conséquence : impossible de coller du texte dans
/// l'éditeur de Notes sécurisées.
///
/// Solution v2.3.8 :
/// Les écrans qui nécessitent un copier/coller/sélectionner système
/// normal (notamment l'éditeur de Notes type=note) appellent
/// `SecureWindow.relax()` en `initState` et `SecureWindow.restore()`
/// en `dispose`. Le flag est ainsi temporairement retiré pendant la
/// vie de l'écran, puis re-posé en sortie.
///
/// Pas de risque sécurité car :
/// - Les notes type=note sont du texte libre (pas un mot de passe).
/// - Le contenu reste visible à l'écran : si l'utilisateur prend un
///   screenshot pendant qu'il édite une note, c'est SA décision.
/// - Le flag est immédiatement remis au dispose → tous les autres
///   écrans (vault unlock, password fields, settings…) restent
///   protégés.
///
/// Refcount-aware : plusieurs écrans empilés peuvent appeler `relax()`
/// sans que le premier qui dispose ne re-pose le flag prématurément.
class SecureWindow {
  static const _channel = MethodChannel('com.passtech.pass_tech/secure_window');

  /// Compteur de demandes de relaxation actives. FLAG_SECURE est
  /// désactivé tant que ce compteur est > 0 ; il est remis à `restore()`
  /// quand il retombe à 0.
  static int _relaxCount = 0;

  /// Demande la désactivation temporaire de FLAG_SECURE pour permettre
  /// les opérations clipboard / sélection système. À appeler dans
  /// `initState` d'un écran qui nécessite ces interactions.
  static Future<void> relax() async {
    _relaxCount++;
    if (_relaxCount == 1) {
      try {
        await _channel.invokeMethod('setSecure', {'enabled': false});
      } catch (_) {
        /* silent — non bloquant */
      }
    }
  }

  /// Restaure FLAG_SECURE quand le dernier écran qui demandait sa
  /// désactivation se ferme. À appeler dans `dispose`.
  static Future<void> restore() async {
    if (_relaxCount <= 0) return;
    _relaxCount--;
    if (_relaxCount == 0) {
      try {
        await _channel.invokeMethod('setSecure', {'enabled': true});
      } catch (_) {
        /* silent */
      }
    }
  }
}
