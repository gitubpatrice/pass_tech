import 'dart:async';
import 'package:flutter/material.dart';
// ignore: unnecessary_import
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart' show HapticFeedback;
import 'package:intl/intl.dart';
import '../l10n/app_localizations.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/anti_phishing_service.dart';
import '../services/clipboard_service.dart';
import '../services/totp_service.dart';
import '../services/vault_service.dart';
import 'entry_edit_screen.dart';

class EntryDetailScreen extends StatefulWidget {
  final Entry entry;
  final VoidCallback onChanged;
  const EntryDetailScreen({
    super.key,
    required this.entry,
    required this.onChanged,
  });

  @override
  State<EntryDetailScreen> createState() => _EntryDetailScreenState();
}

class _EntryDetailScreenState extends State<EntryDetailScreen> {
  late Entry _entry;
  bool _showPassword = false;
  bool _showCardNumber = false;
  bool _showCvv = false;
  bool _showPin = false;

  @override
  void initState() {
    super.initState();
    _entry = widget.entry;
  }

  Future<void> _copy(
    String text,
    String label, {
    bool sensitive = false,
  }) async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);

    // Anti-phishing : sur les champs sensibles (mot de passe, 2FA), on vérifie
    // que le navigateur frontal est bien sur le bon domaine avant de copier.
    if (sensitive && _entry.url.isNotEmpty) {
      final svc = AntiPhishingService();
      final check = await svc.check(_entry.url);
      if (!mounted) return;
      if (check.verdict == PhishingVerdict.typosquatting ||
          check.verdict == PhishingVerdict.mismatch) {
        final proceed = await _showPhishingDialog(check);
        if (proceed != true) return;
      } else if (check.verdict == PhishingVerdict.unknown &&
          await svc.isEnabled) {
        // L'utilisateur a activé l'anti-phishing mais l'AS n'a pas pu lire
        // un domaine (AS désactivée, navigateur non supporté, domaine
        // périmé > 60s). On copie mais on prévient — pour éviter le faux
        // sentiment de sécurité.
        if (!mounted) return;
        messenger.showSnackBar(
          SnackBar(
            content: Text(t.entryDetailAntiPhishingInactive),
            duration: const Duration(seconds: 4),
            action: SnackBarAction(
              label: t.entryDetailAntiPhishingSettings,
              onPressed: () => svc.openAccessibilitySettings(),
            ),
          ),
        );
      }
    }

    await ClipboardService.copyWithAutoClear(text);
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          t.entryDetailCopiedSnack(label, ClipboardService.clearAfterSeconds),
        ),
        duration: const Duration(seconds: 3),
      ),
    );
    if (mounted) {
      // ignore: deprecated_member_use — sendAnnouncement requires FlutterView API non-stable.
      SemanticsService.announce(t.snackbarCopied, Directionality.of(context));
    }
  }

  Future<bool?> _showPhishingDialog(PhishingCheck check) {
    final isTypo = check.verdict == PhishingVerdict.typosquatting;
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.warning_amber_rounded, size: 40, color: cs.error),
        title: Text(
          isTypo
              ? t.entryDetailPhishingTypoTitle
              : t.entryDetailPhishingMismatchTitle,
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isTypo
                  ? t.entryDetailPhishingTypoBody
                  : t.entryDetailPhishingMismatchBody,
            ),
            const SizedBox(height: 12),
            _domainRow(
              t.entryDetailPhishingExpected,
              check.expectedDomain ?? '—',
              cs.primary,
            ),
            const SizedBox(height: 4),
            _domainRow(
              t.entryDetailPhishingActive,
              check.activeDomain ?? '—',
              cs.error,
            ),
            if (check.distance != null) ...[
              const SizedBox(height: 8),
              Text(
                t.entryDetailPhishingDistance(check.distance!),
                style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(isTypo ? t.actionCancel : t.entryDetailPhishingClose),
          ),
          // Sur typosquatting, on garde l'override (peut être un faux positif).
          // Sur mismatch sévère, on retire le bouton — l'utilisateur doit
          // changer de page ou désactiver l'anti-phishing dans Réglages.
          if (isTypo)
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              style: TextButton.styleFrom(foregroundColor: cs.error),
              child: Text(t.entryDetailPhishingCopyAnyway),
            ),
        ],
      ),
    );
  }

  Widget _domainRow(String label, String value, Color color) => Row(
    children: [
      SizedBox(
        width: 64,
        child: Text(
          label,
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
        ),
      ),
      Expanded(
        child: Text(
          value,
          style: TextStyle(
            fontFamily: 'monospace',
            fontSize: 13,
            color: color,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    ],
  );

  Future<void> _toggleFav() async {
    final updated = _entry.copyWith(isFavorite: !_entry.isFavorite);
    await VaultService().updateEntry(updated);
    setState(() => _entry = updated);
    widget.onChanged();
  }

  Future<void> _delete() async {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.entryDetailDeleteTitle),
        content: Text(t.entryDetailDeleteConfirm(_entry.title)),
        actions: [
          // U2 v2.4.4 — autofocus sur Cancel + bouton destructif via
          // FilledButton.tonal cs.errorContainer (aligné pattern home U10
          // v2.4.3 et Notes Tech v1.0.9 U3).
          TextButton(
            autofocus: true,
            onPressed: () => nav.pop(false),
            child: Text(t.actionCancel),
          ),
          FilledButton.tonal(
            onPressed: () => nav.pop(true),
            style: FilledButton.styleFrom(
              backgroundColor: cs.errorContainer,
              foregroundColor: cs.onErrorContainer,
            ),
            child: Text(t.actionDelete),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    // U9 v2.4.4 — feedback haptique sur action destructive.
    await HapticFeedback.mediumImpact();
    await VaultService().deleteEntry(_entry.id);
    widget.onChanged();
    if (mounted) nav.pop();
  }

  Future<void> _edit() async {
    final updated = await Navigator.push<Entry>(
      context,
      MaterialPageRoute(
        builder: (_) =>
            EntryEditScreen(entry: _entry, onSaved: widget.onChanged),
      ),
    );
    if (updated != null && mounted) setState(() => _entry = updated);
  }

  String _entryTypeLabelLocalized(EntryType type, AppLocalizations t) {
    switch (type) {
      case EntryType.password:
        return t.entryTypePassword;
      case EntryType.note:
        return t.entryTypeNote;
      case EntryType.card:
        return t.entryTypeCard;
    }
  }

  // P2.2 v2.4.3 — DateFormat hissé static : entry_detail rebuild fréquent
  // (TOTPCard force `setState` 1× / s pour la barre de progression OTP).
  // Recréer un DateFormat ICU à chaque build = allocation+lookup l10n
  // gaspillée.
  static final _dfDMYHm = DateFormat('dd/MM/yyyy HH:mm');

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final catColor = categoryColor(_entry.category);
    final fmt = _dfDMYHm;

    return Scaffold(
      appBar: AppBar(
        title: Text(_entry.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            icon: Icon(
              _entry.isFavorite ? Icons.star : Icons.star_border,
              color: _entry.isFavorite ? Colors.amber.shade400 : null,
            ),
            tooltip: t.entryDetailFavoriteTooltip,
            onPressed: _toggleFav,
          ),
          IconButton(
            icon: const Icon(Icons.edit_outlined),
            tooltip: t.entryDetailEditTooltip,
            onPressed: _edit,
          ),
          IconButton(
            icon: Icon(Icons.delete_outline, color: cs.error),
            tooltip: t.entryDetailDeleteTooltip,
            onPressed: _delete,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Header
          Center(
            child: Column(
              children: [
                Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    color: catColor.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Icon(
                    _typeIcon(_entry.type),
                    size: 32,
                    color: catColor,
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  alignment: WrapAlignment.center,
                  children: [
                    _badge(_entryTypeLabelLocalized(_entry.type, t), catColor),
                    _badge(
                      categoryLabel(_entry.category, t),
                      cs.onSurfaceVariant,
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),

          if (_entry.type == EntryType.password) ..._buildPasswordView(),
          if (_entry.type == EntryType.note) ..._buildNoteView(),
          if (_entry.type == EntryType.card) ..._buildCardView(),

          const Divider(),
          const SizedBox(height: 6),
          Text(
            t.entryDetailCreatedAt(fmt.format(_entry.createdAt)),
            style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
          ),
          Text(
            t.entryDetailUpdatedAt(fmt.format(_entry.updatedAt)),
            style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
          ),
        ],
      ),
    );
  }

  IconData _typeIcon(EntryType t) {
    switch (t) {
      case EntryType.password:
        return categoryIcon(_entry.category);
      case EntryType.note:
        return Icons.sticky_note_2_outlined;
      case EntryType.card:
        return Icons.credit_card;
    }
  }

  List<Widget> _buildPasswordView() {
    final t = AppLocalizations.of(context);
    return [
      _Field(
        label: t.entryDetailFieldUsername,
        value: _entry.username.isEmpty ? '—' : _entry.username,
        onCopy: _entry.username.isEmpty
            ? null
            : () => _copy(_entry.username, t.entryDetailFieldUsername),
      ),
      const SizedBox(height: 10),

      _PasswordField(
        password: _entry.password,
        label: t.entryDetailFieldPassword,
        copyTooltip: t.entryDetailCopyTooltip,
        show: _showPassword,
        onToggle: () => setState(() => _showPassword = !_showPassword),
        onCopy: () =>
            _copy(_entry.password, t.entryDetailFieldPassword, sensitive: true),
      ),
      const SizedBox(height: 10),

      if (_entry.totpSecret.isNotEmpty) ...[
        _TotpCard(
          secret: _entry.totpSecret,
          label: t.entryDetailField2faTotp,
          copyTooltip: t.entryDetailCopyTooltip,
          onCopy: (code) =>
              _copy(code, t.entryDetailField2faCode, sensitive: true),
        ),
        const SizedBox(height: 10),
      ],

      if (_entry.url.isNotEmpty) ...[
        _Field(
          label: t.entryDetailFieldUrl,
          value: _entry.url,
          onCopy: () => _copy(_entry.url, t.entryDetailFieldUrl),
        ),
        const SizedBox(height: 10),
      ],

      if (_entry.notes.isNotEmpty) ...[
        _Field(label: t.entryDetailFieldNotes, value: _entry.notes),
        const SizedBox(height: 10),
      ],
    ];
  }

  List<Widget> _buildNoteView() {
    final t = AppLocalizations.of(context);
    return [
      Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      t.entryDetailFieldContent,
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.4,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.copy, size: 18),
                    tooltip: t.entryDetailCopyNotesTooltip,
                    onPressed: _entry.notes.isEmpty
                        ? null
                        : () => _copy(_entry.notes, t.entryDetailFieldContent),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              SelectableText(
                _entry.notes.isEmpty ? t.entryDetailNoteEmpty : _entry.notes,
                style: const TextStyle(fontSize: 14, height: 1.4),
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 10),
    ];
  }

  List<Widget> _buildCardView() {
    final t = AppLocalizations.of(context);
    return [
      _CardVisual(entry: _entry, showNumber: _showCardNumber),
      const SizedBox(height: 12),

      if (_entry.cardholderName.isNotEmpty)
        _Field(
          label: t.entryDetailFieldHolder,
          value: _entry.cardholderName,
          onCopy: () => _copy(_entry.cardholderName, t.entryDetailFieldHolder),
        ),
      if (_entry.cardholderName.isNotEmpty) const SizedBox(height: 10),

      _MaskableField(
        label: t.entryDetailFieldNumber,
        value: _entry.cardNumber,
        copyTooltip: t.entryDetailCopyTooltip,
        formattedValue: _formatCardNumber(_entry.cardNumber),
        maskedValue: _maskCardNumber(_entry.cardNumber),
        show: _showCardNumber,
        onToggle: () => setState(() => _showCardNumber = !_showCardNumber),
        onCopy: () =>
            _copy(_entry.cardNumber, t.entryDetailFieldCardNumberCopy),
        monospace: true,
      ),
      const SizedBox(height: 10),

      Row(
        children: [
          if (_entry.cardExpiry.isNotEmpty)
            Expanded(
              child: _Field(
                label: t.entryDetailFieldExpiry,
                value: _entry.cardExpiry,
                onCopy: () =>
                    _copy(_entry.cardExpiry, t.entryDetailFieldExpiry),
              ),
            ),
          if (_entry.cardExpiry.isNotEmpty && _entry.cardCvv.isNotEmpty)
            const SizedBox(width: 10),
          if (_entry.cardCvv.isNotEmpty)
            Expanded(
              child: _MaskableField(
                label: t.entryDetailFieldCvv,
                value: _entry.cardCvv,
                copyTooltip: t.entryDetailCopyTooltip,
                formattedValue: _entry.cardCvv,
                maskedValue: '•' * _entry.cardCvv.length,
                show: _showCvv,
                onToggle: () => setState(() => _showCvv = !_showCvv),
                onCopy: () => _copy(_entry.cardCvv, t.entryDetailFieldCvv),
              ),
            ),
        ],
      ),
      const SizedBox(height: 10),

      if (_entry.cardPin.isNotEmpty) ...[
        _MaskableField(
          label: t.entryDetailFieldPin,
          value: _entry.cardPin,
          copyTooltip: t.entryDetailCopyTooltip,
          formattedValue: _entry.cardPin,
          maskedValue: '•' * _entry.cardPin.length,
          show: _showPin,
          onToggle: () => setState(() => _showPin = !_showPin),
          onCopy: () => _copy(_entry.cardPin, t.entryDetailFieldPinCopy),
        ),
        const SizedBox(height: 10),
      ],

      if (_entry.cardIssuer.isNotEmpty) ...[
        _Field(label: t.entryDetailFieldIssuer, value: _entry.cardIssuer),
        const SizedBox(height: 10),
      ],

      if (_entry.notes.isNotEmpty) ...[
        _Field(label: t.entryDetailFieldNotes, value: _entry.notes),
        const SizedBox(height: 10),
      ],
    ];
  }

  Widget _badge(String text, Color color) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      text,
      style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w600),
    ),
  );

  static String _formatCardNumber(String n) {
    final clean = n.replaceAll(' ', '');
    final buf = StringBuffer();
    for (int i = 0; i < clean.length; i++) {
      if (i > 0 && i % 4 == 0) buf.write(' ');
      buf.write(clean[i]);
    }
    return buf.toString();
  }

  static String _maskCardNumber(String n) {
    final clean = n.replaceAll(' ', '');
    if (clean.length <= 4) return clean;
    final last4 = clean.substring(clean.length - 4);
    return '•••• •••• •••• $last4';
  }
}

class _Field extends StatelessWidget {
  final String label;
  final String value;
  final VoidCallback? onCopy;
  const _Field({required this.label, required this.value, this.onCopy});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 11,
                      color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                    ),
                  ),
                  const SizedBox(height: 3),
                  SelectableText(value, style: const TextStyle(fontSize: 14)),
                ],
              ),
            ),
            if (onCopy != null)
              IconButton(
                icon: const Icon(Icons.copy, size: 18),
                onPressed: onCopy,
                tooltip: t.entryDetailCopyTooltip,
              ),
          ],
        ),
      ),
    );
  }
}

