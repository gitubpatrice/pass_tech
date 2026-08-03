import 'dart:async';
import 'package:biometric_storage/biometric_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show SystemNavigator;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../l10n/app_localizations.dart';
import '../services/heritage_service.dart';
import '../services/integrity_service.dart';
import '../services/panic_service.dart';
import '../utils/snack_utils.dart';
import '../services/vault_service.dart';
import '../widgets/password_text_field.dart';
import 'heir_view_screen.dart';
import 'home_screen.dart';

class UnlockScreen extends StatefulWidget {
  const UnlockScreen({super.key});

  @override
  State<UnlockScreen> createState() => UnlockScreenState();
}

/// Rendu public (et non `_UnlockScreenState`) pour que `main.dart` puisse
/// interroger [estAffiche] avant de pousser un nouvel écran de déverrouillage.
class UnlockScreenState extends State<UnlockScreen> {
  /// UX 2026-08-03 — nombre d'écrans de déverrouillage vivants.
  ///
  /// Lu par `main.dart` avant de pousser un écran de déverrouillage au retour
  /// au premier plan : sans ce compteur, il en empilait un NOUVEAU alors qu'un
  /// autre était déjà affiché, en écrasant la pile au passage.
  static int _instancesVivantes = 0;

  /// Vrai si un écran de déverrouillage est déjà à l'écran.
  static bool get estAffiche => _instancesVivantes > 0;

  /// UX 2026-08-03 — l'invite biométrique automatique n'est tentée qu'UNE fois
  /// par cycle de verrouillage.
  ///
  /// Défaut signalé en usage réel : au lancement, annuler l'invite biométrique
  /// la faisait revenir aussitôt, en boucle, sans jamais laisser saisir le mot
  /// de passe maître — et le bouton Retour n'y changeait rien.
  ///
  /// Enchaînement : l'invite est un dialogue système, elle met l'application en
  /// arrière-plan. À l'annulation, l'application revient au premier plan, le
  /// cycle de vie constate que le coffre est fermé et POUSSE un nouvel écran de
  /// déverrouillage en vidant la pile. Ce nouvel écran relance l'invite dans
  /// son `initState`, et ainsi de suite.
  ///
  /// Annuler l'invite est une intention claire : « je veux taper mon mot de
  /// passe ». On la respecte. Le bouton empreinte reste disponible pour la
  /// relancer volontairement, et le drapeau est remis à zéro par un
  /// déverrouillage réussi, pour que le cycle suivant reproposeà nouveau la
  /// biométrie.
  static bool _inviteBioDejaTentee = false;

  final _passCtrl = TextEditingController();
  bool _loading = false;
  String? _error;
  bool _hasBiometric = false;
  int? _lockoutRemaining;
  Timer? _lockoutTimer;

  /// AUDIT 2026-08-03 — `Future` évalué UNE fois, à la création de l'écran.
  ///
  /// Il était auparavant construit directement dans `build()`
  /// (`future: HeritageService().shouldShowHeirOption()`), donc relancé à
  /// chaque reconstruction — et il y en a à chaque frappe d'erreur, chaque
  /// bascule de chargement, chaque retour de biométrie. Chaque relance
  /// enchaînait quatre lectures de stockage sécurisé **et un effet de bord
  /// d'écriture** : `shouldShowHeirOption` appelle `startGraceIfNeeded`, qui
  /// persiste le début du délai de grâce. Déclencher une écriture depuis une
  /// méthode de rendu est une faute de conception en soi ; ici elle portait sur
  /// l'horloge du dispositif d'héritage.
  late final Future<bool> _heirOptionFuture;

