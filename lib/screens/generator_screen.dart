import 'dart:math';
import 'package:flutter/material.dart';
// ignore: unnecessary_import
import 'package:flutter/semantics.dart';
import '../l10n/app_localizations.dart';
import '../services/clipboard_service.dart';
import '../services/diceware_fr.dart';
import '../services/password_strength_service.dart';

class GeneratorScreen extends StatefulWidget {
  final bool returnPassword;
  const GeneratorScreen({super.key, this.returnPassword = false});

  @override
  State<GeneratorScreen> createState() => _GeneratorScreenState();
}

enum _Mode { chars, phrase }

class _GeneratorScreenState extends State<GeneratorScreen> {
  _Mode _mode = _Mode.chars;
  double _length = 16;
  bool _upper = true;
  bool _lower = true;
  bool _digits = true;
  bool _symbols = true;
  // Phrase de passe (Diceware)
  int _phraseWords = 5;
  bool _phraseAppendNumber = true;
  String _phraseSeparator = '-';
  String _password = '';

  static const _uppers = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  static const _lowers = 'abcdefghijklmnopqrstuvwxyz';
  static const _nums = '0123456789';
  static const _syms = r'!@#$%^&*()-_=+[]{}|;:,.<>?';

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
    if (_upper) buf.write(_uppers);
    if (_lower) buf.write(_lowers);
    if (_digits) buf.write(_nums);
    if (_symbols) buf.write(_syms);
    if (buf.isEmpty) {
      buf.write(_lowers);
      setState(() => _lower = true);
    }
    final pool = buf.toString();
    final rng = Random.secure();
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
  ///
  /// Utilise les mêmes seuils que [PasswordStrengthService] (80 bits = 1.0)
  /// pour cohérence visuelle avec setup/audit, mais conserve une mesure
  /// d'entropie spécifique au mode (Diceware vs char-pool effectif d'après
  /// les options cochées, pas les classes présentes dans la sortie).
  double _strengthScore() {
    final bits = _entropyBits();
    return (bits / 80.0).clamp(0.0, 1.0);
  }

