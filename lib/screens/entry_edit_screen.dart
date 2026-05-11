import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../l10n/app_localizations.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/secure_window.dart';
import '../services/totp_service.dart';
import '../services/vault_service.dart';
import '../widgets/password_text_field.dart';
import 'generator_screen.dart';
import 'qr_scanner_screen.dart';

class EntryEditScreen extends StatefulWidget {
  final Entry? entry;
  final EntryType type;
  final VoidCallback onSaved;
  const EntryEditScreen({
    super.key,
    this.entry,
    this.type = EntryType.password,
    required this.onSaved,
  });

  @override
  State<EntryEditScreen> createState() => _EntryEditScreenState();
}

class _EntryEditScreenState extends State<EntryEditScreen> {
  final _titleCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _urlCtrl = TextEditingController();
  final _totpCtrl = TextEditingController();
  final _notesCtrl = TextEditingController();
  final _holderCtrl = TextEditingController();
  final _numberCtrl = TextEditingController();
  final _expiryCtrl = TextEditingController();
  final _cvvCtrl = TextEditingController();
  final _pinCtrl = TextEditingController();
  final _issuerCtrl = TextEditingController();

  late EntryType _type;
  String _category = 'Autres';
  bool _isFavorite = false;
  bool _saving = false;
  String? _totpError;

  bool get _isEdit => widget.entry != null;

  @override
  void initState() {
    super.initState();
    final e = widget.entry;
    if (e != null) {
      _type = e.type;
      _titleCtrl.text = e.title;
      _userCtrl.text = e.username;
      _passCtrl.text = e.password;
      _urlCtrl.text = e.url;
      _totpCtrl.text = e.totpSecret;
      _notesCtrl.text = e.notes;
      _holderCtrl.text = e.cardholderName;
      _numberCtrl.text = e.cardNumber;
      _expiryCtrl.text = e.cardExpiry;
      _cvvCtrl.text = e.cardCvv;
      _pinCtrl.text = e.cardPin;
      _issuerCtrl.text = e.cardIssuer;
      _category = e.category;
      _isFavorite = e.isFavorite;
    } else {
      _type = widget.type;
      // Sensible default categories per type
      _category = _type == EntryType.card ? 'Banque' : 'Autres';
    }
    // v2.3.8 — sur l'écran d'édition d'une **Note**, on relâche
    // FLAG_SECURE pour permettre copier/coller/sélectionner du système
    // (bloqué par Samsung Knox quand FLAG_SECURE est actif). Les autres
    // types (password, card, identity) gardent FLAG_SECURE actif pour
    // protéger les secrets contre les screenshots.
    if (_type == EntryType.note) {
      SecureWindow.relax();
    }
  }

