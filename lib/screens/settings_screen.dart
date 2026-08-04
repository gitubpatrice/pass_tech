import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
// ignore: unnecessary_import
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart' show HapticFeedback;
import 'package:biometric_storage/biometric_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/entry.dart';
import '../main.dart' show prefKeyScreenshotProtection;
import '../services/anti_phishing_service.dart';
import '../services/backup_reminder.dart';
import '../services/password_policy.dart';
import '../services/clipboard_service.dart';
import '../services/heritage_service.dart';
import '../services/import_export_service.dart';
import '../services/panic_service.dart';
import '../services/secure_window.dart';
import '../services/vault_service.dart';
import '../utils/snack_utils.dart';
import '../widgets/password_text_field.dart';
import '../widgets/destructive.dart';
import '../l10n/app_localizations.dart';
import '../main.dart'
    show
        themeNotifier,
        parseThemeMode,
        themeModeToString,
        localeNotifier,
        parseLocale,
        localeToString,
        prefKeyLocale;
import 'audit_screen.dart';
import 'setup_screen.dart';
import 'unlock_screen.dart';

class SettingsScreen extends StatefulWidget {
  final VoidCallback onChanged;
  const SettingsScreen({super.key, required this.onChanged});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  bool _biometricEnabled = false;
  bool _biometricAvailable = false;

  /// v2.3.11 — état du toggle FLAG_SECURE persisté.
  bool _screenshotProtectionEnabled = true;
  int _clipboardClear = 30;
  int _autoLockSeconds = 300;
  ThemeMode _themeMode = ThemeMode.system;
  Locale? _locale;
  bool _antiPhishingEnabled = false;
  bool _antiPhishingASActive = false;

  List<({String label, int value})> _clipOptions(AppLocalizations t) => [
    (label: t.settingsClipboard15s, value: 15),
    (label: t.settingsClipboard30s, value: 30),
    (label: t.settingsClipboard60s, value: 60),
    (label: t.settingsClipboardNever, value: 0),
  ];

