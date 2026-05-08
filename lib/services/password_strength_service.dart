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
  /// classes de caractères présentes (26+26+10+~32).
  static double entropyBits(String pwd) {
    if (pwd.isEmpty) return 0;
    var pool = 0;
    if (_upper.hasMatch(pwd)) pool += 26;
    if (_lower.hasMatch(pwd)) pool += 26;
    if (_digit.hasMatch(pwd)) pool += 10;
    if (_symbol.hasMatch(pwd)) pool += 32;
    if (pool == 0) return 0;
    return pwd.length * (log(pool) / ln2);
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

  /// True si le mot de passe est faible (score < 0.35).
  /// Équivalent sémantique de l'ancien `variety < 3` (audit_screen).
  static bool isWeak(String pwd) {
    if (pwd.length < 10) return true;
    return score(pwd) < 0.35;
  }
}
