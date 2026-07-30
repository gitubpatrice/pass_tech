import 'dart:math';
import 'package:flutter/material.dart';
import '../l10n/app_localizations.dart';

/// Service unifié de calcul de la force d'un mot de passe.
///
/// Utilise l'entropie de Shannon (log2(pool^len)) — méthode plus rigoureuse
/// que les heuristiques additives à base de char-class. Le score est
/// normalisé [0..1] sur la base de 80 bits = très fort.
///
/// Remplace 3 implémentations divergentes (setup_screen, generator_screen,
/// audit_screen) qui se basaient toutes sur les mêmes 4 RegExp char-class.
class PasswordStrengthService {
  PasswordStrengthService._();

  static final RegExp _upper = RegExp(r'[A-Z]');
  static final RegExp _lower = RegExp(r'[a-z]');
  static final RegExp _digit = RegExp(r'[0-9]');
  static final RegExp _symbol = RegExp(r'[^A-Za-z0-9]');

  /// Référence : 80 bits = très fort (score = 1.0).
  static const double _maxBits = 80.0;

  /// Score normalisé [0..1] basé sur l'entropie de Shannon.
  static double score(String pwd) {
    if (pwd.isEmpty) return 0;
    final bits = entropyBits(pwd);
    return (bits / _maxBits).clamp(0.0, 1.0);
  }

  /// Estime l'entropie en bits pour une chaîne donnée :
  /// log2(pool^len) = len * log2(pool), où pool est la somme des
  /// classes de caractères présentes (26+26+10+26).
  ///
  /// D7 v2.3.8 — pool symboles aligné à 26 (vs 32 auparavant) pour
  /// matcher le set effectif du générateur (`_syms` = 26 chars).
  /// Avant : entropie surestimée d'environ 16% pour les mots de passe
  /// générés (différence cohérente cross-écran maintenant).
  ///
  /// SEC F9 v2.5.2 — l'entropie se calcule désormais sur la longueur EFFECTIVE
  /// (voir [_effectiveLength]) et non sur la longueur brute, et un mot de passe
  /// reconnu comme courant est ramené à 0 bit.
  ///
  /// Avant : `len * log2(pool)` sans aucune pénalité de répétition, de suite ni
  /// de dictionnaire. Le plus petit pool non nul valant 10, un mot de passe de
  /// 10 caractères atteignait déjà 33,2 bits, soit un score de 0,415 — au-dessus
  /// du seuil de faiblesse de 0,35. La seconde branche de [isWeak] était donc
  /// ARITHMÉTIQUEMENT INATTEIGNABLE et `isWeak` se réduisait à `length < 10` :
  /// l'audit du coffre affichait « 0 mot de passe faible » sur un coffre rempli
  /// d'entrées trivialement devinables, et la porte de création acceptait
  /// `aaaaaaaaaaaa` en l'étiquetant « Fort ».
  static double entropyBits(String pwd) {
    if (pwd.isEmpty) return 0;
    if (isCommon(pwd)) return 0;
    var pool = 0;
    if (_upper.hasMatch(pwd)) pool += 26;
    if (_lower.hasMatch(pwd)) pool += 26;
    if (_digit.hasMatch(pwd)) pool += 10;
    if (_symbol.hasMatch(pwd)) pool += 26;
    if (pool == 0) return 0;
    return _effectiveLength(pwd) * (log(pool) / ln2);
  }

  /// Longueur « effective » : les suites triviales ne comptent pas pour leur
  /// longueur brute, car elles ne coûtent rien à deviner.
  ///
  /// On repère les segments où l'écart entre caractères consécutifs est
  /// constant et vaut 0 (répétition : `aaaa`), +1 (suite croissante : `1234`,
  /// `abcd`) ou -1 (suite décroissante : `4321`). Un segment de longueur `n`
  /// vaut `1 + ceil(log2(n))` caractères au lieu de `n` : deviner « un `a`
  /// répété 12 fois » coûte le prix d'un caractère plus celui de la longueur,
  /// pas celui de 12 caractères indépendants.
  static int _effectiveLength(String pwd) {
    var effective = 0;
    var i = 0;
    while (i < pwd.length) {
      var runLen = 1;
      if (i + 1 < pwd.length) {
        final delta = pwd.codeUnitAt(i + 1) - pwd.codeUnitAt(i);
        if (delta == 0 || delta == 1 || delta == -1) {
          var j = i + 1;
          while (j < pwd.length &&
              pwd.codeUnitAt(j) - pwd.codeUnitAt(j - 1) == delta) {
            j++;
          }
          runLen = j - i;
        }
      }
      effective += runLen == 1 ? 1 : 1 + (log(runLen) / ln2).ceil();
      i += runLen;
    }
    return effective;
  }