  /// SEC 2026-08-03 — le camouflage doit pouvoir être défait SANS ouvrir le
  /// coffre.
  ///
  /// Défaut constaté en usage réel, pas en audit : « Révéler l'application »
  /// n'existait que dans les Réglages, donc derrière le déverrouillage. Un
  /// propriétaire qui active la panique puis ne retrouve plus son mot de passe
  /// maître se retrouve avec une application **définitivement déguisée en
  /// calculatrice** sur son lanceur, sans aucun moyen de revenir en arrière —
  /// alors que le camouflage est réversible par conception.
  ///
  /// `CalculatorActivity` documentait déjà ce piège pour un code numérique
  /// oublié (« le bouton Révéler vit dans les Réglages, devenus
  /// inatteignables ») ; il n'avait pas été vu qu'il vaut à l'identique pour un
  /// mot de passe oublié, cas autrement plus fréquent.
  ///
  /// Aucune fuite pour le déni plausible : pour lire cet écran il faut déjà
  /// être sorti de la calculatrice, donc le camouflage est de toute façon
  /// tombé. Et l'action ne touche QUE l'icône du lanceur — elle n'ouvre rien,
  /// ne déchiffre rien, ne révèle aucune donnée.
  late final Future<bool> _disguisedFuture;

  @override
  void initState() {
    super.initState();
    _instancesVivantes++;
    _heirOptionFuture = HeritageService().shouldShowHeirOption();
    _disguisedFuture = PanicService.isDisguised();
    _checkLockout();
    _checkBiometric();
    _checkIntegrity();
    _loadHeirFailCount();
  }

  /// P3-3 (v2.2.0) : restaure le compteur d'échecs heir depuis le stockage sécurisé.
  /// Avant v2.2.0 le compteur était RAM-only — un attaquant pouvait force-close
  /// l'app pour annuler le délai progressif.
  /// v2.5.0 (F1) : migration SharedPreferences → FlutterSecureStorage. L'ancien
  /// stockage était lisible/modifiable en clair par un process root (reset du
  /// compteur possible). FSS persiste désormais via les ciphers internes de
  /// flutter_secure_storage v10 (plus EncryptedSharedPreferences). Lit l'ancienne
  /// clé `heir_fail_count` une dernière fois pour migrer puis la purge.
  Future<void> _loadHeirFailCount() async {
    try {
      final stored = await _secureStorage.read(key: _heirFailCountKey);
      var n = int.tryParse(stored ?? '') ?? 0;
      // Migration one-shot depuis l'ancienne clé SharedPreferences (< v2.5.0).
      if (n == 0) {
        try {
          final prefs = await SharedPreferences.getInstance();
          final legacy = prefs.getInt('heir_fail_count');
          if (legacy != null && legacy > 0) {
            n = legacy;
            await _secureStorage.write(
              key: _heirFailCountKey,
              value: n.toString(),
            );
          }
          // Purge l'ancienne clé qu'elle ait existé ou non (pas d'erreur si absente).
          await prefs.remove('heir_fail_count');
        } catch (_) {}
      }
      if (mounted) setState(() => _heirFailCount = n);
    } catch (_) {}
  }

