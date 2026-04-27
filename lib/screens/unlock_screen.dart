import 'package:flutter/material.dart';
import 'package:local_auth/local_auth.dart';
import '../services/vault_service.dart';
import 'home_screen.dart';

class UnlockScreen extends StatefulWidget {
  const UnlockScreen({super.key});

  @override
  State<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<UnlockScreen> {
  final _passCtrl = TextEditingController();
  final _auth     = LocalAuthentication();
  bool _show    = false;
  bool _loading = false;
  String? _error;
  bool _hasBiometric = false;

  @override
  void initState() {
    super.initState();
    _checkBiometric();
  }

  @override
  void dispose() {
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _checkBiometric() async {
    final canCheck = await _auth.canCheckBiometrics;
    final hasKey   = await VaultService().hasBiometricKey;
    final enabled  = canCheck && hasKey;
    if (mounted) setState(() => _hasBiometric = enabled);
    if (enabled) _tryBiometric();
  }

  Future<void> _tryBiometric() async {
    try {
      final ok = await _auth.authenticate(
        localizedReason: 'Déverrouillez votre coffre-fort',
        options: const AuthenticationOptions(biometricOnly: true),
      );
      if (!ok || !mounted) return;
      setState(() { _loading = true; _error = null; });
      final unlocked = await VaultService().unlockWithBiometric();
      if (!mounted) return;
      if (unlocked) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
      } else {
        setState(() { _loading = false; _error = 'Échec biométrique'; });
      }
    } catch (_) {}
  }

  Future<void> _unlock() async {
    final pass = _passCtrl.text;
    if (pass.isEmpty) return;
    setState(() { _loading = true; _error = null; });
    final ok = await VaultService().unlock(pass);
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomeScreen()),
      );
    } else {
      setState(() { _loading = false; _error = 'Mot de passe incorrect'; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
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
                  child: Icon(Icons.lock, size: 44, color: cs.primary),
                ),
                const SizedBox(height: 24),
                Text('Pass Tech',
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 6),
                Text('Entrez votre mot de passe maître',
                    style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 32),

                TextField(
                  controller: _passCtrl,
                  obscureText: !_show,
                  autofocus: true,
                  onSubmitted: (_) => _unlock(),
                  decoration: InputDecoration(
                    labelText: 'Mot de passe maître',
                    prefixIcon: const Icon(Icons.lock_outline, size: 20),
                    suffixIcon: IconButton(
                      icon: Icon(_show ? Icons.visibility_off : Icons.visibility, size: 20),
                      onPressed: () => setState(() => _show = !_show),
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
                    Text('Déchiffrement…',
                        style: Theme.of(context).textTheme.bodySmall),
                  ])
                else
                  Column(children: [
                    FilledButton.icon(
                      onPressed: _unlock,
                      icon: const Icon(Icons.lock_open, size: 18),
                      label: const Text('Déverrouiller'),
                      style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(48)),
                    ),
                    if (_hasBiometric) ...[
                      const SizedBox(height: 12),
                      OutlinedButton.icon(
                        onPressed: _tryBiometric,
                        icon: const Icon(Icons.fingerprint, size: 18),
                        label: const Text('Empreinte / Face ID'),
                        style: OutlinedButton.styleFrom(
                            minimumSize: const Size.fromHeight(44)),
                      ),
                    ],
                  ]),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
