import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/totp_service.dart';
import '../services/vault_service.dart';
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
  final _titleCtrl    = TextEditingController();
  final _userCtrl     = TextEditingController();
  final _passCtrl     = TextEditingController();
  final _urlCtrl      = TextEditingController();
  final _totpCtrl     = TextEditingController();
  final _notesCtrl    = TextEditingController();
  final _holderCtrl   = TextEditingController();
  final _numberCtrl   = TextEditingController();
  final _expiryCtrl   = TextEditingController();
  final _cvvCtrl      = TextEditingController();
  final _pinCtrl      = TextEditingController();
  final _issuerCtrl   = TextEditingController();

  late EntryType _type;
  String _category    = 'Autres';
  bool _showPass      = false;
  bool _showCvv       = false;
  bool _showPin       = false;
  bool _showTotp      = false;
  bool _isFavorite    = false;
  bool _saving        = false;
  String? _totpError;

  bool get _isEdit => widget.entry != null;

  @override
  void initState() {
    super.initState();
    final e = widget.entry;
    if (e != null) {
      _type = e.type;
      _titleCtrl.text  = e.title;
      _userCtrl.text   = e.username;
      _passCtrl.text   = e.password;
      _urlCtrl.text    = e.url;
      _totpCtrl.text   = e.totpSecret;
      _notesCtrl.text  = e.notes;
      _holderCtrl.text = e.cardholderName;
      _numberCtrl.text = e.cardNumber;
      _expiryCtrl.text = e.cardExpiry;
      _cvvCtrl.text    = e.cardCvv;
      _pinCtrl.text    = e.cardPin;
      _issuerCtrl.text = e.cardIssuer;
      _category        = e.category;
      _isFavorite      = e.isFavorite;
    } else {
      _type = widget.type;
      // Sensible default categories per type
      _category = _type == EntryType.card ? 'Banque' : 'Autres';
    }
  }

  @override
  void dispose() {
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
    if (_titleCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Le titre est obligatoire')));
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
        type:           _type,
        title:          _titleCtrl.text.trim(),
        category:       _category,
        username:       _userCtrl.text.trim(),
        password:       _passCtrl.text,
        url:            _urlCtrl.text.trim(),
        totpSecret:     _totpCtrl.text.trim(),
        notes:          _notesCtrl.text.trim(),
        isFavorite:     _isFavorite,
        cardholderName: _holderCtrl.text.trim(),
        cardNumber:     _numberCtrl.text.replaceAll(' ', '').trim(),
        cardExpiry:     _expiryCtrl.text.trim(),
        cardCvv:        _cvvCtrl.text.trim(),
        cardPin:        _pinCtrl.text.trim(),
        cardIssuer:     _issuerCtrl.text.trim(),
      );
      await VaultService().updateEntry(entry);
    } else {
      entry = Entry(
        type:           _type,
        title:          _titleCtrl.text.trim(),
        category:       _category,
        username:       _userCtrl.text.trim(),
        password:       _passCtrl.text,
        url:            _urlCtrl.text.trim(),
        totpSecret:     _totpCtrl.text.trim(),
        notes:          _notesCtrl.text.trim(),
        isFavorite:     _isFavorite,
        cardholderName: _holderCtrl.text.trim(),
        cardNumber:     _numberCtrl.text.replaceAll(' ', '').trim(),
        cardExpiry:     _expiryCtrl.text.trim(),
        cardCvv:        _cvvCtrl.text.trim(),
        cardPin:        _pinCtrl.text.trim(),
        cardIssuer:     _issuerCtrl.text.trim(),
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
          builder: (_) => const GeneratorScreen(returnPassword: true)),
    );
    if (pass != null) setState(() => _passCtrl.text = pass);
  }

  String? _extractTotpSecret(String raw) {
    if (raw.startsWith('otpauth://')) {
      try {
        return Uri.parse(raw).queryParameters['secret'];
      } catch (_) { return null; }
    }
    return raw.trim();
  }

  Future<void> _scanQrForTotp() async {
    final messenger = ScaffoldMessenger.of(context);
    final raw = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (_) => const QrScannerScreen()),
    );
    if (raw == null || !mounted) return;
    final secret = _extractTotpSecret(raw);
    if (secret == null || secret.isEmpty) {
      messenger.showSnackBar(
        const SnackBar(content: Text('QR code invalide pour 2FA')));
      return;
    }
    final err = TotpService.validate(secret);
    if (err != null) {
      messenger.showSnackBar(SnackBar(content: Text('Secret invalide : $err')));
      return;
    }
    setState(() {
      _totpCtrl.text = secret;
      _totpError = null;
    });
    messenger.showSnackBar(
      const SnackBar(content: Text('Secret 2FA ajouté ✓')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEdit ? 'Modifier' : 'Nouveau ${entryTypeLabel(_type).toLowerCase()}'),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: Text(
              _saving ? 'Enregistrement…' : 'Enregistrer',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(children: [

          // Category
          _label('Catégorie'),
          const SizedBox(height: 6),
          SizedBox(
            height: 48,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: categories.length,
              separatorBuilder: (_, i) => const SizedBox(width: 6),
              itemBuilder: (context, i) {
                final cat      = categories[i];
                final selected = _category == cat;
                final color    = categoryColor(cat);
                return ChoiceChip(
                  label: Text(cat, style: const TextStyle(fontSize: 12)),
                  avatar: Icon(categoryIcon(cat), size: 14,
                      color: selected
                          ? Theme.of(context).colorScheme.onPrimary
                          : color),
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
          _label('Titre *'),
          const SizedBox(height: 6),
          TextField(
            controller: _titleCtrl,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(
              hintText: _hintForTitle(),
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.title, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Type-specific fields
          if (_type == EntryType.password) ..._buildPasswordFields(),
          if (_type == EntryType.note)     ..._buildNoteFields(),
          if (_type == EntryType.card)     ..._buildCardFields(),

          // Favorite
          Card(
            child: SwitchListTile(
              title: const Text('Favori'),
              secondary: Icon(
                _isFavorite ? Icons.star : Icons.star_border,
                color: _isFavorite ? Colors.amber : null,
              ),
              value: _isFavorite,
              onChanged: (v) => setState(() => _isFavorite = v),
            ),
          ),
          const SizedBox(height: 24),

          FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save_outlined, size: 18),
            label: Text(_saving ? 'Enregistrement…' : 'Enregistrer'),
            style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48)),
          ),
        ]),
      ),
    );
  }

  String _hintForTitle() {
    switch (_type) {
      case EntryType.password: return 'ex: Google, Netflix, BNP…';
      case EntryType.note:     return 'ex: Code Wi-Fi, RIB, recovery key…';
      case EntryType.card:     return 'ex: Visa BNP, Mastercard pro…';
    }
  }

  List<Widget> _buildPasswordFields() => [
    _label('Identifiant'),
    const SizedBox(height: 6),
    TextField(
      controller: _userCtrl,
      keyboardType: TextInputType.emailAddress,
      decoration: const InputDecoration(
        hintText: 'Email, nom d\'utilisateur…',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.person_outline, size: 20),
      ),
    ),
    const SizedBox(height: 16),

    _label('Mot de passe'),
    const SizedBox(height: 6),
    TextField(
      controller: _passCtrl,
      obscureText: !_showPass,
      decoration: InputDecoration(
        hintText: 'Mot de passe',
        border: const OutlineInputBorder(),
        prefixIcon: const Icon(Icons.lock_outline, size: 20),
        suffixIcon: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: Icon(_showPass ? Icons.visibility_off : Icons.visibility, size: 20),
              onPressed: () => setState(() => _showPass = !_showPass),
            ),
            IconButton(
              icon: const Icon(Icons.auto_fix_high, size: 20),
              tooltip: 'Générer',
              onPressed: _pickGenerated,
            ),
          ],
        ),
      ),
    ),
    const SizedBox(height: 16),

    _label('URL (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _urlCtrl,
      keyboardType: TextInputType.url,
      decoration: const InputDecoration(
        hintText: 'https://…',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.link, size: 20),
      ),
    ),
    const SizedBox(height: 16),

    // TOTP / 2FA
    _label('Code 2FA — secret TOTP (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _totpCtrl,
      obscureText: !_showTotp,
      onChanged: (_) {
        if (_totpError != null) setState(() => _totpError = null);
      },
      decoration: InputDecoration(
        hintText: 'Clé secrète Base32 ou scanner QR',
        border: const OutlineInputBorder(),
        prefixIcon: const Icon(Icons.shield_outlined, size: 20),
        errorText: _totpError,
        helperText: 'Génère des codes 2FA à 6 chiffres',
        suffixIcon: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: Icon(_showTotp ? Icons.visibility_off : Icons.visibility, size: 20),
              onPressed: () => setState(() => _showTotp = !_showTotp),
            ),
            IconButton(
              icon: const Icon(Icons.qr_code_scanner, size: 20),
              tooltip: 'Scanner QR',
              onPressed: _scanQrForTotp,
            ),
          ],
        ),
      ),
    ),
    const SizedBox(height: 16),

    _label('Notes (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _notesCtrl,
      maxLines: 3,
      decoration: const InputDecoration(
        hintText: 'Informations supplémentaires…',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.notes, size: 20),
      ),
    ),
    const SizedBox(height: 16),
  ];

  List<Widget> _buildNoteFields() => [
    _label('Contenu'),
    const SizedBox(height: 6),
    TextField(
      controller: _notesCtrl,
      maxLines: 12,
      minLines: 6,
      textCapitalization: TextCapitalization.sentences,
      decoration: const InputDecoration(
        hintText: 'Texte chiffré confidentiel…',
        border: OutlineInputBorder(),
        alignLabelWithHint: true,
      ),
    ),
    const SizedBox(height: 16),
  ];

  List<Widget> _buildCardFields() => [
    _label('Titulaire de la carte'),
    const SizedBox(height: 6),
    TextField(
      controller: _holderCtrl,
      textCapitalization: TextCapitalization.words,
      decoration: const InputDecoration(
        hintText: 'Nom Prénom',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.person_outline, size: 20),
      ),
    ),
    const SizedBox(height: 16),

    _label('Numéro de carte'),
    const SizedBox(height: 6),
    TextField(
      controller: _numberCtrl,
      keyboardType: TextInputType.number,
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(19),
        _CardNumberFormatter(),
      ],
      decoration: const InputDecoration(
        hintText: '0000 0000 0000 0000',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.credit_card, size: 20),
      ),
    ),
    const SizedBox(height: 16),

    Row(children: [
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _label('Expiration'),
          const SizedBox(height: 6),
          TextField(
            controller: _expiryCtrl,
            keyboardType: TextInputType.number,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(4),
              _ExpiryFormatter(),
            ],
            decoration: const InputDecoration(
              hintText: 'MM/AA',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.calendar_today, size: 18),
            ),
          ),
        ]),
      ),
      const SizedBox(width: 10),
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _label('CVV'),
          const SizedBox(height: 6),
          TextField(
            controller: _cvvCtrl,
            obscureText: !_showCvv,
            keyboardType: TextInputType.number,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(4),
            ],
            decoration: InputDecoration(
              hintText: '000',
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                icon: Icon(_showCvv ? Icons.visibility_off : Icons.visibility, size: 20),
                onPressed: () => setState(() => _showCvv = !_showCvv),
              ),
            ),
          ),
        ]),
      ),
    ]),
    const SizedBox(height: 16),

    _label('Code PIN (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _pinCtrl,
      obscureText: !_showPin,
      keyboardType: TextInputType.number,
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(8),
      ],
      decoration: InputDecoration(
        hintText: '0000',
        border: const OutlineInputBorder(),
        prefixIcon: const Icon(Icons.pin_outlined, size: 20),
        suffixIcon: IconButton(
          icon: Icon(_showPin ? Icons.visibility_off : Icons.visibility, size: 20),
          onPressed: () => setState(() => _showPin = !_showPin),
        ),
      ),
    ),
    const SizedBox(height: 16),

    _label('Banque / Émetteur (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _issuerCtrl,
      decoration: const InputDecoration(
        hintText: 'BNP Paribas, Boursorama, N26…',
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.account_balance_outlined, size: 20),
      ),
    ),
    const SizedBox(height: 16),

    _label('Notes (optionnel)'),
    const SizedBox(height: 6),
    TextField(
      controller: _notesCtrl,
      maxLines: 2,
      decoration: const InputDecoration(
        hintText: 'Plafond, type de carte…',
        border: OutlineInputBorder(),
      ),
    ),
    const SizedBox(height: 16),
  ];

  Widget _label(String text) => Align(
        alignment: Alignment.centerLeft,
        child: Text(text,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                letterSpacing: 0.4)),
      );
}

/// Formats card number as "1234 5678 9012 3456" while typing.
class _CardNumberFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
      TextEditingValue oldValue, TextEditingValue newValue) {
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
      TextEditingValue oldValue, TextEditingValue newValue) {
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