  static const _heirFailCountKey = 'pt_heir_fail_count';
  static const FlutterSecureStorage _secureStorage = FlutterSecureStorage();

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
            // v2.5.0 (F11) : hiérarchie inversée. "Continuer quand même" est
            // l'action risquée — elle doit être visuellement moins dominante
            // que "Quitter" (action sûre). Ancien layout (TextButton Quitter +
            // FilledButton.error Continuer) attirait l'œil sur l'action
            // dangereuse → risque de clic réflexe.
            TextButton(
              style: TextButton.styleFrom(foregroundColor: cs.error),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(t.integrityContinueAtRisk),
            ),
            FilledButton(
              autofocus: true,
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(t.actionQuit),
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
    // SEC 2026-08-03 — effacement du tampon AVANT libération.
    //
    // Tous les autres champs sensibles de l'app le font depuis B8/B9 v2.3.8
    // (`_PassphraseDialog`, `_ChangePasswordDialog`, `_HeirPasswordDialog`,
    // l'écran d'édition d'entrée). Le champ du MOT DE PASSE MAÎTRE — le seul
    // secret dont dépendent tous les autres — était le seul à ne pas le faire.
    // Une saisie en cours au moment où l'écran est détruit restait dans le
    // tampon du contrôleur jusqu'au passage du ramasse-miettes.
    _passCtrl.clear();
    _passCtrl.dispose();
    _lockoutTimer?.cancel();
    _instancesVivantes--;
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
    // `mounted` requis : `_tryBiometric` démarre par un setState inconditionnel.
    // Si l'écran est disposé pendant les await de _checkBiometric, l'appel
    // provoquerait « setState after dispose ».
    //
    // UX 2026-08-03 — `_inviteBioDejaTentee` casse la boucle d'invite décrite
    // sur ce drapeau. Une annulation ne doit plus jamais relancer l'invite
    // toute seule ; c'est au bouton empreinte de le faire, sur geste explicite.
    if (enabled &&
        _lockoutRemaining == null &&
        mounted &&
        !_inviteBioDejaTentee) {
      _inviteBioDejaTentee = true;
      _tryBiometric();
    }
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
    final UnlockResult result;
    try {
      result = await VaultService().unlockWithBiometric();
    } catch (e) {
      // AUDIT 2026-08-03 — même filet que `_unlock()` : jamais d'indicateur de
      // progression bloqué sur l'écran de déverrouillage.
      if (!mounted) return;
      final t = AppLocalizations.of(context);
      setState(() {
        _loading = false;
        _error = t.genericError('$e');
      });
      return;
    }
    if (!mounted) return;
    switch (result) {
      case UnlockResult.success:
        // UX 2026-08-03 — le coffre s'ouvre : le prochain cycle de
        // verrouillage aura de nouveau droit à l'invite automatique.
        _inviteBioDejaTentee = false;
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
      case UnlockResult.busy:
        // AUDIT 2026-08-03 — une autre ouverture est déjà en cours (typiquement
        // l'utilisateur a validé son mot de passe puis posé son doigt). Rien
        // n'a été tenté, donc aucun essai n'est consommé : on invite juste à
        // recommencer, sans laisser entendre que la biométrie a échoué.
        if (!mounted) return;
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _error = t.vaultBusyRetry;
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
    final UnlockResult result;
    try {
      result = await VaultService().unlock(pass);
    } catch (e) {
      // AUDIT 2026-08-03 — filet de dernier recours.
      //
      // `VaultService.unlock()` est désormais fail-closed en interne, mais rien
      // ne protégeait CET appel : la moindre exception qui remontait laissait
      // `_loading` à `true`, donc un indicateur de progression PERMANENT, sans
      // message, sans bouton, sans issue — et le même écran au relancement.
      // La règle vaut au-delà de ce cas précis : sur l'écran de déverrouillage,
      // aucun chemin ne doit pouvoir laisser l'utilisateur devant un coffre
      // qu'il ne peut ni ouvrir ni comprendre.
      _passCtrl.clear();
      if (!mounted) return;
      final t = AppLocalizations.of(context);
      setState(() {
        _loading = false;
        _error = t.genericError('$e');
      });
      return;
    }
    _passCtrl.clear();
    if (!mounted) return;
    switch (result) {
      case UnlockResult.success:
        // UX 2026-08-03 — idem : ouverture réussie par mot de passe, on
        // réarme l'invite biométrique pour le cycle suivant.
        _inviteBioDejaTentee = false;
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
      case UnlockResult.busy:
        // AUDIT 2026-08-03 — double-appui sur « Déverrouiller ». Avant, ce cas
        // empruntait `wrongPassword` : la saisie était pourtant bonne et aucun
        // essai n'avait été consommé, mais l'écran annonçait « mot de passe
        // incorrect ». Sur l'écran le plus sensible de l'app, c'est une
        // fausse alerte que l'utilisateur ne peut pas distinguer d'une vraie.
        if (!mounted) return;
        final t = AppLocalizations.of(context);
        setState(() {
          _loading = false;
          _error = t.vaultBusyRetry;
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
    // SEC 2026-08-03 (Gemini PT-002) — l'essai est persisté AVANT la
    // vérification, et non après.
    //
    // P3-3 v2.2.0 avait bien vu qu'un compteur en RAM se remettait à zéro en
    // relançant l'app, et l'avait donc persisté. Mais l'écriture restait
    // APRÈS `unlockAsHeir`, c'est-à-dire après une seconde d'Argon2id : fermer
    // l'application pendant ce calcul suffisait à annuler l'essai, et le délai
    // progressif redevenait contournable exactement comme avant P3-3. Le
    // stockage avait été durci, pas le moment de l'écriture.
    _heirFailCount++;
    try {
      await _secureStorage.write(
        key: _heirFailCountKey,
        value: _heirFailCount.toString(),
      );
    } catch (_) {}

    final entries = await HeritageService().unlockAsHeir(pwd);
    if (!mounted) return;
    if (entries == null) {
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
      await _secureStorage.delete(key: _heirFailCountKey);
    } catch (_) {}
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => HeirViewScreen(entries: entries)),
    );
  }

  /// Rétablit l'icône et le nom Pass Tech sur le lanceur, sans déverrouiller.
  ///
  /// Volontairement SANS confirmation : quand on arrive ici, on a déjà traversé
  /// la calculatrice, donc le camouflage ne protège plus rien. Un dialogue de
  /// plus ne ferait qu'ajouter un obstacle à quelqu'un qui cherche justement à
  /// sortir d'une situation bloquée.
  Future<void> _revealFromLockScreen() async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    await PanicService.revealApp();
    if (!mounted) return;
    setState(() {}); // masque le bouton, le camouflage n'est plus actif
    SnackUtils.showInfo(
      messenger,
      t.panicRevealSnack,
      duration: const Duration(seconds: 5),
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
                    // U6 v2.4.3 — Semantics.liveRegion (cf. setup_screen).
                    Semantics(
                      liveRegion: true,
                      label: t.unlockDecrypting,
                      child: Column(
                        children: [
                          const CircularProgressIndicator(),
                          const SizedBox(height: 12),
                          Text(
                            t.unlockDecrypting,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
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
                        // SEC 2026-08-03 — sortie de secours du camouflage,
                        // accessible SANS ouvrir le coffre. Voir
                        // `_disguisedFuture`. N'apparaît que si le camouflage
                        // est effectivement actif.
                        FutureBuilder<bool>(
                          future: _disguisedFuture,
                          builder: (_, snap) {
                            if (snap.data != true) {
                              return const SizedBox.shrink();
                            }
                            return Padding(
                              padding: const EdgeInsets.only(top: 12),
                              child: TextButton.icon(
                                onPressed: _revealFromLockScreen,
                                icon: const Icon(Icons.visibility, size: 18),
                                label: Text(t.panicRevealTitle),
                              ),
                            );
                          },
                        ),
                        FutureBuilder<bool>(
                          future: _heirOptionFuture,
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
          // F8/U1 v2.4.4 — `PasswordTextField` au lieu de `TextField` brut.
          // Avant : pas de `autofillHints:[]` (l'héritier saisit son passphrase
          // dans un champ où Autofill Android pouvait proposer / capter la
          // valeur), pas de `enableInteractiveSelection: !show` (long-press
          // → "Tout sélectionner" → "Copier" exposait le password masqué au
          // clipboard tiers), pas de `keyboardType: visiblePassword` ni
          // `enableSuggestions: false`. Régression par rapport à v2.4.3 U1
          // qui a corrigé le master password mais avait oublié ce dialog.
          PasswordTextField(
            controller: _ctrl,
            labelText: t.heirPasswordLabel,
            autofocus: true,
            onSubmitted: (_) => _submit(),
            showPrefixIcon: false,
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