  List<({String label, int value})> _lockOptions(AppLocalizations t) => [
    (label: t.settingsAutoLockImmediate, value: 0),
    (label: t.settingsAutoLock1Min, value: 60),
    (label: t.settingsAutoLock5Min, value: 300),
    (label: t.settingsAutoLock15Min, value: 900),
    (label: t.settingsAutoLock30Min, value: 1800),
    (label: t.settingsAutoLockNever, value: -1),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadSettings();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Au retour des Réglages d'accessibilité Android, l'utilisateur peut
      // avoir activé/désactivé l'AS — on rafraîchit l'état.
      _refreshAntiPhishingASState();
    }
  }

  Future<void> _refreshAntiPhishingASState() async {
    final active = await AntiPhishingService().isAccessibilityServiceActive;
    if (mounted && active != _antiPhishingASActive) {
      setState(() => _antiPhishingASActive = active);
    }
  }

  Future<void> _loadSettings() async {
    final canAuth = await BiometricStorage().canAuthenticate();
    final canCheck = canAuth == CanAuthenticateResponse.success;
    final hasKey = await VaultService().hasBiometricKey;
    final prefs = await SharedPreferences.getInstance();
    final clip = prefs.getInt('clipboard_clear') ?? 30;
    final lock = prefs.getInt('auto_lock_seconds') ?? 300;
    final theme = parseThemeMode(prefs.getString('theme_mode') ?? 'system');
    final loc = parseLocale(prefs.getString(prefKeyLocale));
    final apSvc = AntiPhishingService();
    final apEnabled = await apSvc.isEnabled;
    final apASActive = await apSvc.isAccessibilityServiceActive;
    final screenshotProt = prefs.getBool(prefKeyScreenshotProtection) ?? true;
    ClipboardService.clearAfterSeconds = clip;
    if (mounted) {
      setState(() {
        _biometricAvailable = canCheck;
        _biometricEnabled = canCheck && hasKey;
        _clipboardClear = clip;
        _autoLockSeconds = lock;
        _themeMode = theme;
        _locale = loc;
        _antiPhishingEnabled = apEnabled;
        _antiPhishingASActive = apASActive;
        _screenshotProtectionEnabled = screenshotProt;
      });
    }
  }

  /// v2.3.11 — toggle FLAG_SECURE. Demande confirmation avant désactivation.
  /// La désactivation marque la window comme "user-disabled" via
  /// [SecureWindow.applyUserPreference] qui retire le flag immédiatement
  /// ET bloque tout futur `init/relax/restore`. La réactivation requiert
  /// un redémarrage de l'app pour que Knox ré-applique sa logique sécurité.
  Future<void> _toggleScreenshotProtection(bool v) async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);

    // Désactivation : confirmation explicite (impact sécu).
    if (!v) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(
            Icons.warning_amber_outlined,
            color: Theme.of(ctx).colorScheme.error,
          ),
          title: Text(t.settingsScreenshotProtectionConfirmOffTitle),
          content: Text(t.settingsScreenshotProtectionConfirmOffBody),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(t.actionCancel),
            ),
            DestructiveButton(
              onPressed: () => Navigator.pop(ctx, true),
              label: t.actionDisable,
            ),
          ],
        ),
      );
      if (ok != true) return;
    }

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(prefKeyScreenshotProtection, v);
    // Applique immédiatement côté window (best-effort — Knox peut retenir
    // l'état marqué initialement, d'où le rappel "redémarrez" ci-dessous).
    await SecureWindow.applyUserPreference(enabled: v);
    if (v) {
      // Si on remet la protection, on relance init() pour reposer le flag.
      // ignore: unawaited_futures
      SecureWindow.init();
    }
    if (!mounted) return;
    setState(() => _screenshotProtectionEnabled = v);
    SnackUtils.showInfo(
      messenger,
      t.settingsScreenshotProtectionRestart,
      duration: const Duration(seconds: 4),
    );
  }

  Future<void> _toggleAntiPhishing(bool v) async {
    final t = AppLocalizations.of(context);
    if (v) {
      // Consent flow : explique le service d'accessibilité et ouvre les
      // Réglages Android (l'utilisateur DOIT activer manuellement).
      final go = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          icon: const Icon(Icons.verified_user_outlined, size: 36),
          title: Text(t.settingsAntiPhishingDialogTitle),
          content: Text(
            t.settingsAntiPhishingDialogBody,
            style: const TextStyle(fontSize: 12),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(t.actionCancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(t.actionContinue),
            ),
          ],
        ),
      );
      if (go != true || !mounted) return;
      await AntiPhishingService().setEnabled(true);
      await AntiPhishingService().openAccessibilitySettings();
    } else {
      await AntiPhishingService().setEnabled(false);
    }
    // Re-checke l'état (l'utilisateur peut être revenu sans avoir activé l'AS)
    final apASActive = await AntiPhishingService().isAccessibilityServiceActive;
    if (mounted) {
      setState(() {
        _antiPhishingEnabled = v;
        _antiPhishingASActive = apASActive;
      });
    }
  }

  Future<void> _setTheme(ThemeMode m) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('theme_mode', themeModeToString(m));
    themeNotifier.value = m;
    if (mounted) setState(() => _themeMode = m);
  }

  String _themeLabelOf(AppLocalizations t) {
    switch (_themeMode) {
      case ThemeMode.light:
        return t.settingsThemeLight;
      case ThemeMode.dark:
        return t.settingsThemeDark;
      case ThemeMode.system:
        return t.settingsThemeSystem;
    }
  }

  Future<void> _setLocale(Locale? l) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(prefKeyLocale, localeToString(l));
    localeNotifier.value = l;
    if (mounted) {
      setState(() => _locale = l);
      // a11y v2.3.4 : annonce TalkBack du changement de langue effectif.
      final t = AppLocalizations.of(context);
      // ignore: deprecated_member_use — sendAnnouncement requires FlutterView API non-stable.
      SemanticsService.announce(_localeLabel(t), Directionality.of(context));
    }
  }

  String _localeLabel(AppLocalizations t) {
    if (_locale == null) return t.settingsLanguageSystem;
    if (_locale!.languageCode == 'fr') return t.settingsLanguageFrench;
    if (_locale!.languageCode == 'en') return t.settingsLanguageEnglish;
    return t.settingsLanguageSystem;
  }

  Future<void> _setAutoLock(int v) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('auto_lock_seconds', v);
    if (mounted) setState(() => _autoLockSeconds = v);
  }

  /// Retourne [enabled, thresholdDays, inactivityDays] pour l'UI Héritage.
  Future<List<dynamic>> _loadHeritageState() async {
    final h = HeritageService();
    return [
      await h.isEnabled,
      await h.getThresholdDays(),
      await h.getInactivityDays(),
    ];
  }

  Future<void> _setupHeritage() async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    // Avertissement explicatif
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(Icons.family_restroom, size: 36),
        title: Text(t.heritageSetupTitle),
        content: Text(
          t.heritageSetupBody,
          style: const TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(t.actionCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(t.heritageConfigure),
          ),
        ],
      ),
    );
    if (go != true || !mounted) return;

    // Refus si pas dans le primary (l'héritage doit refléter le vrai coffre)
    if (VaultService().isDecoyActive) {
      SnackUtils.showInfo(messenger, t.heritageDecoyActiveSnack);
      return;
    }

    final pwd = await showDialog<String>(
      context: context,
      builder: (_) =>
          _PassphraseDialog(title: t.heirPasswordPromptTitle, confirm: true),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return;

    // Vérifie que le password diffère du primary
    final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
    if (matchesPrimary) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.heirSamePasswordSnack);
      return;
    }

    try {
      await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
      if (!mounted) return;
      setState(() {});
      SnackUtils.showInfo(messenger, t.heritageConfiguredSnack);
    } on StateError catch (e) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, e.message);
    } on ArgumentError catch (e) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, '${e.message}');
    } catch (e) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.genericError('$e'));
    }
  }

  Future<void> _manageHeritage() async {
    final t = AppLocalizations.of(context);
    final action = await showDialog<String>(
      context: context,
      builder: (_) {
        return AlertDialog(
          title: Text(t.heritageManageTitle),
          content: Text(
            t.heritageManageBody,
            style: const TextStyle(fontSize: 13),
          ),
          actions: [
            // U2 v2.4.4 — autofocus Cancel + bouton destructif via
            // FilledButton.tonal cs.errorContainer.
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.pop(context, 'cancel'),
              child: Text(t.actionCancel),
            ),
            DestructiveButton(
              onPressed: () => Navigator.pop(context, 'disable'),
              label: t.heritageDisable,
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, 'update'),
              child: Text(t.heritageUpdate),
            ),
          ],
        );
      },
    );
    if (!mounted || action == null || action == 'cancel') return;
    if (action == 'disable') {
      final messenger = ScaffoldMessenger.of(context);
      await HeritageService().disable();
      if (!mounted) return;
      setState(() {});
      SnackUtils.showInfo(messenger, t.heritageDisabledSnack);
    } else if (action == 'update') {
      // Re-prompt heir password pour confirmer + sauvegarder
      final pwd = await showDialog<String>(
        context: context,
        builder: (_) =>
            _PassphraseDialog(title: t.heirPasswordReentryTitle, confirm: true),
      );
      if (pwd == null || pwd.isEmpty || !mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
      if (matchesPrimary) {
        if (!mounted) return;
        SnackUtils.showError(context, messenger, t.heirSamePasswordShortSnack);
        return;
      }
      try {
        await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
        if (!mounted) return;
        setState(() {});
        SnackUtils.showInfo(messenger, t.heritageSnapshotUpdatedSnack);
      } catch (e) {
        if (!mounted) return;
        SnackUtils.showError(context, messenger, t.genericError('$e'));
      }
    }
  }

  Future<void> _changeHeritageThreshold(int current) async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final v = await showDialog<int>(
      context: context,
      builder: (_) => SimpleDialog(
        title: Text(t.heritageThresholdTitle),
        children: [30, 60, 90, 180, 365]
            .map(
              (d) => ListTile(
                title: Text(t.heritageDaysOption(d)),
                trailing: current == d ? const Icon(Icons.check) : null,
                onTap: () => Navigator.pop(context, d),
              ),
            )
            .toList(),
      ),
    );
    if (v == null || !mounted) return;
    try {
      await HeritageService().setThresholdDays(v);
      if (!mounted) return;
      setState(() {});
    } catch (e) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.genericError('$e'));
    }
  }

  Future<void> _setupDecoy() async {
    final t = AppLocalizations.of(context);
    // Avertissement explicatif avant la configuration.
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(Icons.shield_moon_outlined, size: 36),
        title: Text(t.decoyDialogTitle),
        content: Text(t.decoySetupBody, style: const TextStyle(fontSize: 13)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(t.actionCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(t.decoyConfigure),
          ),
        ],
      ),
    );
    if (go != true || !mounted) return;

    final pwd = await showDialog<String>(
      context: context,
      builder: (_) =>
          _PassphraseDialog(title: t.decoyPasswordPromptTitle, confirm: true),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return;

    // Vérifie qu'il diffère du primary : on tente l'unlock contre primary
    // et s'il réussit, on refuse le setup (sinon les 2 slots seraient ouverts
    // par le même password).
    final messenger = ScaffoldMessenger.of(context);
    final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
    if (matchesPrimary) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.decoySamePasswordError);
      return;
    }

    // Le slot va changer pendant setupDecoy → on lock après pour forcer
    // le user à se reconnecter sur le primary s'il veut continuer.
    try {
      await VaultService().setupDecoyVault(pwd);
      VaultService().lock();
      if (!mounted) return;
      setState(() {});
      SnackUtils.showInfo(messenger, t.decoyConfiguredSnack);
      // Retour au unlock screen
      Navigator.of(context).popUntil((r) => r.isFirst);
    } on StateError catch (e) {
      // SEC 2026-08-04 (relecture Codex) — `setupDecoyVault` refuse désormais
      // de créer un leurre quand la comparaison avec le mot de passe principal
      // ne peut pas ABOUTIR (verrouillage anti-force-brute en cours, opération
      // concurrente). Même traitement que le changement de mot de passe
      // maître : message dédié, pas de sentinel interne à l'écran.
      if (!mounted) return;
      SnackUtils.showError(context, messenger, switch (e.message) {
        VaultService.vaultBusy => t.vaultBusyRetry,
        _ => t.genericError('$e'),
      });
    } catch (e) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.genericError('$e'));
    }
  }

  Future<void> _manageDecoy() async {
    final t = AppLocalizations.of(context);
    final action = await showDialog<String>(
      context: context,
      builder: (_) {
        return AlertDialog(
          title: Text(t.decoyDialogTitle),
          content: Text(
            t.decoyManageBody,
            style: const TextStyle(fontSize: 13),
          ),
          actions: [
            // U2 v2.4.4 — autofocus Cancel + bouton destructif via
            // FilledButton.tonal cs.errorContainer.
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.pop(context, 'cancel'),
              child: Text(t.actionCancel),
            ),
            DestructiveButton(
              onPressed: () => Navigator.pop(context, 'delete'),
              label: t.decoyDelete,
            ),
          ],
        );
      },
    );
    if (action != 'delete' || !mounted) return;
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    // La section « Coffre leurre » reste volontairement VISIBLE quel que soit
    // l'emplacement actif : la masquer révélerait lequel est ouvert. Le code
    // étant public sous Apache 2.0, un adversaire sait qu'une section absente
    // signifierait « vous êtes dans le leurre ».
    //
    // AUDIT 2026-08-03 — le refus opaque qui suivait est supprimé. Depuis une
    // session leurre, le service verrouille puis écrase, exactement comme
    // « Tout supprimer » depuis cette même session (SEC F12). On revient alors
    // au déverrouillage, puisque plus rien n'est ouvert — y rester afficherait
    // les Réglages d'un coffre fermé.
    final outcome = await VaultService().deleteDecoyVault();
    if (!mounted) return;
    if (outcome == DecoyDeleteOutcome.sessionLocked) {
      nav.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const UnlockScreen()),
        (_) => false,
      );
      return;
    }
    setState(() {});
    SnackUtils.showInfo(messenger, t.decoyDeletedSnack);
  }

  Future<void> _triggerPanic() async {
    final t = AppLocalizations.of(context);
    // SEC 2026-08-03 — avertissement AVANT la panique, ajouté après un incident
    // réel : le mode panique a été activé pour un test, il a supprimé
    // l'enrôlement biométrique (SEC F4, voulu), et le mot de passe maître ne
    // revenait plus. Coffre définitivement perdu.
    //
    // SEC F4 justifiait la suppression par « le coût pour l'utilisateur
    // légitime est faible, puisque le réenrôlement exige de toute façon le mot
    // de passe maître ». Ce raisonnement suppose que l'utilisateur CONNAÎT ce
    // mot de passe. Quelqu'un qui ouvre à l'empreinte tous les jours ne le tape
    // parfois plus depuis des mois : pour lui, la panique est une porte à sens
    // unique.
    //
    // On n'affiche cet écran que si la biométrie est réellement active — sinon
    // il n'y a rien à perdre et l'avertissement ne serait que du bruit.
    if (_biometricEnabled) {
      final compris = await showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) {
          final cs = Theme.of(ctx).colorScheme;
          return AlertDialog(
            icon: Icon(Icons.fingerprint, size: 36, color: cs.error),
            title: Text(t.panicWarnBiometricTitle),
            content: SingleChildScrollView(
              child: Text(
                t.panicWarnBiometricBody,
                style: const TextStyle(fontSize: 13),
              ),
            ),
            actions: [
              TextButton(
                autofocus: true,
                onPressed: () => Navigator.pop(ctx, false),
                child: Text(t.actionCancel),
              ),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: cs.error),
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(t.actionContinue),
              ),
            ],
          );
        },
      );
      if (compris != true || !mounted) return;
    }
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: Icon(Icons.warning_amber_rounded, size: 36, color: cs.error),
          title: Text(t.panicDialogTitle),
          content: Text(
            t.panicDialogBody,
            style: const TextStyle(fontSize: 14),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(t.actionCancel),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: cs.error,
                foregroundColor: cs.onError,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(t.panicDialogActivate),
            ),
          ],
        );
      },
    );
    if (confirm != true || !mounted) return;
    // U9 v2.4.4 — heavyImpact sur action panique (le geste le plus
    // critique de l'app : lock + clear clipboard + disguise icône).
    await HapticFeedback.heavyImpact();
    await PanicService.panic();
    if (!mounted) return;
    // Retour à l'écran de déverrouillage (vault est lock).
    Navigator.of(context).popUntil((r) => r.isFirst);
  }

  Future<void> _revealApp() async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    await PanicService.revealApp();
    if (!mounted) return;
    setState(() {}); // Rafraîchit le FutureBuilder
    SnackUtils.showInfo(
      messenger,
      t.panicRevealSnack,
      duration: const Duration(seconds: 5),
    );
  }

  void _lockNow() {
    // U9 v2.4.4 — haptique mediumImpact sur lock manuel (geste protecteur,
    // intermédiaire entre l'action courante et l'action destructive).
    HapticFeedback.mediumImpact();
    VaultService().lock();
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const UnlockScreen()),
      (_) => false,
    );
  }

  String _autoLockLabelOf(AppLocalizations t) {
    final opts = _lockOptions(t);
    return opts
        .firstWhere((o) => o.value == _autoLockSeconds, orElse: () => opts[2])
        .label;
  }

  Future<void> _toggleBiometric(bool v) async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    if (v) {
      try {
        // saveBiometricKey() écrit dans biometric_storage, qui crée une
        // clé Keystore avec setUserAuthenticationRequired(true). La première
        // écriture — et chaque lecture suivante — déclenche BiometricPrompt.
        // Lance StateError si le slot actif n'est pas primary (sécurité
        // dual-vault). On absorbe silencieusement pour ne pas trahir
        // l'existence du decoy à un attaquant attentif.
        await VaultService().saveBiometricKey();
      } on AuthException catch (e) {
        // (v2.4.2) Discrimine annulation (userCanceled/canceled) d'échec
        // technique pour donner un feedback explicite à l'utilisateur,
        // remplace le `catch(_)` silencieux qui le laissait sans retour.
        if (!mounted) return;
        final isCancel =
            e.code == AuthExceptionCode.userCanceled ||
            e.code == AuthExceptionCode.canceled;
        if (isCancel) {
          SnackUtils.showInfo(messenger, t.settingsBiometricEnableCanceled);
        } else {
          SnackUtils.showError(
            context,
            messenger,
            t.settingsBiometricEnableFailed,
          );
        }
        return;
      } catch (_) {
        // StateError (slot decoy actif) ou autre erreur non-biométrique.
        // On ne donne PAS de message qui révélerait le decoy ; silence
        // intentionnel, mais on évite de basculer le toggle vers ON.
        return;
      }
    } else {
      await VaultService().deleteBiometricKey();
    }
    if (!mounted) return;
    setState(() => _biometricEnabled = v);
    // Snack de confirmation pour activation comme désactivation — donne
    // un feedback positif que l'opération a bien été prise en compte
    // (la toggle visuelle seule était trop discrète, audit UX).
    SnackUtils.showInfo(
      messenger,
      v ? t.settingsBiometricEnabled : t.settingsBiometricDisabled,
    );
  }

  Future<void> _setClipboard(int v) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('clipboard_clear', v);
    ClipboardService.clearAfterSeconds = v;
    if (mounted) setState(() => _clipboardClear = v);
  }

  /// SEC 2026-08-03 (Gemini PT-001/PT-003) — redemande le mot de passe maître
  /// avant une opération irréversible ou exfiltrante.
  ///
  /// SEC F10 v2.5.2 avait ajouté cette ré-authentification au changement de mot
  /// de passe, en désignant précisément la menace : « quiconque disposait d'un
  /// accès momentané à une session déverrouillée ». Le raisonnement n'avait été
  /// propagé ni à l'export en clair, ni à la suppression du coffre — alors que
  /// l'export est PIRE que le changement de mot de passe : il emporte
  /// l'intégralité des identifiants, en clair, hors de l'appareil.
  ///
  /// Le verrouillage automatique par défaut est à 300 s et n'est évalué qu'au
  /// retour au premier plan : une application laissée ouverte devant quelqu'un
  /// reste ouverte. C'est exactement la fenêtre que ce contrôle referme.
  ///
  /// Vérifie contre l'emplacement ACTIF : depuis une session leurre, c'est le
  /// mot de passe du leurre qui est attendu — rien n'est révélé du principal.
  Future<bool> _reauthenticate() async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final pwd = await showDialog<String>(
      context: context,
      builder: (_) => const _ReauthDialog(),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return false;
    final ok = await VaultService().verifyCurrentPassword(pwd);
    if (!ok && mounted) {
      SnackUtils.showError(
        context,
        messenger,
        t.changePasswordErrorWrongCurrent,
      );
    }
    return ok;
  }

  Future<void> _exportVault() async {
    final t = AppLocalizations.of(context);
    // H-5 : confirmation explicite avant tout export en clair, et suppression
    // immédiate du fichier temporaire après le Share. L'utilisateur DOIT être
    // averti que ses mots de passe seront lisibles par toute personne ayant
    // accès au fichier exporté (cloud sync, app malveillante avec READ_STORAGE,
    // historique de partage Android, etc.).
    final confirmed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: Icon(Icons.warning_amber_rounded, color: cs.error, size: 40),
          title: Text(
            t.exportPlainDialogTitle,
            style: TextStyle(color: cs.error, fontWeight: FontWeight.bold),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                t.exportPlainWarningHeadline,
                style: TextStyle(color: cs.error, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 12),
              Text(t.exportPlainWarningBullet1),
              const SizedBox(height: 6),
              Text(t.exportPlainWarningBullet2),
              const SizedBox(height: 6),
              Text(t.exportPlainWarningBullet3),
              const SizedBox(height: 12),
              Text(
                t.exportPlainWarningTip,
                style: const TextStyle(fontStyle: FontStyle.italic),
              ),
            ],
          ),
          actions: [
            // U2 v2.4.4 — autofocus Cancel + bouton destructif via
            // FilledButton.tonal cs.errorContainer.
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(t.actionCancel),
            ),
            DestructiveButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              label: t.exportPlainConfirm,
            ),
          ],
        );
      },
    );
    if (confirmed != true || !mounted) return;
    // Ré-authentification APRÈS l'avertissement : on ne demande le mot de passe
    // qu'à quelqu'un qui a lu et accepté ce que l'export implique.
    if (!await _reauthenticate() || !mounted) return;

    final json = VaultService().exportJson();
    final dir = await getTemporaryDirectory();
    // AUDIT 2026-08-03 — purge AVANT, en plus de la purge après.
    // C'est la seule façon de rattraper un partage précédent interrompu : si le
    // processus est tué pendant l'affichage du sélecteur, le `finally` ci-dessous
    // ne s'exécute jamais et la copie faite par share_plus survit indéfiniment.
    // Le commentaire de SEC F8 affirmait que ce ménage avait aussi lieu « au
    // verrouillage » — c'était faux, la fonction n'avait qu'un seul appelant.
    _shredStaleExports(dir);
    final file = File('${dir.path}/pass_tech_export.json');
    await file.writeAsString(json);
    try {
      await Share.shareXFiles([
        XFile(file.path, mimeType: 'application/json'),
      ], subject: t.exportShareSubject);
    } finally {
      // F20 v2.3.7 — overwrite plaintext avec random bytes AVANT delete
      // (best-effort — F2FS/SSD wear-leveling ne garantit pas l'effacement
      // physique, mais empêche la récupération via lecture brute fichier).
      _shredFile(file);
      // SEC F8 v2.5.2 — `Share.shareXFiles` ne PARTAGE PAS notre fichier : il
      // le RECOPIE dans `<cache>/share_plus/` (share_plus `copyToShareCacheFolder`)
      // et n'y fait le ménage qu'au DÉBUT du prochain appel à `shareFiles`.
      // On écrasait donc soigneusement notre propre fichier temporaire pendant
      // qu'un vidage JSON en clair de TOUTES les entrées survivait
      // indéfiniment à côté — sans mot de passe, sans Keystore, sans Argon2id
      // pour le protéger. Le plugin accorde en outre une permission de lecture
      // sur cette copie à toute activité résolvant le sélecteur.
      //
      // SEC 2026-08-04 — cette purge est DÉPLACÉE vers l'export suivant.
      //
      // `Share.shareXFiles` rend la main dès que l'activité cible se termine,
      // ce qui ne signifie pas qu'elle a fini de LIRE. Une cible qui téléverse
      // en tâche de fond — Drive, messagerie — lit encore après notre retour.
      // Écraser sa source d'octets aléatoires à cet instant produirait un
      // export CORROMPU, que l'on ne découvrirait qu'au moment d'en avoir
      // besoin. C'est le pire moment possible pour une sauvegarde.
      //
      // Le raisonnement de SEC F8 reste entièrement valable : cette copie ne
      // doit pas survivre indéfiniment. Elle est donc purgée par l'appel à
      // `_shredStaleExports` placé AVANT le partage, qui balaie les résidus de
      // l'export précédent. Le résidu est borné dans le temps sans jamais
      // couper une lecture en cours.
    }
  }

  /// Écrase un fichier par des octets aléatoires puis le supprime.
  ///
  /// AUDIT 2026-08-03 — délègue désormais à [VaultService.shredFileSync] au
  /// lieu de refaire le travail. Cette copie locale n'avait pas le repli de
  /// l'originale : quand l'écrasement échouait, elle abandonnait AUSSI la
  /// suppression, laissant le fichier en clair intact. Deux traitements pour
  /// un même besoin, dont un plus faible — le genre d'écart qui ne se voit
  /// qu'à la relecture croisée.
  static void _shredFile(File file) => VaultService.shredFileSync(file);

  /// Vrai si [filePath] est bien une COPIE faite par le sélecteur de fichiers
  /// dans notre propre cache, et non le document d'origine de l'utilisateur.
  ///
  /// Deux conditions, délibérément cumulatives :
  ///  1. le chemin est sous le répertoire temporaire de l'application ;
  ///  2. il traverse le sous-dossier `file_picker/`, que le plugin fabrique.
  ///
  /// Vérifié dans `file_picker` 11.0.2 (`FileUtils.kt`), qui écrit sous
  /// `<cache>/file_picker/<horodatage>/<nom>`. Exiger les deux plutôt qu'une
  /// seule est volontaire : au moindre écart — nouvelle version du plugin,
  /// autre plateforme, chemin inattendu — on renonce à effacer. Le pire cas
  /// devient « une copie survit jusqu'à `clearTemporaryFiles()` », au lieu de
  /// « le fichier de l'utilisateur a disparu ».
  static bool _isPickerCacheCopy(String cacheRoot, String filePath) {
    final root = cacheRoot.replaceAll('\\', '/');
    final path = filePath.replaceAll('\\', '/');
    final prefix = root.endsWith('/') ? root : '$root/';
    if (!path.startsWith(prefix)) return false;
    if (path.contains('/../') || path.endsWith('/..')) return false;
    return path.contains('/file_picker/');
  }

  /// Purge tout résidu d'un export précédent dans le répertoire temporaire.
  ///
  /// Couvre DEUX emplacements, et c'est le second qui manquait :
  ///  1. `<cache>/share_plus/` — où le plugin recopie chaque fichier partagé,
  ///     et où il ne fait le ménage qu'au DÉBUT du partage suivant ;
  ///  2. **le répertoire temporaire lui-même**, où vivent NOS fichiers
  ///     (`pass_tech_export.json`, `pass_tech_*.ptbak`).
  ///
  /// AUDIT 2026-08-03 (Gemini PT-001) — le point 2 est un trou du correctif
  /// SEC F8 posé le matin même. Le `finally` de l'export déchiquette bien notre
  /// fichier… quand il s'exécute. Si le processus est tué pendant que le
  /// sélecteur de partage est à l'écran — l'utilisateur bascule d'application,
  /// Android récupère la mémoire — ce `finally` ne tourne JAMAIS, et
  /// `pass_tech_export.json`, qui contient l'intégralité des mots de passe **en
  /// clair**, reste dans le cache indéfiniment. La purge d'ouverture ne
  /// regardait que le sous-dossier du plugin, jamais notre propre fichier.
  ///
  /// Appelée AVANT et APRÈS chaque partage : c'est l'appel « avant » qui
  /// rattrape l'export précédent interrompu.
  static void _shredStaleExports(Directory cacheDir) {
    try {
      final shareDir = Directory('${cacheDir.path}/share_plus');
      if (shareDir.existsSync()) {
        for (final ent in shareDir.listSync(followLinks: false)) {
          if (ent is File) _shredFile(ent);
        }
      }
    } catch (_) {}
    try {
      for (final ent in cacheDir.listSync(followLinks: false)) {
        if (ent is! File) continue;
        final name = ent.uri.pathSegments.last;
        if (name == 'pass_tech_export.json' ||
            (name.startsWith('pass_tech_') && name.endsWith('.ptbak'))) {
          _shredFile(ent);
        }
      }
    } catch (_) {}
  }

  Future<void> _exportEncrypted() async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    final passphrase = await showDialog<String>(
      context: context,
      builder: (_) =>
          _PassphraseDialog(title: t.exportEncryptedDialogTitle, confirm: true),
    );
    if (passphrase == null || !mounted) return;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );

    try {
      final content = await ImportExportService.exportEncrypted(
        VaultService().entries,
        passphrase,
      );
      final date = DateTime.now().toIso8601String().substring(0, 10);
      final dir = await getTemporaryDirectory();
      // AUDIT 2026-08-03 — même purge que l'export en clair, avant et après.
      // Ce chemin ne nettoyait PAS le cache de share_plus : la copie du
      // `.ptbak` faite par le plugin y restait jusqu'au partage suivant. Le
      // fichier est chiffré, donc l'enjeu est moindre qu'en clair — mais c'est
      // une copie complète du coffre, laissée dans un répertoire sur lequel le
      // plugin accorde une permission de lecture à toute application capable
      // de répondre au sélecteur.
      _shredStaleExports(dir);
      final file = File('${dir.path}/pass_tech_$date.ptbak');
      await file.writeAsString(content);
      // 2026-08-03 — trace de la sauvegarde. C'est ce qui fait disparaître le
      // rappel de l'accueil. Enregistré dès que le fichier est écrit, sans
      // attendre l'issue du partage : le fichier existe, l'utilisateur a fait
      // sa part. Seule la DATE est conservée, jamais le chemin ni la phrase.
      await BackupReminder.markBackupDone();
      if (!mounted) return;
      Navigator.of(context).pop(); // close progress
      try {
        await Share.shareXFiles([
          XFile(file.path, mimeType: 'application/octet-stream'),
        ], subject: t.exportEncryptedShareSubject);
      } finally {
        // SEC 2026-08-04 — voir `_exportVault` : on déchiquette NOTRE fichier,
        // jamais la copie de `share_plus` que la cible est peut-être encore en
        // train de lire. Une sauvegarde `.ptbak` corrompue au moment du
        // téléversement serait découverte le jour de la restauration, quand il
        // est trop tard. La copie du plugin est purgée à l'export suivant.
        _shredFile(file);
      }
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context).pop();
      SnackUtils.showError(context, messenger, t.genericError('$e'));
    }
  }

  /// Cap import : refuse les fichiers > 50 Mo AVANT de demander leur contenu
  /// en RAM via withData. Sans ça, FilePicker chargeait des fichiers
  /// arbitrairement gros en RAM avant que notre vérification ne s'applique.
  static const _kMaxImportBytes = 50 * 1024 * 1024;

  Future<void> _importFile() async {
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    // 1. Sélection sans bytes pour récupérer juste la taille (évite OOM).
    final probe = await FilePicker.pickFiles(type: FileType.any);
    if (probe == null || probe.files.isEmpty || !mounted) return;
    final probeFile = probe.files.first;
    if (probeFile.size > _kMaxImportBytes) {
      SnackUtils.showError(
        context,
        messenger,
        t.importTooLarge(
          (probeFile.size / 1024 / 1024).toStringAsFixed(0),
          '${_kMaxImportBytes ~/ (1024 * 1024)}',
        ),
      );
      return;
    }
    // 2. Le path Android est rempli par défaut → on lit directement.
    final filePath = probeFile.path;
    if (filePath == null) {
      SnackUtils.showError(context, messenger, t.importReadError);
      return;
    }
    final Uint8List bytes;
    try {
      bytes = await File(filePath).readAsBytes();
    } catch (_) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.importReadError);
      return;
    } finally {
      // AUDIT 2026-08-03 (Gemini PT-002) — purge du cache de FilePicker.
      //
      // Pour rendre un `path` exploitable à partir d'un `content://`, le
      // sélecteur RECOPIE le fichier choisi dans le cache de l'application.
      // Cette copie n'était jamais supprimée. Or ce que l'on importe ici, c'est
      // typiquement un export **en clair** d'un autre gestionnaire — un JSON
      // Bitwarden, un CSV KeePass — c'est-à-dire l'intégralité des mots de
      // passe de la personne, dans un fichier non chiffré qui s'installait à
      // demeure dans le cache de Pass Tech.
      //
      // Placé en `finally` : une lecture qui échoue laisse la copie tout autant
      // derrière elle. On déchiquette d'abord notre exemplaire, puis on demande
      // au plugin de vider le sien.
      //
      // ⚠️ GARDE OBLIGATOIRE : on ne déchiquette QUE si le chemin est bien dans
      // notre répertoire de cache. Vérifié dans `file_picker` 11.0.2, qui copie
      // sous `<cache>/file_picker/<horodatage>/<nom>` — mais si une version
      // future rendait le chemin RÉEL du fichier choisi, on effacerait le
      // document de l'utilisateur lui-même. Un export que l'on vient de lui
      // demander d'importer. Le contrôle coûte deux lignes ; l'erreur serait
      // irréparable.
      try {
        final cacheRoot = (await getTemporaryDirectory()).path;
        if (_isPickerCacheCopy(cacheRoot, filePath)) {
          _shredFile(File(filePath));
        }
      } catch (_) {}
      try {
        await FilePicker.clearTemporaryFiles();
      } catch (_) {}
    }
    final file = probeFile;

    String content;
    try {
      // M-1 : utf8.decode pour préserver les caractères accentués (é, è, ñ…)
      // dans les CSV/JSON exportés par Bitwarden, KeePass, etc. Avec
      // String.fromCharCodes, les bytes UTF-8 multi-octets étaient interprétés
      // comme du Latin-1, corrompant silencieusement les entries.
      content = utf8.decode(bytes, allowMalformed: true);
    } catch (_) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.importNotText);
      return;
    }

    List<Entry>? imported;
    String formatLabel = '';

    // Detect .ptbak — v2.3.4 : on bascule sur l'extension OU une vraie
    // vérification après jsonDecode (sniffing par contains() permettait à
    // un fichier ennemi de déclencher le prompt passphrase via une chaîne
    // injectée dans n'importe quel champ JSON).
    bool isPtbak = file.name.toLowerCase().endsWith('.ptbak');
    if (!isPtbak) {
      try {
        final decoded = jsonDecode(content);
        if (decoded is Map && decoded['magic'] == 'PTBAK') {
          isPtbak = true;
        }
      } catch (_) {
        // pas un JSON → pas un .ptbak
      }
    }

    if (isPtbak) {
      if (!mounted) return;
      final passphrase = await showDialog<String>(
        context: context,
        builder: (_) => _PassphraseDialog(
          title: t.importRestoreDialogTitle,
          confirm: false,
        ),
      );
      if (passphrase == null || !mounted) return;
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (_) => const Center(child: CircularProgressIndicator()),
      );
      imported = await ImportExportService.importEncrypted(content, passphrase);
      if (!mounted) return;
      Navigator.of(context).pop();
      if (imported == null) {
        if (!mounted) return;
        SnackUtils.showError(context, messenger, t.importWrongPassphrase);
        return;
      }
      formatLabel = t.importFormatEncryptedBackup;
    } else {
      final result = ImportExportService.parse(content);
      if (result.error != null) {
        if (!mounted) return;
        SnackUtils.showError(context, messenger, result.error!);
        return;
      }
      imported = result.entries;
      formatLabel = _formatLabel(t, result.format);
    }

    if (imported.isEmpty) {
      if (!mounted) return;
      SnackUtils.showError(context, messenger, t.importNoEntry);
      return;
    }

    if (!mounted) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.importConfirmTitle),
        content: Text(t.importConfirmBody(imported!.length, formatLabel)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(t.actionCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(t.importCta),
          ),
        ],
      ),
    );
    if (go != true) return;

    final existing = VaultService().entries;
    int added = 0;
    int skipped = 0;
    for (final e in imported) {
      final dup = existing.any(
        (x) =>
            x.title.toLowerCase() == e.title.toLowerCase() &&
            x.username.toLowerCase() == e.username.toLowerCase(),
      );
      if (dup) {
        skipped++;
      } else {
        await VaultService().addEntry(e);
        added++;
      }
    }
    widget.onChanged();
    if (mounted) {
      final skippedSuffix = skipped > 0 ? t.importSkippedSuffix(skipped) : '';
      SnackUtils.showInfo(messenger, t.importDoneSnack(added, skippedSuffix));
    }
  }

  String _formatLabel(AppLocalizations t, String f) {
    switch (f) {
      case 'bitwarden':
        return t.importFormatBitwarden;
      case 'pass_tech':
        return t.importFormatPassTech;
      case 'csv':
        return t.importFormatCsv;
      default:
        return t.importFormatUnknown;
    }
  }

  Future<void> _changePassword() async {
    final nav = Navigator.of(context);
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    // SEC F10 v2.5.2 — le dialogue rend désormais le couple
    // (mot de passe actuel, nouveau mot de passe).
    final result = await showDialog<({String current, String fresh})>(
      context: context,
      builder: (_) => const _ChangePasswordDialog(),
    );
    if (result == null || !mounted) return;
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );
    // v2.5.x — try/catch : `changeMasterPassword` peut throw (wrap KEK, IO du
    // save, deleteBiometricKey). Sans ça, le spinner `barrierDismissible:false`
    // restait affiché indéfiniment → app gelée, et si le throw survenait APRÈS
    // le save réussi, le mot de passe était changé mais l'UI le croyait échoué.
    // Aligné sur les autres opérations Réglages (export/héritage) déjà en
    // try/catch avec pop du progress en cas d'erreur.
    // UX 2026-08-04 — relevé AVANT l'appel : `changeMasterPassword` supprime
    // l'enrôlement biométrique en cours de route, on ne pourrait plus savoir
    // après coup s'il existait.
    final bioEtaitActive = _biometricEnabled;
    try {
      await VaultService().changeMasterPassword(
        result.fresh,
        currentPassword: result.current,
      );
      if (!mounted) return;
      nav.pop(); // close progress dialog
      // UX 2026-08-04 — on ANNONCE la désactivation de la biométrie.
      //
      // Le changement de mot de passe supprime l'enrôlement biométrique, et
      // c'est nécessaire : l'enveloppe biométrique scelle l'ANCIENNE clé
      // finale, elle est inutilisable après la rotation. Mais l'app ne le
      // disait pas — elle affichait « mot de passe changé » et rien d'autre.
      // L'utilisateur relançait l'application, ne trouvait plus le bouton
      // empreinte, et n'avait aucun moyen de comprendre pourquoi.
      //
      // Même motif que le mode panique corrigé la veille : un effet de bord
      // indispensable à la sécurité, mais silencieux. Le message n'apparaît
      // que si la biométrie était réellement active — sinon il n'apprendrait
      // rien à personne.
      SnackUtils.showInfo(
        messenger,
        bioEtaitActive
            ? t.changePasswordDoneBiometricReset
            : t.changePasswordDoneSnack,
        duration: bioEtaitActive
            ? const Duration(seconds: 6)
            : const Duration(seconds: 3),
      );
      setState(() => _biometricEnabled = false);
    } on StateError catch (e) {
      // SEC F10 v2.5.2 — mot de passe actuel incorrect : message dédié plutôt
      // qu'une erreur générique exposant le sentinel interne.
      if (!mounted) return;
      nav.pop();
      SnackUtils.showError(context, messenger, switch (e.message) {
        VaultService.wrongCurrentPassword => t.changePasswordErrorWrongCurrent,
        // SEC-R1 v2.5.2 — opération concurrente : rien n'a été muté.
        VaultService.vaultBusy => t.vaultBusyRetry,
        _ => t.genericError('$e'),
      });
    } catch (e) {
      if (!mounted) return;
      nav.pop(); // close progress dialog
      SnackUtils.showError(context, messenger, t.genericError('$e'));
    }
  }

  Future<void> _deleteAll() async {
    final nav = Navigator.of(context);
    final t = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.settingsDeleteAllDialogTitle),
        content: Text(t.settingsDeleteAllDialogBody),
        actions: [
          // U2 v2.4.4 — autofocus Cancel + bouton destructif via
          // FilledButton.tonal cs.errorContainer. Le PLUS critique des
          // 4 sites — supprime intégralement le coffre + decoy.
          TextButton(
            autofocus: true,
            onPressed: () => nav.pop(false),
            child: Text(t.actionCancel),
          ),
          DestructiveButton(
            onPressed: () => nav.pop(true),
            label: t.settingsDeleteAllConfirm,
            icon: Icons.delete_forever_outlined,
          ),
        ],
      ),
    );
    if (ok != true) return;
    // SEC 2026-08-03 — la suppression définitive exige le mot de passe maître.
    // Sans ce contrôle, un accès momentané à une session ouverte suffisait à
    // anéantir le coffre — irréversible, `allowBackup="false"` interdisant
    // toute restauration système.
    if (!await _reauthenticate() || !mounted) return;
    // U9 v2.4.4 — feedback haptique sur action destructive ultime.
    await HapticFeedback.heavyImpact();
    // v2.5.4 — plus de refus opaque depuis une session leurre. `deleteVault()`
    // supprime alors le SEUL emplacement leurre et le signale par son retour.
    // L'écran suivant en dépend, et ce n'est pas cosmétique : pousser l'écran
    // de création après un `decoyOnly` laisserait créer un coffre par-dessus
    // le principal, qui existe toujours. On revient donc au déverrouillage.
    final outcome = await VaultService().deleteVault();
    // Un coffre recréé après une suppression totale doit repartir avec le
    // rappel actif : ses futures entrées ne seront couvertes par aucune des
    // sauvegardes précédentes.
    if (outcome == VaultDeleteOutcome.fullWipe) {
      await BackupReminder.reset();
    }
    if (!mounted) return;
    nav.pushAndRemoveUntil(
      MaterialPageRoute(
        builder: (_) => outcome == VaultDeleteOutcome.fullWipe
            ? const SetupScreen()
            : const UnlockScreen(),
      ),
      (_) => false,
    );
  }

  String _clipLabelOf(AppLocalizations t) {
    final opts = _clipOptions(t);
    return opts
        .firstWhere((o) => o.value == _clipboardClear, orElse: () => opts[1])
        .label;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(t.settingsTitle)),
      // UI 2026-08-03 — Réglages aligné sur la présentation de « À propos ».
      //
      // Avant : une `ListView` de `ListTile` nus, bord à bord, où neuf sections
      // se distinguaient uniquement par un petit titre coloré. Les réglages de
      // sécurité, les actions destructrices et le choix du thème avaient
      // exactement le même poids visuel.
      //
      // Désormais : mêmes marges (16/24/16/40), mêmes titres discrets et
      // surtout chaque réglage posé sur sa propre carte — le vocabulaire déjà
      // employé par « À propos ». Les cartes portent le rayon et la bordure
      // définis par le thème, donc l'écran suit automatiquement le mode clair
      // comme le mode sombre.
      //
      // La décoration est appliquée à la LISTE, pas à chaque élément : les
      // tuiles restent inchangées, avec leurs `onTap` et leurs `FutureBuilder`.
      // C'est le seul moyen de refondre la présentation sans risquer d'égarer
      // un branchement au passage.
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 40),
        children: _decorate([
          _section(t.settingsSectionAppearance),
          ListTile(
            leading: const Icon(Icons.brightness_6_outlined),
            title: Text(t.settingsThemeTitle),
            subtitle: Text(_themeLabelOf(t)),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final m = await showDialog<ThemeMode>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: Text(t.settingsThemeChooseTitle),
                  children:
                      [
                        (
                          t.settingsThemeSystem,
                          ThemeMode.system,
                          Icons.settings_brightness,
                        ),
                        (
                          t.settingsThemeLight,
                          ThemeMode.light,
                          Icons.light_mode_outlined,
                        ),
                        (
                          t.settingsThemeDark,
                          ThemeMode.dark,
                          Icons.dark_mode_outlined,
                        ),
                      ].map((opt) {
                        return ListTile(
                          leading: Icon(opt.$3, size: 20),
                          title: Text(opt.$1),
                          trailing: _themeMode == opt.$2
                              ? const Icon(Icons.check)
                              : null,
                          onTap: () => Navigator.pop(context, opt.$2),
                        );
                      }).toList(),
                ),
              );
              if (m != null) _setTheme(m);
            },
          ),
          Builder(
            builder: (ctx) {
              final t = AppLocalizations.of(ctx);
              return ListTile(
                leading: const Icon(Icons.language),
                title: Text(t.settingsLanguage),
                subtitle: Text(_localeLabel(t)),
                trailing: const Icon(Icons.chevron_right, size: 18),
                onTap: () async {
                  // Use a sentinel string so we can distinguish a barrier
                  // dismiss (null) from an explicit "System" choice.
                  final choice = await showDialog<String>(
                    context: ctx,
                    builder: (_) => SimpleDialog(
                      title: Text(t.settingsLanguage),
                      children:
                          <(String, String)>[
                            (t.settingsLanguageSystem, 'system'),
                            (t.settingsLanguageFrench, 'fr'),
                            (t.settingsLanguageEnglish, 'en'),
                          ].map((opt) {
                            final selected =
                                (_locale?.languageCode ?? 'system') == opt.$2;
                            return ListTile(
                              title: Text(opt.$1),
                              trailing: selected
                                  ? const Icon(Icons.check)
                                  : null,
                              onTap: () => Navigator.pop(ctx, opt.$2),
                            );
                          }).toList(),
                    ),
                  );
                  if (choice == null) return; // barrier dismiss
                  await _setLocale(parseLocale(choice));
                },
              );
            },
          ),

          _section(t.settingsSectionClipboard),
          ListTile(
            leading: const Icon(Icons.content_paste_off_outlined),
            title: Text(t.settingsClipboardTitle),
            subtitle: Text(_clipLabelOf(t)),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final v = await showDialog<int>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: Text(t.settingsClipboardDialogTitle),
                  children: _clipOptions(t)
                      .map(
                        (o) => ListTile(
                          title: Text(o.label),
                          trailing: _clipboardClear == o.value
                              ? const Icon(Icons.check)
                              : null,
                          onTap: () => Navigator.pop(context, o.value),
                        ),
                      )
                      .toList(),
                ),
              );
              if (v != null) _setClipboard(v);
            },
          ),

          _section(t.settingsSectionSecurity),
          // v2.3.11 — toggle FLAG_SECURE. Activé par défaut. L'utilisateur
          // peut désactiver pour permettre le paste cross-app sur Samsung
          // (Knox bloque le clipboard quand FLAG_SECURE est actif).
          // Confirmation requise avant désactivation pour éviter les
          // clics accidentels.
          SwitchListTile(
            title: Text(t.settingsScreenshotProtectionTitle),
            subtitle: Text(
              t.settingsScreenshotProtectionSubtitle,
              style: const TextStyle(fontSize: 12),
            ),
            secondary: Icon(
              _screenshotProtectionEnabled
                  ? Icons.shield_outlined
                  : Icons.shield_outlined,
              color: _screenshotProtectionEnabled
                  ? null
                  : Theme.of(context).colorScheme.error,
            ),
            value: _screenshotProtectionEnabled,
            isThreeLine: true,
            onChanged: _toggleScreenshotProtection,
          ),
          if (_biometricAvailable) ...[
            SwitchListTile(
              title: Text(t.settingsBiometricTitle),
              subtitle: Text(t.settingsBiometricSubtitle),
              secondary: const Icon(Icons.fingerprint),
              value: _biometricEnabled,
              onChanged: _toggleBiometric,
            ),
            // F2 / ROADMAP_HARDENING M-6 — biometric_storage repose sur une
            // clé Android Keystore configurée setUserAuthenticationRequired.
            // Sur certains OEM, l'ajout d'une nouvelle empreinte / Face ID
            // n'invalide PAS systématiquement la clé existante, donc tout
            // nouveau biométrique pourrait déverrouiller Pass Tech. La seule
            // garantie portable : forcer l'utilisateur à désactiver/réactiver
            // la bio dans Pass Tech pour régénérer la clé (deleteBiometricKey
            // + saveBiometricKey crée une nouvelle clé bornée à l'enrollment
            // courant).
            Padding(
              padding: const EdgeInsets.fromLTRB(72, 0, 16, 12),
              child: Text(
                t.settingsBiometricNewEnrollmentWarning,
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
              ),
            ),
          ],
          ListTile(
            leading: const Icon(Icons.key_outlined),
            title: Text(t.settingsChangeMasterTitle),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _changePassword,
          ),
          ListTile(
            leading: const Icon(Icons.timer_outlined),
            title: Text(t.settingsAutoLockTitle),
            subtitle: Text(_autoLockLabelOf(t)),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final v = await showDialog<int>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: Text(t.settingsAutoLockDialogTitle),
                  children: _lockOptions(t)
                      .map(
                        (o) => ListTile(
                          title: Text(o.label),
                          trailing: _autoLockSeconds == o.value
                              ? const Icon(Icons.check)
                              : null,
                          onTap: () => Navigator.pop(context, o.value),
                        ),
                      )
                      .toList(),
                ),
              );
              if (v != null) _setAutoLock(v);
            },
          ),
          ListTile(
            leading: const Icon(Icons.lock_outline),
            title: Text(t.settingsLockNow),
            onTap: _lockNow,
          ),
          ListTile(
            leading: const Icon(Icons.gpp_good_outlined),
            title: Text(t.settingsAuditTitle),
            subtitle: Text(t.settingsAuditSubtitle),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const AuditScreen()),
            ),
          ),

          _section(t.decoySection),
          FutureBuilder<bool>(
            future: VaultService().hasDecoyVault,
            builder: (_, snap) {
              final hasDecoy = snap.data == true;
              return Column(
                children: [
                  ListTile(
                    leading: Icon(
                      Icons.shield_moon_outlined,
                      color: hasDecoy ? cs.primary : null,
                    ),
                    title: Text(
                      hasDecoy ? t.decoyTileConfigured : t.decoyTileSetup,
                    ),
                    subtitle: Text(
                      t.decoyTileSubtitle,
                      style: const TextStyle(fontSize: 12),
                    ),
                    trailing: const Icon(Icons.chevron_right, size: 18),
                    onTap: hasDecoy ? _manageDecoy : _setupDecoy,
                  ),
                ],
              );
            },
          ),

          _section(t.settingsSectionAntiPhishing),
          SwitchListTile(
            title: Text(t.settingsAntiPhishingToggleTitle),
            subtitle: Text(
              _antiPhishingEnabled
                  ? (_antiPhishingASActive
                        ? t.settingsAntiPhishingActive
                        : t.settingsAntiPhishingNeedsAS)
                  : t.settingsAntiPhishingDescription,
              style: const TextStyle(fontSize: 12),
            ),
            secondary: Icon(
              Icons.verified_user_outlined,
              color: _antiPhishingEnabled && _antiPhishingASActive
                  ? cs.primary
                  : null,
            ),
            value: _antiPhishingEnabled,
            onChanged: _toggleAntiPhishing,
          ),
          if (_antiPhishingEnabled && !_antiPhishingASActive)
            ListTile(
              leading: const Icon(Icons.settings_accessibility_outlined),
              title: Text(t.settingsAntiPhishingOpenASTitle),
              subtitle: Text(
                t.settingsAntiPhishingOpenASSubtitle,
                style: const TextStyle(fontSize: 12),
              ),
              trailing: const Icon(Icons.chevron_right, size: 18),
              onTap: () async {
                await AntiPhishingService().openAccessibilitySettings();
                final active =
                    await AntiPhishingService().isAccessibilityServiceActive;
                if (mounted) setState(() => _antiPhishingASActive = active);
              },
            ),

          _section(t.panicSection),
          ListTile(
            leading: Icon(
              Icons.warning_amber_rounded,
              color: Theme.of(context).colorScheme.error,
            ),
            title: Text(t.panicTriggerTitle),
            subtitle: Text(
              t.panicTriggerSubtitle,
              style: const TextStyle(fontSize: 12),
            ),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _triggerPanic,
          ),
          // `_Undecorated` : ce bloc ne s'affiche QUE si le camouflage est
          // actif. Sans cette marque, la décoration poserait une carte vide sur
          // l'écran de tout le monde.
          _Undecorated(
            child: FutureBuilder<bool?>(
              future: PanicService.isDisguised(),
              builder: (_, snap) {
                if (snap.data != true) return const SizedBox.shrink();
                return Card(
                  margin: const EdgeInsets.only(bottom: 6),
                  child: ListTile(
                    leading: Icon(Icons.visibility, color: cs.primary),
                    title: Text(t.panicRevealTitle),
                    subtitle: Text(
                      t.panicRevealSubtitle,
                      style: const TextStyle(fontSize: 12),
                    ),
                    trailing: const Icon(Icons.chevron_right, size: 18),
                    onTap: _revealApp,
                  ),
                );
              },
            ),
          ),

          _section(t.heritageSection),
          FutureBuilder<List<dynamic>>(
            future: _loadHeritageState(),
            builder: (_, snap) {
              final enabled = snap.hasData ? snap.data![0] as bool : false;
              final threshold = snap.hasData ? snap.data![1] as int : 90;
              final inactivity = snap.hasData ? snap.data![2] as int : -1;
              return Column(
                children: [
                  ListTile(
                    leading: Icon(
                      Icons.family_restroom,
                      color: enabled ? cs.primary : null,
                    ),
                    title: Text(
                      enabled ? t.heritageTileConfigured : t.heritageTileSetup,
                    ),
                    subtitle: Text(
                      enabled
                          ? t.heritageTileSubtitleConfigured
                          : t.heritageTileSubtitleSetup,
                      style: const TextStyle(fontSize: 12),
                    ),
                    trailing: const Icon(Icons.chevron_right, size: 18),
                    onTap: enabled ? _manageHeritage : _setupHeritage,
                  ),
                  if (enabled)
                    ListTile(
                      leading: const Icon(Icons.timer_outlined),
                      title: Text(t.heritageThresholdTileTitle),
                      subtitle: Text(
                        t.heritageThresholdTileSubtitle(
                          '$threshold',
                          inactivity < 0 ? '—' : '$inactivity j',
                        ),
                        style: const TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right, size: 18),
                      onTap: () => _changeHeritageThreshold(threshold),
                    ),
                ],
              );
            },
          ),

          _section(t.settingsSectionData),
          ListTile(
            leading: const Icon(Icons.shield_outlined),
            title: Text(t.settingsBackupEncryptedTitle),
            subtitle: Text(t.settingsBackupEncryptedSubtitle),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _exportEncrypted,
          ),
          ListTile(
            leading: const Icon(Icons.download_outlined),
            title: Text(t.settingsImportTitle),
            subtitle: Text(t.settingsImportSubtitle),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _importFile,
          ),
          ListTile(
            leading: const Icon(Icons.upload_outlined),
            title: Text(t.settingsExportPlainTitle),
            subtitle: Text(t.settingsExportPlainSubtitle),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _exportVault,
          ),

          _section(t.settingsSectionDanger),
          ListTile(
            leading: const Icon(
              Icons.delete_forever_outlined,
              color: kDestructiveRed,
            ),
            title: Text(
              t.settingsDeleteAllTitle,
              style: TextStyle(color: cs.error),
            ),
            subtitle: Text(t.settingsDeleteAllSubtitle),
            onTap: _deleteAll,
          ),
        ]),
      ),
    );
  }

  /// Pose chaque réglage sur sa propre carte, en laissant passer les titres de
  /// section et les éléments qui gèrent déjà leur propre encadrement.
  ///
  /// Volontairement appliqué à la liste entière plutôt qu'écrit sur chaque
  /// tuile : la centaine de lignes de `onTap`, de dialogues et de
  /// `FutureBuilder` de cet écran n'est pas touchée, donc aucune régression de
  /// comportement n'est possible — seule la présentation change.
  List<Widget> _decorate(List<Widget> items) => [
    for (final w in items)
      if (w is _SectionTitle || w is _Undecorated)
        w
      else
        Card(margin: const EdgeInsets.only(bottom: 6), child: w),
  ];

  Widget _section(String title) => _SectionTitle(title);
}

