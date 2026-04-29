import 'dart:async';
import 'package:biometric_storage/biometric_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show SystemNavigator;
import 'package:shared_preferences/shared_preferences.dart';
import '../services/heritage_service.dart';
import '../services/integrity_service.dart';
import '../services/vault_service.dart';
import 'heir_view_screen.dart';
import 'home_screen.dart';

class UnlockScreen extends StatefulWidget {
  const UnlockScreen({super.key});

  @override
  State<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<UnlockScreen> {
  final _passCtrl = TextEditingController();
  bool _show    = false;
  bool _loading = false;
  String? _error;
  bool _hasBiometric = false;
  int? _lockoutRemaining;
  Timer? _lockoutTimer;

  @override
  void initState() {
    super.initState();
    _checkLockout();
    _checkBiometric();
    _checkIntegrity();
  }

  /// Vérifie root / émulateur / debugger. Avertit l'utilisateur une seule
  /// fois par session si un problème est détecté. L'app fonctionne malgré
  /// tout — c'est purement informatif (best-effort).
  Future<void> _checkIntegrity() async {
    final status = await IntegrityService.check();
    if (!status.hasIssue || !mounted) return;
    final prefs = await SharedPreferences.getInstance();
    // Mémoriser le hash des problèmes détectés pour ne ré-avertir que
    // si la situation change (ex: rootage post-install).
    final fingerprint = status.issues.join('|');
    if (prefs.getString('integrity_warned') == fingerprint) return;
    if (!mounted) return;

    final accepted = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: Icon(Icons.warning_amber_rounded, color: cs.error, size: 36),
          title: const Text('Environnement à risque'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Pass Tech a détecté un environnement potentiellement '
                  'compromis :',
                  style: TextStyle(fontSize: 13),
                ),
                const SizedBox(height: 10),
                ...status.issues.map((i) => Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Row(children: [
                        Icon(Icons.circle, size: 6, color: cs.error),
                        const SizedBox(width: 8),
                        Expanded(child: Text(i,
                            style: const TextStyle(fontSize: 13))),
                      ]),
                    )),
                const SizedBox(height: 14),
                Text(
                  'Sur un appareil rooté, en mode debug ou émulé, le '
                  'coffre-fort est moins protégé : un attaquant local '
                  'peut lire le fichier chiffré, contourner la biométrie '
                  'liée au Keystore, et tenter une attaque hors-ligne sur '
                  'votre mot de passe maître.',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: cs.error.withValues(alpha: 0.10),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                        color: cs.error.withValues(alpha: 0.3)),
                  ),
                  child: const Text(
                    'En continuant, vous reconnaissez avoir été averti et '
                    'utilisez Pass Tech à vos propres risques. Le '
                    'développeur ne peut garantir la confidentialité de '
                    'vos données dans cet environnement.',
                    style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Quitter'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                  backgroundColor: Theme.of(ctx).colorScheme.error),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Continuer à mes risques'),
            ),
          ],
        );
      },
    );
    if (accepted == true) {
      await prefs.setString('integrity_warned', fingerprint);
    } else {
      // L'utilisateur choisit de quitter — fermer l'activité.
      await SystemNavigator.pop();
    }
  }

  @override
  void dispose() {
    _passCtrl.dispose();
    _lockoutTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkLockout() async {
    final remaining = await VaultService().getLockoutRemaining();
    if (!mounted) return;
    setState(() => _lockoutRemaining = remaining);
    if (remaining != null) {
      _lockoutTimer?.cancel();
      _lockoutTimer = Timer.periodic(const Duration(seconds: 1), (_) async {
        final r = await VaultService().getLockoutRemaining();
        if (!mounted) { _lockoutTimer?.cancel(); return; }
        setState(() => _lockoutRemaining = r);
        if (r == null) _lockoutTimer?.cancel();
      });
    }
  }

  Future<void> _checkBiometric() async {
    final canAuth = await BiometricStorage().canAuthenticate();
    final hasKey  = await VaultService().hasBiometricKey;
    final enabled = canAuth == CanAuthenticateResponse.success && hasKey;
    if (mounted) setState(() => _hasBiometric = enabled);
    if (enabled && _lockoutRemaining == null) _tryBiometric();
  }

  Future<void> _tryBiometric() async {
    if (_lockoutRemaining != null) return;
    setState(() { _loading = true; _error = null; });
    // unlockWithBiometric() triggers BiometricPrompt via biometric_storage —
    // the Keystore key is gated by setUserAuthenticationRequired(true), so a
    // successful read implies a successful biometric authentication.
    final result = await VaultService().unlockWithBiometric();
    if (!mounted) return;
    switch (result) {
      case UnlockResult.success:
        // Bio = forcément primary (cf. saveBiometricKey), markActive OK
        await HeritageService().markActive();
        if (!mounted) return;
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
        break;
      case UnlockResult.lockedOut:
        setState(() { _loading = false; _error = null; });
        _checkLockout();
        break;
      case UnlockResult.wrongPassword:
        setState(() { _loading = false; _error = 'Échec biométrique'; });
        break;
    }
  }

  Future<void> _unlock() async {
    final pass = _passCtrl.text;
    if (pass.isEmpty || _lockoutRemaining != null) return;
    setState(() { _loading = true; _error = null; });
    final result = await VaultService().unlock(pass);
    _passCtrl.clear();
    if (!mounted) return;
    switch (result) {
      case UnlockResult.success:
        // Marque l'utilisateur comme actif uniquement si on est sur PRIMARY.
        // Le decoy ne reset pas le timer héritage (sinon un attaquant qui
        // force l'ouverture du leurre prolongerait la vie du dead-man).
        if (!VaultService().isDecoyActive) {
          await HeritageService().markActive();
        }
        if (!mounted) return;
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
        break;
      case UnlockResult.lockedOut:
        setState(() { _loading = false; _error = null; _passCtrl.clear(); });
        _checkLockout();
        break;
      case UnlockResult.wrongPassword:
        setState(() { _loading = false; _error = 'Mot de passe incorrect'; });
        _checkLockout(); // may have just hit threshold
        break;
    }
  }

  /// Délai progressif après échec heir (anti-brute-force complémentaire à
  /// PBKDF2 600k qui limite déjà à ~2 essais/sec).
  int _heirFailCount = 0;

  /// Affiche un dialog avec champ heir password. Si succès, ouvre un écran
  /// HeirView en lecture seule avec les entries de l'héritage.
  Future<void> _unlockAsHeir() async {
    final pwd = await showDialog<String>(
      context: context,
      builder: (_) => const _HeirPasswordDialog(),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return;
    // Délai progressif : 0 / 2 / 4 / 8 / 16 secondes selon l'historique
    if (_heirFailCount > 0) {
      final delay = (1 << (_heirFailCount - 1)) * 1000;
      setState(() => _loading = true);
      await Future.delayed(Duration(milliseconds: delay.clamp(1000, 16000)));
      if (!mounted) return;
    }
    final entries = await HeritageService().unlockAsHeir(pwd);
    if (!mounted) return;
    if (entries == null) {
      _heirFailCount++;
      setState(() {
        _loading = false;
        _error = 'Mot de passe héritier incorrect';
      });
      return;
    }
    _heirFailCount = 0;
    Navigator.of(context).pushReplacement(MaterialPageRoute(
        builder: (_) => HeirViewScreen(entries: entries)));
  }

  String _formatLockout(int seconds) {
    if (seconds < 60) return '${seconds}s';
    final m = seconds ~/ 60;
    final s = seconds % 60;
    return s == 0 ? '${m}min' : '${m}min ${s}s';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final locked = _lockoutRemaining != null;

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
                    color: locked ? cs.error.withValues(alpha: 0.15) : cs.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Icon(locked ? Icons.lock_clock : Icons.lock,
                      size: 44, color: locked ? cs.error : cs.primary),
                ),
                const SizedBox(height: 24),
                Text('Pass Tech',
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 6),
                Text(
                  locked
                      ? 'Trop de tentatives — verrouillé'
                      : 'Entrez votre mot de passe maître',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 32),

                if (locked) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                    decoration: BoxDecoration(
                      color: cs.error.withValues(alpha: 0.10),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: cs.error.withValues(alpha: 0.3)),
                    ),
                    child: Column(children: [
                      Text('Réessayez dans',
                          style: TextStyle(fontSize: 12, color: cs.error)),
                      const SizedBox(height: 4),
                      Text(_formatLockout(_lockoutRemaining!),
                          style: TextStyle(
                              fontSize: 28,
                              fontWeight: FontWeight.w700,
                              color: cs.error,
                              fontFeatures: const [FontFeature.tabularFigures()])),
                    ]),
                  ),
                ] else ...[
                  TextField(
                    controller: _passCtrl,
                    obscureText: !_show,
                    autofocus: true,
                    onSubmitted: (_) => _unlock(),
                    enableSuggestions: false,
                    autocorrect: false,
                    keyboardType: TextInputType.visiblePassword,
                    decoration: InputDecoration(
                      labelText: 'Mot de passe maître',
                      prefixIcon: const Icon(Icons.lock_outline, size: 20),
                      suffixIcon: IconButton(
                        icon: Icon(_show ? Icons.visibility_off : Icons.visibility,
                            size: 20),
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
                      // Accès héritier : visible uniquement si l'inactivité du
                      // propriétaire dépasse le seuil + grâce expirée. Le
                      // FutureBuilder ne renvoie l'option qu'après le check
                      // crypto, pas de leak temporel.
                      FutureBuilder<bool>(
                        future: HeritageService().shouldShowHeirOption(),
                        builder: (_, snap) {
                          if (snap.data != true) return const SizedBox.shrink();
                          return Padding(
                            padding: const EdgeInsets.only(top: 12),
                            child: TextButton.icon(
                              onPressed: _unlockAsHeir,
                              icon: const Icon(Icons.family_restroom, size: 18),
                              label: const Text('Accès héritier'),
                            ),
                          );
                        },
                      ),
                    ]),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Dialog dédié pour saisie du heir password. StatefulWidget pour disposer
/// proprement le TextEditingController (pas de leak du password en clair en
/// RAM jusqu'au GC, contrairement à un Builder inline).
class _HeirPasswordDialog extends StatefulWidget {
  const _HeirPasswordDialog();

  @override
  State<_HeirPasswordDialog> createState() => _HeirPasswordDialogState();
}

class _HeirPasswordDialogState extends State<_HeirPasswordDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    // Wipe le contenu du buffer avant dispose (anti-trace mémoire).
    _ctrl.clear();
    _ctrl.dispose();
    super.dispose();
  }

  void _submit() {
    final v = _ctrl.text;
    _ctrl.clear();
    Navigator.pop(context, v);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      icon: const Icon(Icons.family_restroom, size: 36),
      title: const Text('Accès héritier'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        const Text(
            'Période d\'inactivité du propriétaire dépassée.\n'
            'Saisissez le mot de passe héritier qui vous a été confié.',
            style: TextStyle(fontSize: 12),
            textAlign: TextAlign.center),
        const SizedBox(height: 12),
        TextField(
          controller: _ctrl,
          obscureText: true,
          autofocus: true,
          onSubmitted: (_) => _submit(),
          decoration: const InputDecoration(
            labelText: 'Mot de passe héritier',
            border: OutlineInputBorder(),
          ),
        ),
      ]),
      actions: [
        TextButton(
            onPressed: () { _ctrl.clear(); Navigator.pop(context); },
            child: const Text('Annuler')),
        FilledButton(
            onPressed: _submit,
            child: const Text('Déverrouiller')),
      ],
    );
  }
}