  /// Normalise les substitutions « l33t » et la casse, pour que `P@ssw0rd`
  /// soit reconnu au même titre que `password`.
  static String _deleet(String pwd) {
    const map = {
      '@': 'a',
      '4': 'a',
      '8': 'b',
      '(': 'c',
      '3': 'e',
      '6': 'g',
      '1': 'l',
      '!': 'i',
      '0': 'o',
      '5': 's',
      r'$': 's',
      '7': 't',
      '2': 'z',
    };
    final buf = StringBuffer();
    for (final ch in pwd.toLowerCase().split('')) {
      buf.write(map[ch] ?? ch);
    }
    return buf.toString();
  }

  /// Mots de passe et racines les plus courants, y compris francophones.
  /// Liste volontairement LOCALE : l'app n'a par conception aucune
  /// vérification en ligne du secret maître.
  static const _commonRoots = {
    'password',
    'passwd',
    'motdepasse',
    'secret',
    'azerty',
    'qwerty',
    'qwertz',
    'azertyuiop',
    'qwertyuiop',
    'asdfgh',
    'wxcvbn',
    'zxcvbn',
    'letmein',
    'welcome',
    'admin',
    'administrateur',
    'root',
    'toor',
    'login',
    'user',
    'utilisateur',
    'test',
    'guest',
    'invite',
    'bonjour',
    'salut',
    'coucou',
    'hello',
    'monkey',
    'dragon',
    'soleil',
    'chouchou',
    'doudou',
    'nicolas',
    'jetaime',
    'amour',
    'bisous',
    'chocolat',
    'football',
    'baseball',
    'superman',
    'batman',
    'starwars',
    'pokemon',
    'princesse',
    'princess',
    'sunshine',
    'iloveyou',
    'trustno',
    'freedom',
    'whatever',
    'master',
    'shadow',
    'michael',
    'jennifer',
    'thomas',
    'jordan',
    'hunter',
    'ranger',
    'liverpool',
    'marseille',
    'chelsea',
    'arsenal',
    'juventus',
    'barcelona',
    'realmadrid',
    'motorola',
    'samsung',
    'iphone',
    'android',
    'internet',
    'computer',
    'ordinateur',
    'maison',
    'famille',
    'vacances',
    'anniversaire',
  };

  /// True si [pwd] est un mot de passe courant, ou n'est qu'une racine
  /// courante ornée de chiffres / ponctuation (`Password123!`, `azerty2024`).
  ///
  /// Ne remplace pas une vraie liste de fuites — c'est un filet minimal
  /// destiné à rendre [isWeak] capable de signaler les cas les plus grossiers,
  /// que l'ancienne formule d'entropie pure laissait tous passer.
  static bool isCommon(String pwd) {
    final lower = pwd.toLowerCase();
    // Habillage typique : chiffres et ponctuation en tête ou en queue
    // (`Password123!`, `azerty2024`, `!!!admin`). Il faut les retirer AVANT
    // la normalisation l33t — sinon `_deleet` convertit ces chiffres en
    // lettres (`123` → `lze`), ce qui gonfle le cœur du mot et empêche de
    // reconnaître la racine.
    final trimmed = lower.replaceAll(RegExp(r'^[\d\W_]+|[\d\W_]+$'), '');
    // On teste les deux lectures : avec habillage (pour les l33t internes
    // comme `P@ssw0rd`) et sans (pour les suffixes numériques).
    for (final candidate in {_deleet(lower), _deleet(trimmed)}) {
      final core = candidate.replaceAll(RegExp(r'[^a-z]'), '');
      if (core.isEmpty) continue;
      if (_commonRoots.contains(core)) return true;
      for (final root in _commonRoots) {
        // Racine d'au moins 5 lettres pesant au moins la moitié du mot de
        // passe : `passwordxyz` compte, `chaisenuageturbine` non.
        if (root.length >= 5 &&
            core.contains(root) &&
            root.length * 2 >= core.length) {
          return true;
        }
      }
    }
    return false;
  }

  /// Mappe le score normalisé sur 4 niveaux : weak / medium / strong / veryStrong.
  /// Seuils : 0.35 / 0.65 / 0.85.
  static String label(double s, AppLocalizations t) {
    if (s < 0.35) return t.strengthWeak;
    if (s < 0.65) return t.strengthMedium;
    if (s < 0.85) return t.strengthStrong;
    return t.strengthVeryStrong;
  }

  /// Couleur associée au score (rouge / orange / jaune / vert).
  static Color color(double s) {
    if (s < 0.35) return const Color(0xFFE53935);
    if (s < 0.65) return const Color(0xFFFF7043);
    if (s < 0.85) return const Color(0xFFFDD835);
    return const Color(0xFF43A047);
  }

  /// True si le mot de passe est faible (score < 0.35, ou mot de passe courant).
  ///
  /// SEC F9 v2.5.2 — la seconde branche est désormais réellement atteignable :
  /// [entropyBits] pénalise répétitions, suites et mots de passe courants, donc
  /// `aaaaaaaaaaaa`, `1234567890` et `motdepasse12` sont signalés alors qu'ils
  /// passaient tous pour « Fort ».
  static bool isWeak(String pwd) {
    if (pwd.length < 10) return true;
    if (isCommon(pwd)) return true;
    return score(pwd) < 0.35;
  }
}