class _PasswordField extends StatelessWidget {
  final String password;
  final String label;
  final String copyTooltip;
  final bool show;
  final VoidCallback onToggle;
  final VoidCallback onCopy;
  const _PasswordField({
    required this.password,
    required this.label,
    required this.copyTooltip,
    required this.show,
    required this.onToggle,
    required this.onCopy,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 11,
                      color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    // QW11 v2.4.0 — longueur réelle (clamp 8..24) au lieu
                    // de 12 fixe : aligne `_PasswordField` sur `_MaskableField`
                    // et évite la fuite faible "ce password fait 12 chars".
                    show ? password : '•' * password.length.clamp(8, 24),
                    style: TextStyle(
                      fontSize: 14,
                      letterSpacing: show ? 0.5 : 2,
                      fontFamily: show ? 'monospace' : null,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              icon: Icon(
                show ? Icons.visibility_off : Icons.visibility,
                size: 18,
              ),
              tooltip: t.entryDetailToggleVisibility,
              onPressed: onToggle,
            ),
            IconButton(
              icon: const Icon(Icons.copy, size: 18),
              onPressed: onCopy,
              tooltip: copyTooltip,
            ),
          ],
        ),
      ),
    );
  }
}

class _MaskableField extends StatelessWidget {
  final String label;
  final String value;
  final String formattedValue;
  final String maskedValue;
  final String? copyTooltip;
  final bool show;
  final VoidCallback onToggle;
  final VoidCallback onCopy;
  final bool monospace;
  const _MaskableField({
    required this.label,
    required this.value,
    required this.formattedValue,
    required this.maskedValue,
    required this.show,
    required this.onToggle,
    required this.onCopy,
    this.copyTooltip,
    this.monospace = false,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 11,
                      color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    show ? formattedValue : maskedValue,
                    style: TextStyle(
                      fontSize: 14,
                      fontFamily: monospace ? 'monospace' : null,
                      letterSpacing: monospace ? 1.5 : 0.5,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              icon: Icon(
                show ? Icons.visibility_off : Icons.visibility,
                size: 18,
              ),
              tooltip: t.entryDetailToggleVisibility,
              onPressed: onToggle,
            ),
            IconButton(
              icon: const Icon(Icons.copy, size: 18),
              onPressed: onCopy,
              tooltip: copyTooltip,
            ),
          ],
        ),
      ),
    );
  }
}

