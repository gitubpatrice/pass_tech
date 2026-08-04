/// Calcul du score de l'audit de coffre — fonction PURE, testable.
///
/// ## Pourquoi ce fichier existe
///
/// Le score était calculé en ligne dans l'écran, par soustractions absolues :
///
/// ```dart
/// s -= _weak.length.clamp(0, 6) * 5;        // -30 au maximum
/// s -= _duplicates.length.clamp(0, 6) * 5;  // -30 au maximum
/// s -= _old.length.clamp(0, 4) * 5;         // -20 au maximum
/// s -= _missing2fa.length.clamp(0, 4) * 5;  // -20 au maximum
/// ```
///
/// Quatre défauts, tous corrigés ici :
///
/// 1. **Il ne tenait aucun compte de la taille du coffre.** Au-delà de six
///    mots de passe faibles la pénalité s'arrêtait. Un coffre de six entrées
///    dont les six étaient faibles affichait donc **70/100, « Bon »** — et un
///    coffre de deux cents entrées avec six faibles affichait exactement la
///    même chose. Le score rassurait précisément là où il ne fallait pas.
///
/// 2. **Le seul signal FACTUEL ne comptait pas.** Le résultat de la
///    vérification HaveIBeenPwned n'entrait pas dans le calcul : un mot de
///    passe dont on a la preuve qu'il figure dans une fuite publique valait
///    zéro point de pénalité, pendant que l'ancienneté — le signal le plus
///    contestable — en coûtait jusqu'à vingt.
///
/// 3. **L'ancienneté pénalisait, à rebours de l'état de l'art.** Le NIST
///    (SP 800-63B) recommande explicitement de NE PAS imposer de rotation
///    périodique et de ne changer qu'en cas de compromission avérée. Punir un
///    mot de passe fort, unique et non compromis parce qu'il a un an pousse à
///    l'incrément (`MotDePasse1` → `MotDePasse2`), qui est plus faible. Elle
///    est désormais informative et ne touche plus au score.
///
/// 4. **Les défauts se cumulaient sur une même entrée.** Un mot de passe à la
///    fois faible ET réutilisé était compté deux fois. Ici une entrée vaut son
///    défaut le PLUS GRAVE, une seule fois.
///
/// ## Le modèle retenu
///
/// Le score est la **santé moyenne des mots de passe du coffre**. Chaque
/// entrée vaut un nombre de points selon son pire défaut, et le score est la
/// moyenne. Il est donc proportionnel par construction : une entrée fautive
/// parmi deux cents pèse ce qu'elle doit peser, et six sur six écrasent le
/// score comme elles le doivent.
library;

abstract final class VaultAuditScore {
  /// Catégories dont on attend un second facteur.
  ///
  /// Ces chaînes sont les valeurs CANONIQUES stockées dans le coffre, pas des
  /// libellés traduits — cf. `models/category.dart`, qui conserve le français
  /// en base pour ne pas casser les coffres existants. Les comparer à des
  /// chaînes traduites ferait silencieusement échouer le contrôle hors du
  /// français.
  ///
  /// « Réseaux sociaux » a été ajoutée : la prise de contrôle d'un compte
  /// social sert de rebond vers les autres services (réinitialisations,
  /// authentification déléguée), au même titre qu'une messagerie.
  static const sensitiveCategories = {'Banque', 'Email', 'Réseaux sociaux'};

  /// Barème par entrée. Une entrée ne compte QUE pour son pire défaut.
  ///
  /// Les valeurs ne sont pas des pourcentages de gravité mais des points de
  /// santé : `compromised` vaut 0 parce qu'un mot de passe publiquement fuité
  /// n'offre plus aucune protection, quelle que soit sa complexité.
  static const int pointsCompromised = 0;
  static const int pointsWeak = 25;
  static const int pointsDuplicate = 55;
  static const int pointsNo2fa = 85;
  static const int pointsHealthy = 100;

  /// Points d'une entrée, par gravité décroissante.
  ///
  /// L'ordre des tests EST le barème : il fixe lequel des défauts l'emporte
  /// quand une entrée en cumule plusieurs.
  static int pointsFor({
    required bool compromised,
    required bool weak,
    required bool duplicate,
    required bool sensitiveWithout2fa,
  }) {
    if (compromised) return pointsCompromised;
    if (weak) return pointsWeak;
    if (duplicate) return pointsDuplicate;
    if (sensitiveWithout2fa) return pointsNo2fa;
    return pointsHealthy;
  }

  /// Moyenne des points sur la population de mots de passe.
  ///
  /// Retourne `null` quand cette population est VIDE — un coffre sans mot de
  /// passe n'a pas de score. L'ancienne formule y répondait `100`,
  /// « Excellent », ce qui revenait à féliciter un coffre vide.
  static int? average(Iterable<int> pointsPerEntry) {
    var total = 0;
    var n = 0;
    for (final p in pointsPerEntry) {
      total += p;
      n++;
    }
    if (n == 0) return null;
    return (total / n).round().clamp(0, 100);
  }
}
