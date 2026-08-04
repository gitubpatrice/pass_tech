import 'package:flutter/material.dart';

/// Helpers SnackBar uniformes (QW3 v2.4.0).
///
/// Avant : ~28 sites `messenger.showSnackBar(SnackBar(content: Text(...)))`
/// dispersés, sans `behavior: SnackBarBehavior.floating`, durations inégales,
/// overlap fréquent avec FAB sur petits écrans (S9 360dp).
///
/// Aligné sur les helpers Notes Tech v1.0.6 / Read Files Tech v2.12.1.
/// `messenger` doit toujours être capturé AVANT un `await` (anti
/// `Looking up a deactivated widget's ancestor` post-dispose).
abstract final class SnackUtils {
  SnackUtils._();

  /// Affiche un snack flottant neutre (info/succès simple).
  static void showInfo(
    ScaffoldMessengerState messenger,
    String text, {
    Duration duration = const Duration(seconds: 3),
    SnackBarAction? action,
  }) {
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(text),
        behavior: SnackBarBehavior.floating,
        duration: duration,
        // `SnackBar.persist` vaut par défaut `action != null` : sans ce flag,
        // passer une [action] rendrait le bandeau permanent et [duration]
        // inopérante (cf. snack_bar.dart / scaffold.dart).
        persist: false,
        action: action,
      ),
    );
  }

  /// Affiche un snack flottant d'erreur (couleur `cs.errorContainer`).
  /// Le messager DOIT être capturé avant l'await déclencheur.
  static void showError(
    BuildContext context,
    ScaffoldMessengerState messenger,
    String text, {
    Duration duration = const Duration(seconds: 4),
  }) {
    final cs = Theme.of(context).colorScheme;
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(text, style: TextStyle(color: cs.onErrorContainer)),
        backgroundColor: cs.errorContainer,
        behavior: SnackBarBehavior.floating,
        duration: duration,
      ),
    );
  }

  /// Affiche un snack flottant de succès avec icône check.
  static void showSuccess(
    BuildContext context,
    ScaffoldMessengerState messenger,
    String text, {
    Duration duration = const Duration(seconds: 2),
  }) {
    final cs = Theme.of(context).colorScheme;
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Row(
          children: [
            // UI 2026-08-04 — `cs.onPrimary` et non `cs.primary` : depuis que
            // le fond du bandeau EST `cs.primary`, une icône de cette même
            // couleur serait invisible.
            Icon(Icons.check_circle, color: cs.onPrimary, size: 18),
            const SizedBox(width: 8),
            Expanded(child: Text(text)),
          ],
        ),
        behavior: SnackBarBehavior.floating,
        duration: duration,
      ),
    );
  }
}