class _TotpCard extends StatefulWidget {
  final String secret;
  final String label;
  final String copyTooltip;
  final void Function(String code) onCopy;
  const _TotpCard({
    required this.secret,
    required this.label,
    required this.copyTooltip,
    required this.onCopy,
  });

  @override
  State<_TotpCard> createState() => _TotpCardState();
}

class _TotpCardState extends State<_TotpCard> {
  Timer? _timer;
  // F7 v2.4.4 — TOTP masqué par défaut. Avant : code affiché en clair en
  // permanence dans la card → shoulder-surfing trivial sur un détail
  // d'entrée bancaire ouvert. Aligné sur le pattern `_PasswordField` qui
  // exige `show=true` pour révéler.
  bool _showTotp = false;

  // P3 v2.4.4 — cache du code TOTP. Avant : `TotpService.generateCode` (HMAC-
  // SHA1) recalculé chaque seconde alors que le code change toutes les 30s.
  // Désormais on cache `(code, validUntilEpoch)` et on ne recalcule que
  // lorsque la fenêtre courante expire. Le rebuild 1Hz du countdown reste
  // mais le calcul crypto ne tourne plus que tous les 30s.
  String? _cachedCode;
  int _cachedValidUntilMs = 0;

  String _currentCode() {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (_cachedCode != null && now < _cachedValidUntilMs) {
      return _cachedCode!;
    }
    _cachedCode = TotpService.generateCode(widget.secret);
    // Fenêtre TOTP : 30s, alignée sur l'epoch (now ~/ 30 * 30).
    final periodMs = 30 * 1000;
    _cachedValidUntilMs = ((now ~/ periodMs) + 1) * periodMs;
    return _cachedCode!;
  }

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final code = _currentCode();
    final remaining = TotpService.secondsRemaining();
    final urgent = remaining <= 5;
    final ringColor = urgent ? cs.error : cs.primary;
    final displayed = _showTotp ? code : '••• •••';

    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
        child: Row(
          children: [
            // Countdown ring
            SizedBox(
              width: 42,
              height: 42,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox(
                    width: 42,
                    height: 42,
                    child: CircularProgressIndicator(
                      value: remaining / 30,
                      strokeWidth: 3,
                      backgroundColor: cs.surfaceContainerHighest,
                      valueColor: AlwaysStoppedAnimation(ringColor),
                    ),
                  ),
                  Text(
                    '$remaining',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: ringColor,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.label,
                    style: TextStyle(
                      fontSize: 11,
                      color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    displayed,
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 22,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 3,
                      color: _showTotp ? ringColor : cs.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              icon: Icon(
                _showTotp ? Icons.visibility_off : Icons.visibility,
                size: 18,
              ),
              onPressed: () => setState(() => _showTotp = !_showTotp),
              tooltip: t.entryDetailToggleVisibility,
            ),
            IconButton(
              icon: const Icon(Icons.copy, size: 18),
              onPressed: () => widget.onCopy(code.replaceAll(' ', '')),
              tooltip: widget.copyTooltip,
            ),
          ],
        ),
      ),
    );
  }
}