/// Titre de section, repris tel quel de « À propos » : discret, en
/// `onSurfaceVariant`, il structure sans capter le regard. L'espacement fait
/// partie du widget pour que la liste reste lisible à la lecture du code.
class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle(this.title);

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(2, 20, 2, 8),
    child: Text(
      title,
      // UI 2026-08-03 — titres agrandis à la demande. `titleMedium` (16 sp)
      // plutôt que le `titleSmall` (14 sp) de « À propos » : les Réglages
      // comptent neuf sections, on y navigue en cherchant un titre du regard,
      // alors qu'« À propos » se lit d'un trait.
      //
      // Couleur : `cs.primary`, c'est-à-dire le bleu de la marque — celui du
      // damier du logo (#0B5FC7) en thème clair.
      //
      // ⚠️ Ce bleu n'est PAS codé en dur, et il ne faut pas le faire : sur le
      // fond sombre (#0D1117) il ne donne qu'environ 3,4:1 de contraste, sous
      // l'exigence AA de 4,5:1. `cs.primary` rend le bleu du damier en clair et
      // bascule sur #58A6FF en sombre, où le contraste repasse au-dessus du
      // seuil. C'est la même erreur que celle corrigée en v2.4.4 sur les
      // `Colors.grey` codés en dur (U5).
      style: Theme.of(context).textTheme.titleMedium?.copyWith(
        color: Theme.of(context).colorScheme.primary,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.3,
      ),
    ),
  );
}

