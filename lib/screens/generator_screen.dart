import 'dart:math';
import 'package:flutter/material.dart';
import '../services/clipboard_service.dart';
import '../services/diceware_fr.dart';

class GeneratorScreen extends StatefulWidget {
  final bool returnPassword;
  const GeneratorScreen({super.key, this.returnPassword = false});

  @override
  State<GeneratorScreen> createState() => _GeneratorScreenState();
}

enum _Mode { chars, phrase }

class _GeneratorScreenState extends State<GeneratorScreen> {
  _Mode _mode = _Mode.chars;
  double _length  = 16;
  bool _upper   = true;
  bool _lower   = true;
  bool _digits  = true;
  bool _symbols = true;
  // Phrase de passe (Diceware)
  int _phraseWords = 5;
  bool _phraseAppendNumber = true;
  String _phraseSeparator = '-';
  String _password = '';

  static const _uppers = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  static const _lowers = 'abcdefghijklmnopqrstuvwxyz';
  static const _nums   = '0123456789';
  static const _syms   = r'!@#$%^&*()-_=+[]{}|;:,.<>?';

  @override
  void initState() {
    super.initState();
    _generate();
  }

  void _generate() {
    if (_mode == _Mode.phrase) {
      setState(() {
        _password = DicewareFr.generate(
          count: _phraseWords,
          separator: _phraseSeparator,
          appendNumber: _phraseAppendNumber,
        );
      });
      return;
    }
    final buf = StringBuffer();
    if (_upper)   buf.write(_uppers);
    if (_lower)   buf.write(_lowers);
    if (_digits)  buf.write(_nums);
    if (_symbols) buf.write(_syms);
    if (buf.isEmpty) {
      buf.write(_lowers);
      setState(() => _lower = true);
    }
    final pool = buf.toString();
    final rng  = Random.secure();
    setState(() {
      _password = List.generate(
        _length.round(),
        (_) => pool[rng.nextInt(pool.length)],
      ).join();
    });
  }

  /// Score de force normalisé [0..1] basé sur l'entropie réelle.
  /// Pour Diceware : log2(N^k) / 80 (80 bits = très fort).
  /// Pour caractères : log2(pool^len) / 80.
  double _strengthScore() {
    final bits = _entropyBits();
    return (bits / 80.0).clamp(0.0, 1.0);
  }

  double _entropyBits() {
    if (_mode == _Mode.phrase) {
      return DicewareFr.entropy(_phraseWords)
          + (_phraseAppendNumber ? log(90) / ln2 : 0);
    }
    var pool = 0;
    if (_upper)   pool += 26;
    if (_lower)   pool += 26;
    if (_digits)  pool += 10;
    if (_symbols) pool += 26;
    if (pool == 0) return 0;
    return _length * (log(pool) / ln2);
  }

  @override
  Widget build(BuildContext context) {
    final cs       = Theme.of(context).colorScheme;
    final strength = _strengthScore();
    final sColor   = strength < 0.4
        ? const Color(0xFFE53935)
        : strength < 0.7
            ? const Color(0xFFFF7043)
            : const Color(0xFF43A047);
    final sLabel = strength < 0.4 ? 'Faible' : strength < 0.7 ? 'Moyen' : 'Fort';
    final bits = _entropyBits().round();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Générateur'),
        actions: [
          if (widget.returnPassword)
            TextButton(
              onPressed: _password.isNotEmpty
                  ? () => Navigator.pop(context, _password)
                  : null,
              child: const Text('Utiliser',
                  style: TextStyle(fontWeight: FontWeight.w700)),
            ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Password display
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: cs.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: cs.outline.withValues(alpha: 0.3)),
              ),
              child: Column(children: [
                Text(
                  _password,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: cs.primary,
                    letterSpacing: 1.5,
                  ),
                ),
                const SizedBox(height: 10),
                Row(children: [
                  Expanded(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: strength,
                        minHeight: 5,
                        backgroundColor: cs.surface,
                        valueColor: AlwaysStoppedAnimation(sColor),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text('$sLabel · $bits bits',
                      style: TextStyle(fontSize: 11, color: sColor,
                          fontWeight: FontWeight.w600)),
                ]),
              ]),
            ),
            const SizedBox(height: 16),

