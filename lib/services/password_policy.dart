import 'password_strength_service.dart';

/// Motif de refus d'un mot de passe. `null` = accepté.
enum PasswordRejection {
  /// Trop court (voir [PasswordPolicy.minLength]).
  tooShort,

  /// Assez long, mais devinable : répétition (`aaaaaaaaaa`), suite
  /// (`abcdefghij`), ou mot de passe figurant dans la liste des plus courants.
  tooWeak,
}

/// Règle UNIQUE de validation des mots de passe et phrases secrètes.
///
/// ─────────────────────────────────────────────────────────────────────────
/// SEC 2026-08-04 — créée pour supprimer une asymétrie de garde.
///
/// Le contrôle d'entropie n'existait QUE dans `setup_screen.dart`, à la
/// création du coffre. Les quatre autres points d'entrée — changement du mot de
/// passe maître, phrase secrète d'une sauvegarde `.ptbak`, mot de passe du
/// coffre leurre, mot de passe héritier — ne vérifiaient que la LONGUEUR.
///
/// Conséquence : on pouvait créer un coffre avec un mot de passe solide, puis
/// le remplacer par `aaaaaaaaaaaa`. Douze caractères, accepté sans broncher, ni
/// répétition détectée ni mot de passe courant rejeté. La porte d'entrée était
/// gardée, aucune des autres portes de la même maison ne l'était.
///
/// Le cas le plus grave était la phrase du `.ptbak` : c'est le seul artefact
/// NON lié au matériel — restaurable sur n'importe quel appareil, donc
/// attaquable hors ligne — et c'est lui qui avait la garde la plus faible.
///
/// ─────────────────────────────────────────────────────────────────────────
/// CE QUI N'EST PAS EXIGÉ, ET POURQUOI
///
/// Aucune obligation de symbole, de chiffre ni de casse. Le NIST déconseille
/// explicitement ces règles de composition depuis SP 800-63B (2017) : elles
/// poussent vers `Motdepasse2024!`, prévisible, et n'ajoutent presque rien.
///
/// Ce qui est exigé, c'est de l'ENTROPIE — donc de la longueur, ou de la
/// variété, au choix de l'utilisateur. À 12 caractères, les minuscules SEULES
/// suffisent déjà (56 bits) : personne n'est contraint d'ajouter quoi que ce
/// soit. C'est à l'utilisateur de décider comment il atteint la barre, pas à
/// l'application de lui imposer une recette.
///
/// ⚠️ Le calcul d'entropie SURESTIME les mots de passe humains : `motdepasse`
/// et ses variantes affichent des bits qu'une attaque par dictionnaire français
/// balaie en quelques minutes. `isCommon` ne rattrape que les plus connus.
/// C'est la raison pour laquelle la longueur minimale ne descend pas plus bas,
/// et pourquoi le générateur de phrases de passe reste la meilleure réponse.
abstract final class PasswordPolicy {
  PasswordPolicy._();

  /// Longueur minimale, tous points d'entrée confondus.
  ///
  /// Maintenue à 12 (décision du 2026-08-04, après avoir envisagé 10). À 12
  /// caractères, même en minuscules seules, on atteint 56 bits — au-dessus du
  /// seuil. Aucune classe de caractères n'est donc nécessaire, ce qui est
  /// précisément l'objectif : la barre est atteignable par la seule longueur.
  ///
  /// Une phrase comme `chien bleu matin` passe sans effort. C'est ce que le
  /// générateur de phrases de passe produit, et c'est la voie à privilégier.
  static const int minLength = 12;

  /// Score minimal (voir [PasswordStrengthService.score]) : 0,6 = 48 bits.
  ///
  /// Ce seuil pénalise déjà les répétitions, les suites arithmétiques et les
  /// mots de passe courants — il fait le travail qu'une règle de composition
  /// prétend faire, sans en avoir les défauts.
  static const double minScore = 0.6;

  /// Nombre minimal de caractères DISTINCTS.
  ///
  /// Comble une limite du calcul d'entropie partagé, découverte en écrivant les
  /// tests de cette règle : `entropyBits` évalue le pool à partir des CLASSES
  /// présentes (26 pour les minuscules), pas des caractères réellement
  /// employés. `abababababab` était donc crédité de 56 bits alors qu'il n'en
  /// vaut qu'une douzaine — deux lettres alternées. Il franchissait le seuil.
  ///
  /// ⚠️ Le contrôle est posé ICI et non dans [PasswordStrengthService] : cette
  /// fonction de score alimente aussi l'indicateur de force et l'écran d'audit
  /// du coffre. En modifier le barème changerait rétroactivement le verdict
  /// rendu sur les entrées déjà enregistrées de l'utilisateur — un effet de
  /// bord sans rapport avec le sujet. La règle de création se durcit ; la
  /// mesure affichée reste ce qu'elle était.
  ///
  /// Cinq suffit à écarter les alternances et les alphabets minuscules sans
  /// gêner personne : la plus courte phrase acceptable de nos tests en compte
  /// déjà dix.
  static const int minDistinctChars = 5;

  /// Rend `null` si [pwd] est acceptable, sinon le motif du refus.
  ///
  /// L'ordre compte : on signale la longueur AVANT la faiblesse. Un mot de
  /// passe trop court est presque toujours aussi trop faible, et « il manque
  /// des caractères » est une consigne actionnable, là où « trop faible »
  /// laisse l'utilisateur deviner ce qu'on attend de lui.
  static PasswordRejection? check(String pwd) {
    if (pwd.length < minLength) return PasswordRejection.tooShort;
    if (pwd.split('').toSet().length < minDistinctChars) {
      return PasswordRejection.tooWeak;
    }
    if (PasswordStrengthService.score(pwd) < minScore) {
      return PasswordRejection.tooWeak;
    }
    return null;
  }
}
