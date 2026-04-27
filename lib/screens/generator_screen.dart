import 'dart:math';
import 'package:flutter/material.dart';
import '../services/clipboard_service.dart';

class GeneratorScreen extends StatefulWidget {
  final bool returnPassword;
  const GeneratorScreen({super.key, this.returnPassword = false});

  @override
  State<GeneratorScreen> createState() => _GeneratorScreenState();
}

class _GeneratorScreenState extends State<GeneratorScreen> {
  double _length  = 16;
  bool _upper   = true;
  bool _lower   = true;
  bool _digits  = true;
  bool _symbols = true;
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

  double _strengthScore() {
    double s = 0;
    if (_length >= 12) s += 0.3;
    if (_length >= 16) s += 0.2;
    if (_upper && _lower) s += 0.2;
    if (_digits)  s += 0.15;
    if (_symbols) s += 0.15;
    return s.clamp(0.0, 1.0);
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
                  Text(sLabel,
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
          ],
        ),
      ),
    );
  }
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