/// Marque un élément qui décide lui-même de son encadrement.
///
/// Nécessaire pour les blocs dont le contenu peut être VIDE : les envelopper
/// systématiquement dans une carte laisserait une carte vide à l'écran. C'est
/// le cas du bouton « Révéler l'application », affiché seulement quand le mode
/// panique est actif.
class _Undecorated extends StatelessWidget {
  final Widget child;
  const _Undecorated({required this.child});

  @override
  Widget build(BuildContext context) => child;
}

class _PassphraseDialog extends StatefulWidget {
  final String title;
  final bool confirm;
  const _PassphraseDialog({required this.title, required this.confirm});

  @override
  State<_PassphraseDialog> createState() => _PassphraseDialogState();
}

class _PassphraseDialogState extends State<_PassphraseDialog> {
  final _ctrl1 = TextEditingController();
  final _ctrl2 = TextEditingController();
  String? _error;

  @override
  void dispose() {
    // B8 v2.3.8 — clear AVANT dispose : signal d'intention de wipe
    // (String Dart reste immutable, mais ça libère la référence du
    // controller plus tôt et fait écrire du vide dans toute couche
    // d'observabilité Flutter qui sniffe le controller).
    _ctrl1.clear();
    _ctrl2.clear();
    _ctrl1.dispose();
    _ctrl2.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            widget.confirm
                ? t.passphraseDialogConfirmHelper
                : t.passphraseDialogEnterHelper,
            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          PasswordTextField(
            controller: _ctrl1,
            labelText: widget.confirm
                ? t.passphraseLabelMin
                : t.passphraseLabel,
            autofocus: true,
            showPrefixIcon: false,
          ),
          if (widget.confirm) ...[
            const SizedBox(height: 12),
            PasswordTextField(
              controller: _ctrl2,
              labelText: t.passphraseConfirmLabel,
              showPrefixIcon: false,
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: cs.error, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => nav.pop(), child: Text(t.actionCancel)),
        FilledButton(
          onPressed: () {
            if (_ctrl1.text.isEmpty) {
              setState(() => _error = t.passphraseErrorEmpty);
              return;
            }
            if (widget.confirm) {
              // SEC 2026-08-04 — ce dialogue sert la phrase secrète d'une
              // sauvegarde `.ptbak`, le mot de passe du coffre leurre ET celui
              // de l'héritier. Il ne vérifiait que la longueur : `aaaaaaaaaaaa`
              // passait. Le cas du `.ptbak` est le plus grave — c'est le seul
              // fichier NON lié au matériel, donc le seul attaquable hors ligne
              // depuis une simple copie.
              //
              // `confirm: false` (restauration d'une sauvegarde) ne passe pas
              // par ici : on y SAISIT une phrase existante, il n'y a rien à
              // valider.
              switch (PasswordPolicy.check(_ctrl1.text)) {
                case PasswordRejection.tooShort:
                  setState(() => _error = t.passphraseErrorMin);
                  return;
                case PasswordRejection.tooWeak:
                  setState(() => _error = t.passwordTooWeak);
                  return;
                case null:
                  break;
              }
              if (_ctrl1.text != _ctrl2.text) {
                setState(() => _error = t.passphraseErrorMismatch);
                return;
              }
            }
            nav.pop(_ctrl1.text);
          },
          child: Text(
            widget.confirm ? t.passphraseEncryptCta : t.passphraseDecryptCta,
          ),
        ),
      ],
    );
  }
}