class _CardVisual extends StatelessWidget {
  final Entry entry;
  final bool showNumber;
  const _CardVisual({required this.entry, required this.showNumber});

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final clean = entry.cardNumber.replaceAll(' ', '');
    final masked = clean.length > 4
        ? '•••• •••• •••• ${clean.substring(clean.length - 4)}'
        : '••••';
    final formatted = StringBuffer();
    for (int i = 0; i < clean.length; i++) {
      if (i > 0 && i % 4 == 0) formatted.write(' ');
      formatted.write(clean[i]);
    }

    return Container(
      height: 180,
      decoration: BoxDecoration(
        // v2.5.0 (F4b) — gradient hardcodé volontairement conservé : c'est
        // le rendu visuel d'une carte bancaire physique (bleu → violet),
        // pas un statut sémantique. Mapper sur cs.primary/cs.tertiary
        // dénaturerait l'effet "carte de crédit" attendu par l'utilisateur
        // (en light mode notamment, les tokens M3 produiraient un dégradé
        // bleu pâle → orange pâle, perte d'identité visuelle).
        gradient: const LinearGradient(
          colors: [Color(0xFF1F6FEB), Color(0xFF7B1FA2)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.25),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                entry.cardIssuer.isEmpty
                    ? t.entryDetailCardLabel
                    : entry.cardIssuer.toUpperCase(),
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.5,
                ),
              ),
              const Icon(Icons.credit_card, color: Colors.white70, size: 24),
            ],
          ),
          Text(
            showNumber && clean.isNotEmpty ? formatted.toString() : masked,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 20,
              fontFamily: 'monospace',
              fontWeight: FontWeight.w600,
              letterSpacing: 2,
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    t.entryDetailCardHolderLabel,
                    // P0 v2.4.0 — `Colors.white60` (~3.0:1 sur gradient
                    // bleu→violet) échouait WCAG AA 4.5:1. `white.85` +
                    // taille 10 atteint ~5.2:1 sur le pire pixel.
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.85),
                      fontSize: 10,
                      letterSpacing: 1,
                    ),
                  ),
                  Text(
                    entry.cardholderName.isEmpty
                        ? '—'
                        : entry.cardholderName.toUpperCase(),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    t.entryDetailCardExpiryLabel,
                    // P0 v2.4.0 — `Colors.white60` (~3.0:1 sur gradient
                    // bleu→violet) échouait WCAG AA 4.5:1. `white.85` +
                    // taille 10 atteint ~5.2:1 sur le pire pixel.
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.85),
                      fontSize: 10,
                      letterSpacing: 1,
                    ),
                  ),
                  Text(
                    entry.cardExpiry.isEmpty ? '—' : entry.cardExpiry,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
    );
  }
}