  @override
  void dispose() {
    // v2.3.8 — restaure FLAG_SECURE si on l'avait relâché pour le type Note.
    // À faire AVANT le wipe controllers pour rester symétrique avec initState.
    if (_type == EntryType.note) {
      SecureWindow.restore();
    }
    // B12 v2.3.8 — clear AVANT dispose pour tous les controllers tenant
    // des secrets (password, TOTP, CVV, PIN, card number, notes).
    _passCtrl.clear();
    _totpCtrl.clear();
    _cvvCtrl.clear();
    _pinCtrl.clear();
    _numberCtrl.clear();
    _notesCtrl.clear();
    _titleCtrl.dispose();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _urlCtrl.dispose();
    _totpCtrl.dispose();
    _notesCtrl.dispose();
    _holderCtrl.dispose();
    _numberCtrl.dispose();
    _expiryCtrl.dispose();
    _cvvCtrl.dispose();
    _pinCtrl.dispose();
    _issuerCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final t = AppLocalizations.of(context);
    if (_titleCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(t.entryEditTitleRequired)));
      return;
    }

    // Validate TOTP secret if provided
    if (_type == EntryType.password && _totpCtrl.text.trim().isNotEmpty) {
      final err = TotpService.validate(_totpCtrl.text.trim());
      if (err != null) {
        setState(() => _totpError = err);
        return;
      }
    }

    setState(() => _saving = true);

    Entry entry;
    if (_isEdit) {
      entry = widget.entry!.copyWith(
        type: _type,
        title: _titleCtrl.text.trim(),
        category: _category,
        username: _userCtrl.text.trim(),
        password: _passCtrl.text,
        url: _urlCtrl.text.trim(),
        totpSecret: _totpCtrl.text.trim(),
        notes: _notesCtrl.text.trim(),
        isFavorite: _isFavorite,
        cardholderName: _holderCtrl.text.trim(),
        cardNumber: _numberCtrl.text.replaceAll(' ', '').trim(),
        cardExpiry: _expiryCtrl.text.trim(),
        cardCvv: _cvvCtrl.text.trim(),
        cardPin: _pinCtrl.text.trim(),
        cardIssuer: _issuerCtrl.text.trim(),
      );
      await VaultService().updateEntry(entry);
    } else {
      entry = Entry(
        type: _type,
        title: _titleCtrl.text.trim(),
        category: _category,
        username: _userCtrl.text.trim(),
        password: _passCtrl.text,
        url: _urlCtrl.text.trim(),
        totpSecret: _totpCtrl.text.trim(),
        notes: _notesCtrl.text.trim(),
        isFavorite: _isFavorite,
        cardholderName: _holderCtrl.text.trim(),
        cardNumber: _numberCtrl.text.replaceAll(' ', '').trim(),
        cardExpiry: _expiryCtrl.text.trim(),
        cardCvv: _cvvCtrl.text.trim(),
        cardPin: _pinCtrl.text.trim(),
        cardIssuer: _issuerCtrl.text.trim(),
      );
      await VaultService().addEntry(entry);
    }

    widget.onSaved();
    if (mounted) Navigator.of(context).pop(entry);
  }

  Future<void> _pickGenerated() async {
    final pass = await Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => const GeneratorScreen(returnPassword: true),
      ),
    );
    if (pass != null) setState(() => _passCtrl.text = pass);
  }

  String? _extractTotpSecret(String raw) {
    if (raw.startsWith('otpauth://')) {
      try {
        return Uri.parse(raw).queryParameters['secret'];
      } catch (_) {
        return null;
      }
    }
    return raw.trim();
  }

  Future<void> _scanQrForTotp() async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    final raw = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (_) => const QrScannerScreen()),
    );
    if (raw == null || !mounted) return;
    final secret = _extractTotpSecret(raw);
    if (secret == null || secret.isEmpty) {
      messenger.showSnackBar(SnackBar(content: Text(t.entryEditQrInvalid)));
      return;
    }
    final err = TotpService.validate(secret);
    if (err != null) {
      messenger.showSnackBar(
        SnackBar(content: Text(t.entryEditSecretInvalid(err))),
      );
      return;
    }
    setState(() {
      _totpCtrl.text = secret;
      _totpError = null;
    });
    messenger.showSnackBar(SnackBar(content: Text(t.entryEditSecretAdded)));
  }

  @override
  Widget build(BuildContext context) {
    final base = Theme.of(context);
    // Réduit globalement la hauteur de tous les TextField de ce formulaire
    // (~56dp → ~44dp), gain ~10dp × N fields.
    final dense = base.copyWith(
      inputDecorationTheme: base.inputDecorationTheme.copyWith(
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 12,
          vertical: 12,
        ),
      ),
    );
    return Theme(data: dense, child: _build(context));
  }

  String _typeLabelLower(EntryType type, AppLocalizations t) {
    switch (type) {
      case EntryType.password:
        return t.entryTypePassword.toLowerCase();
      case EntryType.note:
        return t.entryTypeNote.toLowerCase();
      case EntryType.card:
        return t.entryTypeCard.toLowerCase();
    }
  }

  Widget _build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _isEdit
              ? t.entryEditTitleEdit
              : t.entryEditTitleNew(_typeLabelLower(_type, t)),
        ),
        actions: [
          // Toggle favori dans l'AppBar — gain d'espace dans le form,
          // toujours visible quel que soit le scroll.
          IconButton(
            icon: Icon(
              _isFavorite ? Icons.star : Icons.star_border,
              color: _isFavorite ? Colors.amber : null,
            ),
            tooltip: _isFavorite
                ? t.entryEditRemoveFavorite
                : t.entryEditAddFavorite,
            onPressed: () => setState(() => _isFavorite = !_isFavorite),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            // Ligne "Catégorie" + bouton Enregistrer aligné à droite
            // (fond bleu clair primaryContainer + bordure, bien visible).
            Row(
              children: [
                _label(t.entryEditCategory),
                const Spacer(),
                FilledButton.tonal(
                  onPressed: _saving ? null : _save,
                  style: FilledButton.styleFrom(
                    side: BorderSide(
                      color: Theme.of(
                        context,
                      ).colorScheme.primary.withValues(alpha: 0.5),
                      width: 1,
                    ),
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    minimumSize: const Size(0, 38),
                  ),
                  child: Text(
                    _saving ? t.entryEditSaving : t.entryEditSave,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            SizedBox(
              height: 48,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: categories.length,
                separatorBuilder: (_, i) => const SizedBox(width: 6),
                itemBuilder: (context, i) {
                  final cat = categories[i];
                  final selected = _category == cat;
                  final color = categoryColor(cat);
                  return ChoiceChip(
                    label: Text(
                      categoryLabel(cat, AppLocalizations.of(context)),
                      style: const TextStyle(fontSize: 12),
                    ),
                    avatar: Icon(
                      categoryIcon(cat),
                      size: 14,
                      color: selected
                          ? Theme.of(context).colorScheme.onPrimary
                          : color,
                    ),
                    selected: selected,
                    selectedColor: color,
                    onSelected: (_) => setState(() => _category = cat),
                    showCheckmark: false,
                  );
                },
              ),
            ),
            const SizedBox(height: 16),

            // Title
            _label(t.entryEditFieldTitleRequired),
            const SizedBox(height: 6),
            TextField(
              controller: _titleCtrl,
              textCapitalization: TextCapitalization.sentences,
              decoration: InputDecoration(
                labelText: t.entryEditFieldTitleRequired,
                hintText: _hintForTitle(t),
                border: const OutlineInputBorder(),
                prefixIcon: const Icon(Icons.title, size: 20),
              ),
            ),
            const SizedBox(height: 16),

            // Type-specific fields
            if (_type == EntryType.password) ..._buildPasswordFields(),
            if (_type == EntryType.note) ..._buildNoteFields(),
            if (_type == EntryType.card) ..._buildCardFields(),

            // Favori et Enregistrer sont tous deux dans l'AppBar (haut).
            // Pas de bouton dupliqué en bas du form — gain ~70dp.
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  String _hintForTitle(AppLocalizations t) {
    switch (_type) {
      case EntryType.password:
        return t.entryEditHintTitlePassword;
      case EntryType.note:
        return t.entryEditHintTitleNote;
      case EntryType.card:
        return t.entryEditHintTitleCard;
    }
  }

  List<Widget> _buildPasswordFields() {
    final t = AppLocalizations.of(context);
    return [
      _label(t.entryEditFieldUsername),
      const SizedBox(height: 6),
      TextField(
        controller: _userCtrl,
        keyboardType: TextInputType.emailAddress,
        decoration: InputDecoration(
          labelText: t.entryEditFieldUsername,
          hintText: t.entryEditHintUsername,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.person_outline, size: 20),
        ),
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldPassword),
      const SizedBox(height: 6),
      PasswordTextField(
        controller: _passCtrl,
        labelText: t.entryEditFieldPassword,
        hintText: t.entryEditHintPassword,
        extraSuffixIcons: [
          IconButton(
            icon: const Icon(Icons.auto_fix_high, size: 20),
            tooltip: t.entryEditTooltipGenerate,
            onPressed: _pickGenerated,
          ),
        ],
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldUrlOptional),
      const SizedBox(height: 6),
      TextField(
        controller: _urlCtrl,
        keyboardType: TextInputType.url,
        decoration: InputDecoration(
          labelText: t.entryEditFieldUrlOptional,
          hintText: t.entryEditHintUrl,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.link, size: 20),
        ),
      ),
      const SizedBox(height: 16),

      // TOTP / 2FA
      _label(t.entryEditField2faOptional),
      const SizedBox(height: 6),
      PasswordTextField(
        controller: _totpCtrl,
        labelText: t.entryEditField2faOptional,
        hintText: t.entryEditHint2fa,
        helperText: t.entryEditHelper2fa,
        errorText: _totpError,
        prefixIcon: const Icon(Icons.shield_outlined, size: 20),
        onChanged: (_) {
          if (_totpError != null) setState(() => _totpError = null);
        },
        extraSuffixIcons: [
          IconButton(
            icon: const Icon(Icons.qr_code_scanner, size: 20),
            tooltip: t.entryEditTooltipScanQr,
            onPressed: _scanQrForTotp,
          ),
        ],
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldNotesOptional),
      const SizedBox(height: 6),
      TextField(
        controller: _notesCtrl,
        maxLines: 3,
        decoration: InputDecoration(
          labelText: t.entryEditFieldNotesOptional,
          hintText: t.entryEditHintNotes,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.notes, size: 20),
        ),
      ),
      const SizedBox(height: 16),
    ];
  }

  List<Widget> _buildNoteFields() {
    final t = AppLocalizations.of(context);
    return [
      _label(t.entryEditFieldContent),
      const SizedBox(height: 6),
      // v2.3.10 — barre d'actions explicites au-dessus du champ Note.
      // Garantit que Coller/Copier/Tout sélectionner sont accessibles
      // même quand Samsung Knox bloque le menu contextuel système
      // (effet de bord de FLAG_SECURE qui interfère avec le clipboard
      // au niveau Android). Utilise l'API `Clipboard` Dart qui opère au
      // niveau app — fiable indépendamment de Knox/OEM.
      Wrap(
        spacing: 4,
        children: [
          TextButton.icon(
            onPressed: _pasteIntoNote,
            icon: const Icon(Icons.content_paste, size: 16),
            label: Text(t.noteActionPaste),
            style: TextButton.styleFrom(visualDensity: VisualDensity.compact),
          ),
          TextButton.icon(
            onPressed: _selectAllNote,
            icon: const Icon(Icons.select_all, size: 16),
            label: Text(t.noteActionSelectAll),
            style: TextButton.styleFrom(visualDensity: VisualDensity.compact),
          ),
          TextButton.icon(
            onPressed: _copyAllNote,
            icon: const Icon(Icons.copy_all, size: 16),
            label: Text(t.noteActionCopyAll),
            style: TextButton.styleFrom(visualDensity: VisualDensity.compact),
          ),
        ],
      ),
      const SizedBox(height: 6),
      TextField(
        controller: _notesCtrl,
        maxLines: 12,
        minLines: 6,
        textCapitalization: TextCapitalization.sentences,
        decoration: InputDecoration(
          labelText: t.entryEditFieldContent,
          hintText: t.entryEditHintNoteContent,
          border: const OutlineInputBorder(),
          alignLabelWithHint: true,
        ),
      ),
      const SizedBox(height: 16),
    ];
  }

  /// v2.3.10 — Coller via l'API Clipboard Dart à la position du curseur.
  /// Marche même quand le menu contextuel système est masqué par
  /// FLAG_SECURE/Knox, car `Clipboard.getData` opère au niveau app.
  Future<void> _pasteIntoNote() async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final clip = data?.text ?? '';
    if (clip.isEmpty) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(t.noteActionClipboardEmpty),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
      return;
    }
    final ctrl = _notesCtrl;
    final sel = ctrl.selection;
    final cur = ctrl.text;
    if (sel.isValid && sel.start >= 0 && sel.end >= 0) {
      final before = cur.substring(0, sel.start);
      final after = cur.substring(sel.end);
      final newText = '$before$clip$after';
      final newCursor = sel.start + clip.length;
      ctrl.value = TextEditingValue(
        text: newText,
        selection: TextSelection.collapsed(offset: newCursor),
      );
    } else {
      ctrl.text = cur + clip;
      ctrl.selection = TextSelection.collapsed(offset: ctrl.text.length);
    }
    if (mounted) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(t.noteActionPasted),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 1),
        ),
      );
    }
  }

  /// Sélectionne tout le texte du champ Note (curseur étendu sur
  /// l'ensemble du contenu).
  void _selectAllNote() {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final ctrl = _notesCtrl;
    if (ctrl.text.isEmpty) return;
    ctrl.selection = TextSelection(
      baseOffset: 0,
      extentOffset: ctrl.text.length,
    );
    messenger.showSnackBar(
      SnackBar(
        content: Text(t.noteActionAllSelected),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 1),
      ),
    );
  }

  /// Copie l'intégralité du contenu de la Note dans le presse-papier
  /// (sans auto-clear : ce n'est pas un secret au sens "password",
  /// l'utilisateur a explicitement demandé à copier).
  Future<void> _copyAllNote() async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final text = _notesCtrl.text;
    if (text.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    messenger.showSnackBar(
      SnackBar(
        content: Text(t.noteActionCopied),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  List<Widget> _buildCardFields() {
    final t = AppLocalizations.of(context);
    return [
      _label(t.entryEditFieldCardholder),
      const SizedBox(height: 6),
      TextField(
        controller: _holderCtrl,
        textCapitalization: TextCapitalization.words,
        decoration: InputDecoration(
          labelText: t.entryEditFieldCardholder,
          hintText: t.entryEditHintCardholder,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.person_outline, size: 20),
        ),
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldCardNumber),
      const SizedBox(height: 6),
      TextField(
        controller: _numberCtrl,
        keyboardType: TextInputType.number,
        inputFormatters: [
          FilteringTextInputFormatter.digitsOnly,
          LengthLimitingTextInputFormatter(19),
          _CardNumberFormatter(),
        ],
        decoration: InputDecoration(
          labelText: t.entryEditFieldCardNumber,
          hintText: t.entryEditHintCardNumber,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.credit_card, size: 20),
        ),
      ),
      const SizedBox(height: 16),

      Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _label(t.entryEditFieldExpiry),
                const SizedBox(height: 6),
                TextField(
                  controller: _expiryCtrl,
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                    LengthLimitingTextInputFormatter(4),
                    _ExpiryFormatter(),
                  ],
                  decoration: InputDecoration(
                    labelText: t.entryEditFieldExpiry,
                    hintText: t.entryEditHintExpiry,
                    border: const OutlineInputBorder(),
                    prefixIcon: const Icon(Icons.calendar_today, size: 18),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _label(t.entryEditFieldCvv),
                const SizedBox(height: 6),
                PasswordTextField(
                  controller: _cvvCtrl,
                  labelText: t.entryEditFieldCvv,
                  hintText: t.entryEditHintCvv,
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                    LengthLimitingTextInputFormatter(4),
                  ],
                  showPrefixIcon: false,
                ),
              ],
            ),
          ),
        ],
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldPinOptional),
      const SizedBox(height: 6),
      PasswordTextField(
        controller: _pinCtrl,
        labelText: t.entryEditFieldPinOptional,
        hintText: t.entryEditHintPin,
        keyboardType: TextInputType.number,
        inputFormatters: [
          FilteringTextInputFormatter.digitsOnly,
          LengthLimitingTextInputFormatter(8),
        ],
        prefixIcon: const Icon(Icons.pin_outlined, size: 20),
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldIssuerOptional),
      const SizedBox(height: 6),
      TextField(
        controller: _issuerCtrl,
        decoration: InputDecoration(
          labelText: t.entryEditFieldIssuerOptional,
          hintText: t.entryEditHintIssuer,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.account_balance_outlined, size: 20),
        ),
      ),
      const SizedBox(height: 16),

      _label(t.entryEditFieldNotesOptional),
      const SizedBox(height: 6),
      TextField(
        controller: _notesCtrl,
        maxLines: 2,
        decoration: InputDecoration(
          labelText: t.entryEditFieldNotesOptional,
          hintText: t.entryEditHintCardNotes,
          border: const OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 16),
    ];
  }

  Widget _label(String text) => Align(
    alignment: Alignment.centerLeft,
    child: Text(
      text,
      style: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w700,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
        letterSpacing: 0.4,
      ),
    ),
  );
}

/// Formats card number as "1234 5678 9012 3456" while typing.
class _CardNumberFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    final digits = newValue.text.replaceAll(' ', '');
    final buf = StringBuffer();
    for (int i = 0; i < digits.length; i++) {
      if (i > 0 && i % 4 == 0) buf.write(' ');
      buf.write(digits[i]);
    }
    return TextEditingValue(
      text: buf.toString(),
      selection: TextSelection.collapsed(offset: buf.length),
    );
  }
}

/// Formats expiry as "MM/YY" while typing.
class _ExpiryFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    final digits = newValue.text.replaceAll('/', '');
    String out;
    if (digits.length <= 2) {
      out = digits;
    } else {
      out = '${digits.substring(0, 2)}/${digits.substring(2)}';
    }
    return TextEditingValue(
      text: out,
      selection: TextSelection.collapsed(offset: out.length),
    );
  }
}
