import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../l10n/app_localizations.dart';

/// Champ texte de type "mot de passe" avec bouton oeil intégré.
///
/// Encapsule le pattern `obscureText + IconButton(visibility/visibility_off)`
/// dupliqué 9× dans l'app. Gère l'état `_show` en interne.
///
/// Pour des champs spécialisés (TOTP, CVV, PIN) qui nécessitent des actions
/// supplémentaires (scan QR, formatters numériques, etc.), passer
/// `extraSuffixIcons`, `inputFormatters`, et personnaliser `keyboardType` /
/// `prefixIcon`.
class PasswordTextField extends StatefulWidget {
  final TextEditingController controller;
  final String labelText;
  final String? helperText;
  final String? hintText;
  final String? errorText;
  final Widget? prefixIcon;
  final TextInputType keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final List<Widget>? extraSuffixIcons;
  final String? showTooltip;
  final String? hideTooltip;
  final bool autofocus;
  final bool showPrefixIcon;

  const PasswordTextField({
    super.key,
    required this.controller,
    required this.labelText,
    this.helperText,
    this.hintText,
    this.errorText,
    this.prefixIcon,
    this.keyboardType = TextInputType.visiblePassword,
    this.inputFormatters,
    this.onChanged,
    this.onSubmitted,
    this.extraSuffixIcons,
    this.showTooltip,
    this.hideTooltip,
    this.autofocus = false,
    this.showPrefixIcon = true,
  });

  @override
  State<PasswordTextField> createState() => _PasswordTextFieldState();
}

class _PasswordTextFieldState extends State<PasswordTextField> {
  bool _show = false;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final eye = IconButton(
      icon: Icon(_show ? Icons.visibility_off : Icons.visibility, size: 20),
      tooltip: _show
          ? (widget.hideTooltip ?? t.unlockHidePassword)
          : (widget.showTooltip ?? t.unlockShowPassword),
      onPressed: () => setState(() => _show = !_show),
    );

    final extras = widget.extraSuffixIcons;
    final Widget suffix = (extras == null || extras.isEmpty)
        ? eye
        : Row(mainAxisSize: MainAxisSize.min, children: [eye, ...extras]);

    final defaultPrefix = const Icon(Icons.lock_outline, size: 20);
    final Widget? resolvedPrefix = widget.showPrefixIcon
        ? (widget.prefixIcon ?? defaultPrefix)
        : null;

    return TextField(
      controller: widget.controller,
      obscureText: !_show,
      autofocus: widget.autofocus,
      enableSuggestions: false,
      autocorrect: false,
      // U1 v2.4.3 — bloque le service Autofill Android pour qu'il ne tente
      // pas de capturer / proposer la valeur. Pour un master password ou un
      // password applicatif, la collecte par un Autofill tiers est un risque
      // de fuite cross-app non maîtrisé.
      autofillHints: const <String>[],
      // U1 v2.4.3 — désactive la sélection et la copie quand la valeur est
      // masquée. Empêche un long-press → "Tout sélectionner" → "Copier"
      // qui exposerait le password en clair au clipboard (capté par les
      // clipboard managers tiers sur Android 13-).
      enableInteractiveSelection: _show,
      keyboardType: widget.keyboardType,
      inputFormatters: widget.inputFormatters,
      onChanged: widget.onChanged,
      onSubmitted: widget.onSubmitted,
      decoration: InputDecoration(
        labelText: widget.labelText,
        helperText: widget.helperText,
        hintText: widget.hintText,
        errorText: widget.errorText,
        prefixIcon: resolvedPrefix,
        suffixIcon: suffix,
        border: const OutlineInputBorder(),
      ),
    );
  }
}
