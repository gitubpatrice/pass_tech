import 'dart:io';
import 'package:flutter/services.dart';

/// Détection RASP basique : root, émulateur, debugger.
/// Best-effort — un attaquant déterminé contourne ces checks (Magisk Hide,
/// Frida bypass). Sert d'avertissement utilisateur.
class IntegrityService {
  static const _channel = MethodChannel('com.passtech.pass_tech/rasp');

  /// État d'intégrité de l'appareil. Tout `false` = environnement sain.
  static Future<IntegrityStatus> check() async {
    if (!Platform.isAndroid) return const IntegrityStatus();
    try {
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'checkIntegrity',
      );
      if (res == null) return const IntegrityStatus();
      return IntegrityStatus(
        rooted: res['rooted'] as bool? ?? false,
        emulator: res['emulator'] as bool? ?? false,
        debuggable: res['debuggable'] as bool? ?? false,
        debugger: res['debugger'] as bool? ?? false,
      );
    } catch (_) {
      return const IntegrityStatus();
    }
  }
}

class IntegrityStatus {
  final bool rooted;
  final bool emulator;
  final bool debuggable;
  final bool debugger;
  const IntegrityStatus({
    this.rooted = false,
    this.emulator = false,
    this.debuggable = false,
    this.debugger = false,
  });

  bool get hasIssue => rooted || emulator || debuggable || debugger;

  /// Problèmes détectés, sous forme d'identifiants STABLES.
  ///
  /// v2.7.0 — ce getter rendait auparavant des libellés français tout faits,
  /// que `unlock_screen` affichait tels quels : un utilisateur anglophone
  /// lisait « Appareil rooté détecté ». Le libellé est désormais choisi par
  /// l'écran, qui a la locale ; le service ne rend que le fait.
  ///
  /// L'identifiant doit rester stable pour une seconde raison, moins visible :
  /// `unlock_screen._checkIntegrity` en dérive l'empreinte qui évite de
  /// ré-avertir à chaque ouverture. Une empreinte bâtie sur du texte affiché
  /// aurait changé au premier changement de langue, et l'avertissement serait
  /// reparti alors que rien n'avait bougé sur l'appareil.
  List<IntegrityIssue> get issues {
    final list = <IntegrityIssue>[];
    if (rooted) list.add(IntegrityIssue.rooted);
    if (debugger) list.add(IntegrityIssue.debuggerAttached);
    if (debuggable) list.add(IntegrityIssue.debuggableBuild);
    if (emulator) list.add(IntegrityIssue.emulator);
    return list;
  }
}

/// Un problème d'intégrité, indépendant de la langue.
/// `name` sert d'identifiant persistant — ne pas renommer ces valeurs sans
/// accepter un avertissement supplémentaire chez les utilisateurs existants.
enum IntegrityIssue { rooted, debuggerAttached, debuggableBuild, emulator }