/// Demande le mot de passe maître avant une opération sensible.
/// `StatefulWidget` pour disposer proprement le contrôleur et vider son tampon.
class _ReauthDialog extends StatefulWidget {
  const _ReauthDialog();

  @override
  State<_ReauthDialog> createState() => _ReauthDialogState();
}

class _ReauthDialogState extends State<_ReauthDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
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
      icon: const Icon(Icons.lock_outline, size: 32),
      title: Text(t.reauthTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(t.reauthBody, style: const TextStyle(fontSize: 13)),
          const SizedBox(height: 12),
          PasswordTextField(
            controller: _ctrl,
            labelText: t.changePasswordCurrentLabel,
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
        FilledButton(onPressed: _submit, child: Text(t.actionContinue)),
      ],
    );
  }
}

class _ChangePasswordDialog extends StatefulWidget {
  const _ChangePasswordDialog();

  @override
  State<_ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends State<_ChangePasswordDialog> {
  /// SEC F10 v2.5.2 — champ « mot de passe actuel ». Son absence permettait à
  /// quiconque disposait d'un accès momentané à une session déverrouillée de
  /// pivoter le secret et de verrouiller définitivement le propriétaire.
  final _ctrlCurrent = TextEditingController();
  final _ctrl1 = TextEditingController();
  final _ctrl2 = TextEditingController();
  String? _error;

  @override
  void dispose() {
    // B9 v2.3.8 — clear master password ctrls avant dispose.
    _ctrlCurrent.clear();
    _ctrl1.clear();
    _ctrl2.clear();
    _ctrlCurrent.dispose();
    _ctrl1.dispose();
    _ctrl2.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(t.changePasswordDialogTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          PasswordTextField(
            controller: _ctrlCurrent,
            labelText: t.changePasswordCurrentLabel,
            showPrefixIcon: false,
          ),
          const SizedBox(height: 12),
          PasswordTextField(
            controller: _ctrl1,
            labelText: t.changePasswordNewLabel,
            showPrefixIcon: false,
          ),
          const SizedBox(height: 12),
          PasswordTextField(
            controller: _ctrl2,
            labelText: t.changePasswordConfirmLabel,
            showPrefixIcon: false,
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: cs.error, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => nav.pop(), child: Text(t.actionCancel)),
        FilledButton(
          onPressed: () {
            if (_ctrlCurrent.text.isEmpty) {
              setState(() => _error = t.changePasswordErrorCurrentRequired);
              return;
            }
            // SEC 2026-08-04 — ce chemin ne contrôlait QUE la longueur.
            //
            // On pouvait donc créer un coffre avec un mot de passe solide —
            // l'écran de création, lui, vérifiait l'entropie — puis le
            // remplacer ici par `aaaaaaaaaaaa` : douze caractères, accepté sans
            // broncher. La garde protégeait la porte d'entrée pendant que la
            // porte de service restait ouverte, et c'est celle-ci qui permet de
            // DÉGRADER un coffre existant.
            switch (PasswordPolicy.check(_ctrl1.text)) {
              case PasswordRejection.tooShort:
                setState(() => _error = t.changePasswordErrorMin);
                return;
              case PasswordRejection.tooWeak:
                setState(() => _error = t.passwordTooWeak);
                return;
              case null:
                break;
            }
            if (_ctrl1.text != _ctrl2.text) {
              setState(() => _error = t.changePasswordErrorMismatch);
              return;
            }
            nav.pop((current: _ctrlCurrent.text, fresh: _ctrl1.text));
          },
          child: Text(t.changePasswordCta),
        ),
      ],
    );
  }
}