  double _entropyBits() {
    if (_mode == _Mode.phrase) {
      return DicewareFr.entropy(_phraseWords) +
          (_phraseAppendNumber ? log(90) / ln2 : 0);
    }
    var pool = 0;
    if (_upper) pool += 26;
    if (_lower) pool += 26;
    if (_digits) pool += 10;
    if (_symbols) pool += 26;
    if (pool == 0) return 0;
    return _length * (log(pool) / ln2);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final strength = _strengthScore();
    final sColor = PasswordStrengthService.color(strength);
    final sLabel = PasswordStrengthService.label(strength, t);
    final bits = _entropyBits().round();

    return Scaffold(
      appBar: AppBar(
        title: Text(t.generatorTitle),
        actions: [
          if (widget.returnPassword)
            TextButton(
              onPressed: _password.isNotEmpty
                  ? () => Navigator.pop(context, _password)
                  : null,
              child: Text(
                t.generatorUse,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
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
              child: Column(
                children: [
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
                  Row(
                    children: [
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          // U8 v2.4.4 — Semantics value pour TalkBack.
                          child: LinearProgressIndicator(
                            value: strength,
                            minHeight: 5,
                            backgroundColor: cs.surface,
                            valueColor: AlwaysStoppedAnimation(sColor),
                            semanticsLabel: t.generatorStrengthSuffix(
                              sLabel,
                              bits,
                            ),
                            semanticsValue:
                                '${(strength * 100).round()}% — $sLabel',
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Text(
                        t.generatorStrengthSuffix(sLabel, bits),
                        style: TextStyle(
                          fontSize: 11,
                          color: sColor,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _generate,
                    icon: const Icon(Icons.refresh, size: 18),
                    label: Text(t.generatorGenerate),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () async {
                      final messenger = ScaffoldMessenger.of(context);
                      final dir = Directionality.of(context);
                      await ClipboardService.copyWithAutoClear(_password);
                      messenger.showSnackBar(
                        SnackBar(
                          content: Text(t.generatorCopiedSnack),
                          duration: const Duration(seconds: 2),
                        ),
                      );
                      // ignore: deprecated_member_use — sendAnnouncement requires FlutterView API non-stable.
                      SemanticsService.announce(t.snackbarCopied, dir);
                    },
                    icon: const Icon(Icons.copy, size: 18),
                    label: Text(t.generatorCopy),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),

            // ── Sélecteur de mode ──────────────────────────────────────────
            SegmentedButton<_Mode>(
              segments: [
                ButtonSegment(
                  value: _Mode.chars,
                  label: Text(t.generatorModeChars),
                  icon: const Icon(Icons.tag, size: 16),
                ),
                ButtonSegment(
                  value: _Mode.phrase,
                  label: Text(t.generatorModePhrase),
                  icon: const Icon(Icons.translate, size: 16),
                ),
              ],
              selected: {_mode},
              onSelectionChanged: (s) {
                setState(() => _mode = s.first);
                _generate();
              },
            ),
            const SizedBox(height: 16),

            if (_mode == _Mode.chars)
              ..._buildCharsOptions(cs, t)
            else
              ..._buildPhraseOptions(cs, t),
          ],
        ),
      ),
    );
  }

  List<Widget> _buildCharsOptions(ColorScheme cs, AppLocalizations t) => [
    Text(
      t.generatorLength(_length.round()),
      style: const TextStyle(fontWeight: FontWeight.w600),
    ),
    Slider(
      value: _length,
      min: 8,
      max: 64,
      divisions: 56,
      label: '${_length.round()}',
      onChanged: (v) {
        setState(() => _length = v);
        _generate();
      },
    ),
    const SizedBox(height: 8),
    Text(
      t.generatorCharsHeader,
      style: TextStyle(
        fontWeight: FontWeight.w600,
        fontSize: 13,
        color: cs.onSurfaceVariant,
      ),
    ),
    const SizedBox(height: 8),
    _OptionTile(t.generatorCharsUpper, _upper, (v) {
      setState(() => _upper = v);
      _generate();
    }),
    _OptionTile(t.generatorCharsLower, _lower, (v) {
      setState(() => _lower = v);
      _generate();
    }),
    _OptionTile(t.generatorCharsDigits, _digits, (v) {
      setState(() => _digits = v);
      _generate();
    }),
    _OptionTile(t.generatorCharsSymbols, _symbols, (v) {
      setState(() => _symbols = v);
      _generate();
    }),
  ];

  List<Widget> _buildPhraseOptions(ColorScheme cs, AppLocalizations t) => [
    Text(
      t.generatorPhraseWords(_phraseWords),
      style: const TextStyle(fontWeight: FontWeight.w600),
    ),
    Slider(
      value: _phraseWords.toDouble(),
      min: 3,
      max: 8,
      divisions: 5,
      label: '$_phraseWords',
      onChanged: (v) {
        setState(() => _phraseWords = v.round());
        _generate();
      },
    ),
    const SizedBox(height: 8),
    Text(
      t.generatorOptionsHeader,
      style: TextStyle(
        fontWeight: FontWeight.w600,
        fontSize: 13,
        color: cs.onSurfaceVariant,
      ),
    ),
    const SizedBox(height: 8),
    _OptionTile(t.generatorPhraseAppendNumber, _phraseAppendNumber, (v) {
      setState(() => _phraseAppendNumber = v);
      _generate();
    }),
    Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: ListTile(
        dense: true,
        title: Text(
          t.generatorPhraseSeparator,
          style: const TextStyle(fontSize: 14),
        ),
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
      child: Row(
        children: [
          Icon(Icons.lightbulb_outline, size: 16, color: cs.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              t.generatorPhraseHint,
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
            ),
          ),
        ],
      ),
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
