import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
// ignore: unnecessary_import
import 'package:flutter/semantics.dart';
import 'package:biometric_storage/biometric_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/entry.dart';
import '../services/anti_phishing_service.dart';
import '../services/clipboard_service.dart';
import '../services/heritage_service.dart';
import '../services/import_export_service.dart';
import '../services/panic_service.dart';
import '../services/vault_service.dart';
import '../widgets/password_text_field.dart';
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
      });
    }
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
      messenger.showSnackBar(
        SnackBar(content: Text(t.heritageDecoyActiveSnack)),
      );
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
      messenger.showSnackBar(SnackBar(content: Text(t.heirSamePasswordSnack)));
      return;
    }

    try {
      await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
      if (!mounted) return;
      setState(() {});
      messenger.showSnackBar(
        SnackBar(content: Text(t.heritageConfiguredSnack)),
      );
    } on StateError catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    } on ArgumentError catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('${e.message}')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(t.genericError('$e'))));
    }
  }

  Future<void> _manageHeritage() async {
    final t = AppLocalizations.of(context);
    final action = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.heritageManageTitle),
        content: Text(
          t.heritageManageBody,
          style: const TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'cancel'),
            child: Text(t.actionCancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'disable'),
            child: Text(
              t.heritageDisable,
              style: const TextStyle(color: Colors.red),
            ),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'update'),
            child: Text(t.heritageUpdate),
          ),
        ],
      ),
    );
    if (!mounted || action == null || action == 'cancel') return;
    if (action == 'disable') {
      await HeritageService().disable();
      if (!mounted) return;
      setState(() {});
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(t.heritageDisabledSnack)));
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
        messenger.showSnackBar(
          SnackBar(content: Text(t.heirSamePasswordShortSnack)),
        );
        return;
      }
      try {
        await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
        if (!mounted) return;
        setState(() {});
        messenger.showSnackBar(
          SnackBar(content: Text(t.heritageSnapshotUpdatedSnack)),
        );
      } catch (e) {
        messenger.showSnackBar(SnackBar(content: Text(t.genericError('$e'))));
      }
    }
  }

  Future<void> _changeHeritageThreshold(int current) async {
    final t = AppLocalizations.of(context);
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
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(t.genericError('$e'))));
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
      messenger.showSnackBar(SnackBar(content: Text(t.decoySamePasswordError)));
      return;
    }

    // Le slot va changer pendant setupDecoy → on lock après pour forcer
    // le user à se reconnecter sur le primary s'il veut continuer.
    try {
      await VaultService().setupDecoyVault(pwd);
      VaultService().lock();
      if (!mounted) return;
      setState(() {});
      messenger.showSnackBar(SnackBar(content: Text(t.decoyConfiguredSnack)));
      // Retour au unlock screen
      Navigator.of(context).popUntil((r) => r.isFirst);
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text(t.genericError('$e'))));
    }
  }

  Future<void> _manageDecoy() async {
    final t = AppLocalizations.of(context);
    final action = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.decoyDialogTitle),
        content: Text(t.decoyManageBody, style: const TextStyle(fontSize: 13)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'cancel'),
            child: Text(t.actionCancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'delete'),
            child: Text(
              t.decoyDelete,
              style: const TextStyle(color: Colors.red),
            ),
          ),
        ],
      ),
    );
    if (action != 'delete' || !mounted) return;
    await VaultService().deleteDecoyVault();
    if (!mounted) return;
    setState(() {});
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(t.decoyDeletedSnack)));
  }

  Future<void> _triggerPanic() async {
    final t = AppLocalizations.of(context);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(
          Icons.warning_amber_rounded,
          size: 36,
          color: Colors.red,
        ),
        title: Text(t.panicDialogTitle),
        content: Text(t.panicDialogBody, style: const TextStyle(fontSize: 14)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(t.actionCancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: Text(t.panicDialogActivate),
          ),
        ],
      ),
    );
    if (confirm != true || !mounted) return;
    await PanicService.panic();
    if (!mounted) return;
    // Retour à l'écran de déverrouillage (vault est lock).
    Navigator.of(context).popUntil((r) => r.isFirst);
  }

  Future<void> _revealApp() async {
    final t = AppLocalizations.of(context);
    await PanicService.revealApp();
    if (!mounted) return;
    setState(() {}); // Rafraîchit le FutureBuilder
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(t.panicRevealSnack),
        duration: const Duration(seconds: 5),
      ),
    );
  }

  void _lockNow() {
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
    if (v) {
      try {
        // saveBiometricKey() écrit dans biometric_storage, qui crée une
        // clé Keystore avec setUserAuthenticationRequired(true). La première
        // écriture — et chaque lecture suivante — déclenche BiometricPrompt.
        // Lance StateError si le slot actif n'est pas primary (sécurité
        // dual-vault). On absorbe silencieusement pour ne pas trahir
        // l'existence du decoy à un attaquant attentif.
        await VaultService().saveBiometricKey();
      } catch (_) {
        return;
      }
    } else {
      await VaultService().deleteBiometricKey();
    }
    if (mounted) setState(() => _biometricEnabled = v);
  }

  Future<void> _setClipboard(int v) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('clipboard_clear', v);
    ClipboardService.clearAfterSeconds = v;
    if (mounted) setState(() => _clipboardClear = v);
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
      builder: (ctx) => AlertDialog(
        icon: const Icon(
          Icons.warning_amber_rounded,
          color: Colors.red,
          size: 40,
        ),
        title: Text(
          t.exportPlainDialogTitle,
          style: const TextStyle(
            color: Colors.red,
            fontWeight: FontWeight.bold,
          ),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              t.exportPlainWarningHeadline,
              style: const TextStyle(
                color: Colors.red,
                fontWeight: FontWeight.w600,
              ),
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
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(t.actionCancel),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(t.exportPlainConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    final json = VaultService().exportJson();
    final dir = await getTemporaryDirectory();
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
      try {
        if (file.existsSync()) {
          final len = file.lengthSync();
          if (len > 0) {
            final rand = SecretBytes.randomBytes(len);
            file.writeAsBytesSync(rand, flush: true);
          }
          file.deleteSync();
        }
      } catch (_) {}
    }
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
      final file = File('${dir.path}/pass_tech_$date.ptbak');
      await file.writeAsString(content);
      if (!mounted) return;
      Navigator.of(context).pop(); // close progress
      try {
        await Share.shareXFiles([
          XFile(file.path, mimeType: 'application/octet-stream'),
        ], subject: t.exportEncryptedShareSubject);
      } finally {
        try {
          if (file.existsSync()) file.deleteSync();
        } catch (_) {}
      }
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context).pop();
      messenger.showSnackBar(SnackBar(content: Text(t.genericError('$e'))));
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
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            t.importTooLarge(
              (probeFile.size / 1024 / 1024).toStringAsFixed(0),
              '${_kMaxImportBytes ~/ (1024 * 1024)}',
            ),
          ),
        ),
      );
      return;
    }
    // 2. Le path Android est rempli par défaut → on lit directement.
    final filePath = probeFile.path;
    if (filePath == null) {
      messenger.showSnackBar(SnackBar(content: Text(t.importReadError)));
      return;
    }
    final Uint8List bytes;
    try {
      bytes = await File(filePath).readAsBytes();
    } catch (_) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text(t.importReadError)));
      return;
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
      messenger.showSnackBar(SnackBar(content: Text(t.importNotText)));
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
        messenger.showSnackBar(
          SnackBar(content: Text(t.importWrongPassphrase)),
        );
        return;
      }
      formatLabel = t.importFormatEncryptedBackup;
    } else {
      final result = ImportExportService.parse(content);
      if (result.error != null) {
        messenger.showSnackBar(SnackBar(content: Text(result.error!)));
        return;
      }
      imported = result.entries;
      formatLabel = _formatLabel(t, result.format);
    }

    if (imported.isEmpty) {
      messenger.showSnackBar(SnackBar(content: Text(t.importNoEntry)));
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
      messenger.showSnackBar(
        SnackBar(content: Text(t.importDoneSnack(added, skippedSuffix))),
      );
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
    final result = await showDialog<String>(
      context: context,
      builder: (_) => const _ChangePasswordDialog(),
    );
    if (result == null || !mounted) return;
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );
    await VaultService().changeMasterPassword(result);
    if (mounted) {
      nav.pop(); // close progress dialog
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(t.changePasswordDoneSnack)));
      setState(() => _biometricEnabled = false);
    }
  }

  Future<void> _deleteAll() async {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(t.settingsDeleteAllDialogTitle),
        content: Text(t.settingsDeleteAllDialogBody),
        actions: [
          TextButton(
            onPressed: () => nav.pop(false),
            child: Text(t.actionCancel),
          ),
          TextButton(
            onPressed: () => nav.pop(true),
            style: TextButton.styleFrom(foregroundColor: cs.error),
            child: Text(t.settingsDeleteAllConfirm),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await VaultService().deleteVault();
    if (mounted) {
      nav.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const SetupScreen()),
        (_) => false,
      );
    }
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
      body: ListView(
        children: [
          _section(t.settingsSectionSecurity),
          if (_biometricAvailable)
            SwitchListTile(
              title: Text(t.settingsBiometricTitle),
              subtitle: Text(t.settingsBiometricSubtitle),
              secondary: const Icon(Icons.fingerprint),
              value: _biometricEnabled,
              onChanged: _toggleBiometric,
            ),
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
                      color: hasDecoy ? Colors.green : null,
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
                  ? Colors.green
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
              color: Colors.red.shade700,
            ),
            title: Text(t.panicTriggerTitle),
            subtitle: Text(
              t.panicTriggerSubtitle,
              style: const TextStyle(fontSize: 12),
            ),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _triggerPanic,
          ),
          FutureBuilder<bool>(
            future: PanicService.isDisguised(),
            builder: (_, snap) {
              if (snap.data != true) return const SizedBox.shrink();
              return ListTile(
                leading: Icon(Icons.visibility, color: Colors.green.shade700),
                title: Text(t.panicRevealTitle),
                subtitle: Text(
                  t.panicRevealSubtitle,
                  style: const TextStyle(fontSize: 12),
                ),
                trailing: const Icon(Icons.chevron_right, size: 18),
                onTap: _revealApp,
              );
            },
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
                      color: enabled ? Colors.green : null,
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
            leading: Icon(Icons.delete_forever_outlined, color: cs.error),
            title: Text(
              t.settingsDeleteAllTitle,
              style: TextStyle(color: cs.error),
            ),
            subtitle: Text(t.settingsDeleteAllSubtitle),
            onTap: _deleteAll,
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  Widget _section(String title) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
    child: Text(
      title,
      style: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w700,
        color: Theme.of(context).colorScheme.primary,
        letterSpacing: 0.5,
      ),
    ),
  );
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
              if (_ctrl1.text.length < 12) {
                setState(() => _error = t.passphraseErrorMin);
                return;
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

class _ChangePasswordDialog extends StatefulWidget {
  const _ChangePasswordDialog();

  @override
  State<_ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends State<_ChangePasswordDialog> {
  final _ctrl1 = TextEditingController();
  final _ctrl2 = TextEditingController();
  String? _error;

  @override
  void dispose() {
    // B9 v2.3.8 — clear master password ctrls avant dispose.
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
      title: Text(t.changePasswordDialogTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
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
            if (_ctrl1.text.length < 12) {
              setState(() => _error = t.changePasswordErrorMin);
              return;
            }
            if (_ctrl1.text != _ctrl2.text) {
              setState(() => _error = t.changePasswordErrorMismatch);
              return;
            }
            nav.pop(_ctrl1.text);
          },
          child: Text(t.changePasswordCta),
        ),
      ],
    );
  }
}
