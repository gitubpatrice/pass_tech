import 'package:shared_preferences/shared_preferences.dart';

/// Suivi de la dernière sauvegarde chiffrée `.ptbak`.
///
/// Ajouté le 2026-08-03 après la perte définitive d'un coffre : mot de passe
/// maître oublié, biométrie supprimée par le mode panique, **et aucune
/// sauvegarde**. Rien dans l'application ne suggérait d'en faire une, alors que
/// c'est le SEUL moyen de récupération qui existe — il n'y a ni cloud, ni
/// compte, ni séquestre de clé.
///
/// Volontairement dans les préférences ordinaires et non dans le stockage
/// sécurisé : un horodatage de sauvegarde n'est pas un secret, et le placer
/// dans le stockage chiffré le rendrait illisible depuis l'écran d'accueil sans
/// bénéfice. Il ne révèle rien du contenu du coffre.
///
/// ⚠️ On n'enregistre QUE la date, jamais le chemin ni la phrase secrète.
class BackupReminder {
  BackupReminder._();

  static const _lastBackupKey = 'pt_last_backup_ms';

  /// Appelé après un export `.ptbak` réussi.
  static Future<void> markBackupDone() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_lastBackupKey, DateTime.now().millisecondsSinceEpoch);
  }

  /// Vrai si aucune sauvegarde chiffrée n'a jamais été enregistrée.
  ///
  /// Sciemment binaire plutôt que « sauvegarde trop ancienne » : un rappel qui
  /// revient périodiquement devient un bandeau qu'on ne lit plus. Celui-ci
  /// disparaît définitivement à la première sauvegarde, donc quand il est là,
  /// il veut dire quelque chose.
  static Future<bool> get neverBackedUp async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_lastBackupKey) == null;
  }

  /// Efface la trace — utilisé par la suppression complète du coffre, pour
  /// qu'un coffre recréé reparte avec le rappel actif.
  static Future<void> reset() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_lastBackupKey);
  }
}
