import 'package:flutter/material.dart';
import '../services/heritage_service.dart';
import '../services/vault_service.dart';
import 'home_screen.dart';

class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  final _pass1 = TextEditingController();
  final _pass2 = TextEditingController();
  bool _show1 = false, _show2 = false;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _pass1.dispose();
    _pass2.dispose();
    super.dispose();
  }

  double _strength(String p) {
    if (p.isEmpty) return 0;
    double s = 0;
    if (p.length >= 12) s += 0.25;
    if (p.length >= 16) s += 0.20;
    if (p.length >= 20) s += 0.10;
    if (p.contains(RegExp(r'[A-Z]')))       s += 0.10;
    if (p.contains(RegExp(r'[a-z]')))       s += 0.10;
    if (p.contains(RegExp(r'[0-9]')))       s += 0.10;
    if (p.contains(RegExp(r'[^A-Za-z0-9]')))s += 0.15;
    return s.clamp(0.0, 1.0);
  }

  Color _strengthColor(double s) {
    if (s < 0.35) return const Color(0xFFE53935);
    if (s < 0.65) return const Color(0xFFFF7043);
    if (s < 0.85) return const Color(0xFFFDD835);
    return const Color(0xFF43A047);
  }

  String _strengthLabel(double s) {
    if (s < 0.35) return 'Faible';
    if (s < 0.65) return 'Moyen';
    if (s < 0.85) return 'Fort';
    return 'Très fort';
  }

  Future<void> _create() async {
    final p1 = _pass1.text;
    final p2 = _pass2.text;
    if (p1.length < 12) {
      setState(() => _error = 'Minimum 12 caractères');
      return;
    }
    if (_strength(p1) < 0.6) {
      setState(() => _error = 'Mot de passe trop faible — variez majuscules, chiffres, symboles');
      return;
    }
    if (p1 != p2) {
      setState(() => _error = 'Les mots de passe ne correspondent pas');
      return;
    }
    setState(() { _loading = true; _error = null; });
    await VaultService().createVault(p1);
    // Initialise le timestamp dead-man : le compteur d'inactivité commence
    // à 0 dès la création du vault.
    await HeritageService().markActive();
    _pass1.clear();
    _pass2.clear();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const HomeScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final s  = _strength(_pass1.text);

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 80, height: 80,
                  decoration: BoxDecoration(
                    color: cs.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Icon(Icons.lock_outline, size: 44, color: cs.primary),
                ),
                const SizedBox(height: 24),
                Text('Créer votre coffre-fort',
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                Text(
                  'Choisissez un mot de passe maître fort.\nIl chiffre toutes vos données en local.',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 32),

                TextField(
                  controller: _pass1,
                  obscureText: !_show1,
                  enableSuggestions: false,
                  autocorrect: false,
                  keyboardType: TextInputType.visiblePassword,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    labelText: 'Mot de passe maître (min. 12)',
                    prefixIcon: const Icon(Icons.lock_outline, size: 20),
                    suffixIcon: IconButton(
                      icon: Icon(_show1 ? Icons.visibility_off : Icons.visibility, size: 20),
                      onPressed: () => setState(() => _show1 = !_show1),
                    ),
                    border: const OutlineInputBorder(),
                  ),
                ),

                if (_pass1.text.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Row(children: [
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: LinearProgressIndicator(
                          value: s,
                          minHeight: 6,
                          backgroundColor: cs.surfaceContainerHighest,
                          valueColor: AlwaysStoppedAnimation(_strengthColor(s)),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Text(_strengthLabel(s),
                        style: TextStyle(
                            fontSize: 12,
                            color: _strengthColor(s),
                            fontWeight: FontWeight.w600)),
                  ]),
                ],

                const SizedBox(height: 16),

                TextField(
                  controller: _pass2,
                  obscureText: !_show2,
                  enableSuggestions: false,
                  autocorrect: false,
                  keyboardType: TextInputType.visiblePassword,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    labelText: 'Confirmer le mot de passe',
                    prefixIcon: const Icon(Icons.lock_outline, size: 20),
                    suffixIcon: IconButton(
                      icon: Icon(_show2 ? Icons.visibility_off : Icons.visibility, size: 20),
                      onPressed: () => setState(() => _show2 = !_show2),
                    ),
                    border: const OutlineInputBorder(),
                  ),
                ),

                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: cs.error, fontSize: 13)),
                ],

                const SizedBox(height: 24),

                if (_loading)
                  Column(children: [
                    const CircularProgressIndicator(),
                    const SizedBox(height: 12),
                    Text('Chiffrement en cours…',
                        style: Theme.of(context).textTheme.bodySmall),
                  ])
                else
                  FilledButton.icon(
                    onPressed: _create,
                    icon: const Icon(Icons.shield_outlined, size: 18),
                    label: const Text('Créer le coffre-fort'),
                    style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(48)),
                  ),

                const SizedBox(height: 24),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Icon(Icons.info_outline, size: 16, color: cs.onSurfaceVariant),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Ce mot de passe ne peut pas être récupéré. Si vous l\'oubliez, vos données seront inaccessibles.',
                        style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                      ),
                    ),
                  ]),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
