import '../l10n/app_localizations.dart';
import '../services/vault_service.dart' show BiometricPromptText;

/// Textes de l'invite biométrique système, dans la langue de l'application.
///
/// Un seul point de construction, à dessein : les deux chemins qui font
/// apparaître l'invite — l'activation depuis les réglages et le
/// déverrouillage — doivent afficher exactement le même texte. Les laisser
/// construire chacun le leur est précisément la façon dont l'un des deux finit
/// par diverger sans que rien ne le signale.
BiometricPromptText biometricPromptTextOf(AppLocalizations t) =>
    BiometricPromptText(
      subtitle: t.biometricPromptSubtitle,
      cancel: t.actionCancel,
    );
