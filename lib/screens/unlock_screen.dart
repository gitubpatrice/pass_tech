import 'dart:async';
import 'package:biometric_storage/biometric_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show SystemNavigator;
import 'package:shared_preferences/shared_preferences.dart';
import '../l10n/app_localizations.dart';
import '../services/heritage_service.dart';
import '../services/integrity_service.dart';
import '../services/vault_service.dart';
import '../widgets/password_text_field.dart';
import 'heir_view_screen.dart';
import 'home_screen.dart';

class UnlockScreen extends StatefulWidget {
  const UnlockScreen({super.key});

  @override
  State<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<UnlockScreen> {
  final _passCtrl = TextEditingController();
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
    _loadHeirFailCount();
  }

  /// P3-3 (v2.2.0) : restaure le compteur d'échecs heir depuis les prefs.
  /// Avant v2.2.0 le compteur était RAM-only — un attaquant pouvait force-close
  /// l'app pour annuler le délai progressif. Désormais persisté.
  Future<void> _loadHeirFailCount() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final n = prefs.getInt(_heirFailCountKey) ?? 0;
      if (mounted) setState(() => _heirFailCount = n);
    } catch (_) {}
  }

  static const _heirFailCountKey = 'heir_fail_count';

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
    final t = AppLocalizations.of(context);
    final accepted = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: Icon(Icons.warning_amber_rounded, color: cs.error, size: 36),
          title: Text(t.integrityTitle),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(t.integrityIntro, style: const TextStyle(fontSize: 13)),
                const SizedBox(height: 10),
                ...status.issues.map(
                  (i) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(
                      children: [
                        Icon(Icons.circle, size: 6, color: cs.error),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(i, style: const TextStyle(fontSize: 13)),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                Text(
                  t.integrityExplanation,
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: cs.error.withValues(alpha: 0.10),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: cs.error.withValues(alpha: 0.3)),
                  ),
                  child: Text(
                    t.integrityAcknowledge,
                    style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(t.actionQuit),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: Theme.of(ctx).colorScheme.error,
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(t.integrityContinueAtRisk),
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
        if (!mounted) {
          _lockoutTimer?.cancel();
          return;
        }
        setState(() => _lockoutRemaining = r);
        if (r == null) _lockoutTimer?.cancel();
      });
    }
  }

  Future<void> _checkBiometric() async {
    final canAuth = await BiometricStorage().canAuthenticate();
    final hasKey = await VaultService().hasBiometricKey;
    final enabled = canAuth == CanAuthenticateResponse.success && hasKey;
    if (mounted) setState(() => _hasBiometric = enabled);
    if (enabled && _lockoutRemaining == null) _tryBiometric();
  }

  Future<void> _tryBiometric() async {
    if (_lockoutRemaining != null) return;
    setState(() {
      _loading = true;
      _error = null;
    });
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
        setState(() {
          _loading = false;
          _error = null;
        });
        _checkLockout();
        break;
      case UnlockResult.wrongPassword:
        if (!mounted) return;
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _error = t.unlockBiometricFailed;
        });
        break;
      case UnlockResult.biometricInvalidated:
        // (v2.4.2) La clé Keystore est morte (ré-enrôlement d'empreinte
        // Android typiquement). VaultService a déjà supprimé le wrap →
        // on masque le bouton biométrique et on affiche un message clair
        // qui invite à utiliser le master password puis à réactiver la
        // biométrie depuis Réglages.
        if (!mounted) return;
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _hasBiometric = false;
          _error = t.unlockBiometricEnrollmentChanged;
        });
        break;
    }
  }

  Future<void> _unlock() async {
    final pass = _passCtrl.text;
    if (pass.isEmpty || _lockoutRemaining != null) return;
    setState(() {
      _loading = true;
      _error = null;
    });
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
        setState(() {
          _loading = false;
          _error = null;
          _passCtrl.clear();
        });
        _checkLockout();
        break;
      case UnlockResult.wrongPassword:
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _error = t.unlockWrongPassword;
        });
        _checkLockout(); // may have just hit threshold
        break;
      case UnlockResult.biometricInvalidated:
        // Non-émis depuis l'unlock par mot de passe ; cas inclus pour
        // satisfaire l'exhaustivité de l'enum. Traité comme un échec
        // standard de saisie pour éviter tout comportement bizarre si
        // un futur refactor venait à le faire remonter ici.
        if (!mounted) return;
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _error = t.unlockWrongPassword;
        });
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
      // P3-3 : persiste pour résister à un force-close (sinon l'attaquant
      // peut reset le délai progressif en relançant l'app).
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setInt(_heirFailCountKey, _heirFailCount);
      } catch (_) {}
      if (!mounted) return;
      final t = AppLocalizations.of(context);
      setState(() {
        _loading = false;
        _error = t.unlockHeirWrongPassword;
      });
      return;
    }
    _heirFailCount = 0;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_heirFailCountKey);
    } catch (_) {}
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => HeirViewScreen(entries: entries)),
    );
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
    final t = AppLocalizations.of(context);
    final locked = _lockoutRemaining != null;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ExcludeSemantics(
                  child: Container(
                    width: 80,
                    height: 80,
                    decoration: BoxDecoration(
                      color: locked
                          ? cs.error.withValues(alpha: 0.15)
                          : cs.primaryContainer,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Icon(
                      locked ? Icons.lock_clock : Icons.lock,
                      size: 44,
                      color: locked ? cs.error : cs.primary,
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  t.appTitle,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 6),
                Text(
                  locked ? t.unlockTooManyAttempts : t.unlockEnterMaster,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 32),

                if (locked) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 16,
                    ),
                    decoration: BoxDecoration(
                      color: cs.error.withValues(alpha: 0.10),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: cs.error.withValues(alpha: 0.3),
                      ),
                    ),
                    child: Column(
                      children: [
                        Text(
                          t.unlockTryAgainIn,
                          style: TextStyle(fontSize: 12, color: cs.error),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _formatLockout(_lockoutRemaining!),
                          style: TextStyle(
                            fontSize: 28,
                            fontWeight: FontWeight.w700,
                            color: cs.error,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
                  ),
                ] else ...[
                  PasswordTextField(
                    controller: _passCtrl,
                    labelText: t.unlockMasterLabel,
                    autofocus: true,
                    onSubmitted: (_) => _unlock(),
                  ),

                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      _error!,
                      style: TextStyle(color: cs.error, fontSize: 13),
                    ),
                  ],

                  const SizedBox(height: 24),

                  if (_loading)
                    Column(
                      children: [
                        const CircularProgressIndicator(),
                        const SizedBox(height: 12),
                        Text(
                          t.unlockDecrypting,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    )
                  else
                    Column(
                      children: [
                        FilledButton.icon(
                          onPressed: _unlock,
                          icon: const Icon(Icons.lock_open, size: 18),
                          label: Text(t.unlockCta),
                          style: FilledButton.styleFrom(
                            minimumSize: const Size.fromHeight(48),
                          ),
                        ),
                        if (_hasBiometric) ...[
                          const SizedBox(height: 12),
                          OutlinedButton.icon(
                            onPressed: _tryBiometric,
                            icon: const Icon(Icons.fingerprint, size: 18),
                            label: Text(t.unlockBiometricCta),
                            style: OutlinedButton.styleFrom(
                              minimumSize: const Size.fromHeight(48),
                            ),
                          ),
                        ],
                        // Accès héritier : visible uniquement si l'inactivité du
                        // propriétaire dépasse le seuil + grâce expirée. Le
                        // FutureBuilder ne renvoie l'option qu'après le check
                        // crypto, pas de leak temporel.
                        FutureBuilder<bool>(
                          future: HeritageService().shouldShowHeirOption(),
                          builder: (_, snap) {
                            if (snap.data != true) {
                              return const SizedBox.shrink();
                            }
                            return Padding(
                              padding: const EdgeInsets.only(top: 12),
                              child: TextButton.icon(
                                onPressed: _unlockAsHeir,
                                icon: const Icon(
                                  Icons.family_restroom,
                                  size: 18,
                                ),
                                label: Text(t.unlockHeirCta),
                              ),
                            );
                          },
                        ),
                      ],
                    ),
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
    final t = AppLocalizations.of(context);
    return AlertDialog(
      icon: const Icon(Icons.family_restroom, size: 36),
      title: Text(t.heirDialogTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            t.heirDialogBody,
            style: const TextStyle(fontSize: 12),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _ctrl,
            obscureText: true,
            autofocus: true,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: t.heirPasswordLabel,
              border: const OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            _ctrl.clear();
            Navigator.pop(context);
          },
          child: Text(t.actionCancel),
        ),
        FilledButton(onPressed: _submit, child: Text(t.actionUnlock)),
      ],
    );
  }
}
