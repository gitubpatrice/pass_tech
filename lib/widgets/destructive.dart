import 'package:flutter/material.dart';

/// Rouge des actions destructives — repris du logo Pass Tech.
///
/// Volontairement IDENTIQUE en thème clair et sombre. Une action irréversible
/// doit se reconnaître au premier coup d'œil, quel que soit le thème : la
/// laisser suivre `ColorScheme.error` donnerait un rouge pâle en clair
/// (`errorContainer`) et un rouge clair en sombre, deux teintes que l'œil ne
/// relie pas immédiatement au danger.
///
/// Contraste avec [kDestructiveOn] ≈ 5,5:1 — conforme WCAG AA pour du texte
/// normal (seuil 4,5:1).
const Color kDestructiveRed = Color(0xFFC62828);

/// Couleur de premier plan sur [kDestructiveRed] : blanc pur, pour le contraste.
const Color kDestructiveOn = Colors.white;

/// Bouton de confirmation d'une action IRRÉVERSIBLE : fond rouge plein, texte
/// blanc.
///
/// À utiliser pour TOUTE confirmation destructive — suppression d'entrée, de
/// coffre, de leurre, désactivation d'Héritage, export en clair. Passer par ce
/// widget plutôt que de recopier un `FilledButton.styleFrom` garantit que les
/// boîtes de dialogue ne dérivent pas les unes des autres au fil des ajouts.
///
/// Remplace l'ancien `FilledButton.tonal` + `errorContainer`, dont le fond
/// pâle et le texte sombre se lisaient mal comme un avertissement.
class DestructiveButton extends StatelessWidget {
  const DestructiveButton({
    super.key,
    required this.onPressed,
    required this.label,
    this.autofocus = false,
    this.icon,
  });

  final VoidCallback? onPressed;
  final String label;

  /// Laisser à `false` sur les dialogues destructifs : l'autofocus doit rester
  /// sur « Annuler », pour qu'une validation au clavier n'efface rien.
  final bool autofocus;

  /// Icône optionnelle (ex. `Icons.delete_forever_outlined`).
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final style = FilledButton.styleFrom(
      backgroundColor: kDestructiveRed,
      foregroundColor: kDestructiveOn,
      disabledBackgroundColor: kDestructiveRed.withValues(alpha: 0.38),
      disabledForegroundColor: kDestructiveOn.withValues(alpha: 0.60),
    );
    if (icon == null) {
      return FilledButton(
        onPressed: onPressed,
        autofocus: autofocus,
        style: style,
        child: Text(label),
      );
    }
    return FilledButton.icon(
      onPressed: onPressed,
      autofocus: autofocus,
      style: style,
      icon: Icon(icon),
      label: Text(label),
    );
  }
}