            Row(children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _generate,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: const Text('Générer'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton.icon(
                  onPressed: () async {
                    final messenger = ScaffoldMessenger.of(context);
                    await ClipboardService.copyWithAutoClear(_password);
                    messenger.showSnackBar(const SnackBar(
                      content: Text('Copié ✓'),
                      duration: Duration(seconds: 2),
                    ));
                  },
                  icon: const Icon(Icons.copy, size: 18),
                  label: const Text('Copier'),
                ),
              ),
            ]),
            const SizedBox(height: 24),

            // ── Sélecteur de mode ──────────────────────────────────────────
            SegmentedButton<_Mode>(
              segments: const [
                ButtonSegment(value: _Mode.chars,
                    label: Text('Caractères'), icon: Icon(Icons.tag, size: 16)),
                ButtonSegment(value: _Mode.phrase,
                    label: Text('Phrase'), icon: Icon(Icons.translate, size: 16)),
              ],
              selected: {_mode},
              onSelectionChanged: (s) {
                setState(() => _mode = s.first);
                _generate();
              },
            ),
            const SizedBox(height: 16),

            if (_mode == _Mode.chars) ..._buildCharsOptions(cs)
            else ..._buildPhraseOptions(cs),
          ],
        ),
      ),
    );
  }

  List<Widget> _buildCharsOptions(ColorScheme cs) => [
    Text('Longueur : ${_length.round()}',
        style: const TextStyle(fontWeight: FontWeight.w600)),
    Slider(
      value: _length,
      min: 8, max: 64, divisions: 56,
      label: '${_length.round()}',
      onChanged: (v) { setState(() => _length = v); _generate(); },
    ),
    const SizedBox(height: 8),
    Text('Caractères',
        style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13,
            color: cs.onSurfaceVariant)),
    const SizedBox(height: 8),
    _OptionTile('A–Z  Majuscules', _upper,
        (v) { setState(() => _upper = v);   _generate(); }),
    _OptionTile('a–z  Minuscules', _lower,
        (v) { setState(() => _lower = v);   _generate(); }),
    _OptionTile('0–9  Chiffres',   _digits,
        (v) { setState(() => _digits = v);  _generate(); }),
    _OptionTile('!@#  Symboles',   _symbols,
        (v) { setState(() => _symbols = v); _generate(); }),
  ];

  List<Widget> _buildPhraseOptions(ColorScheme cs) => [
    Text('Nombre de mots : $_phraseWords',
        style: const TextStyle(fontWeight: FontWeight.w600)),
    Slider(
      value: _phraseWords.toDouble(),
      min: 3, max: 8, divisions: 5,
      label: '$_phraseWords',
      onChanged: (v) {
        setState(() => _phraseWords = v.round());
        _generate();
      },
    ),
    const SizedBox(height: 8),
    Text('Options',
        style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13,
            color: cs.onSurfaceVariant)),
    const SizedBox(height: 8),
    _OptionTile('Ajouter un nombre 10–99', _phraseAppendNumber,
        (v) { setState(() => _phraseAppendNumber = v); _generate(); }),
    Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: ListTile(
        dense: true,
        title: const Text('Séparateur', style: TextStyle(fontSize: 14)),
        trailing: SegmentedButton<String>(
          showSelectedIcon: false,
          style: const ButtonStyle(visualDensity: VisualDensity.compact),
          segments: const [
            ButtonSegment(value: '-', label: Text('-')),
            ButtonSegment(value: '.', label: Text('.')),
            ButtonSegment(value: '_', label: Text('_')),
            ButtonSegment(value: ' ', label: Text('␣')),
          ],
          selected: {_phraseSeparator},
          onSelectionChanged: (s) {
            setState(() => _phraseSeparator = s.first);
            _generate();
          },
        ),
      ),
    ),
    const SizedBox(height: 8),
    Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: cs.primaryContainer.withValues(alpha: 0.20),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(children: [
        Icon(Icons.lightbulb_outline, size: 16, color: cs.primary),
        const SizedBox(width: 8),
        Expanded(child: Text(
          'Une phrase de passe est plus simple à mémoriser '
          'qu\'un mot de passe complexe, et peut être tout aussi forte.',
          style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
        )),
      ]),
    ),
  ];
}

class _OptionTile extends StatelessWidget {
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;
  const _OptionTile(this.label, this.value, this.onChanged);

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(bottom: 6),
        child: SwitchListTile(
          title: Text(label, style: const TextStyle(fontSize: 14)),
          value: value,
          onChanged: onChanged,
          dense: true,
        ),
      );
}
