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
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('checkIntegrity');
      if (res == null) return const IntegrityStatus();
      return IntegrityStatus(
        rooted:     res['rooted']     as bool? ?? false,
        emulator:   res['emulator']   as bool? ?? false,
        debuggable: res['debuggable'] as bool? ?? false,
        debugger:   res['debugger']   as bool? ?? false,
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

  /// Liste des problèmes détectés en français pour affichage UI.
  List<String> get issues {
    final list = <String>[];
    if (rooted)     list.add('Appareil rooté détecté');
    if (debugger)   list.add('Debugger attaché');
    if (debuggable) list.add('App en mode debug');
    if (emulator)   list.add('Émulateur détecté');
    return list;
  }
}
