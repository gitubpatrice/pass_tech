import 'dart:convert';
import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
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
import '../main.dart' show themeNotifier, parseThemeMode, themeModeToString;
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
  bool _antiPhishingEnabled = false;
  bool _antiPhishingASActive = false;

  static const _clipOptions = [
    (label: '15 secondes', value: 15),
    (label: '30 secondes', value: 30),
    (label: '60 secondes', value: 60),
    (label: 'Jamais', value: 0),
  ];

  static const _lockOptions = [
    (label: 'Immédiatement', value: 0),
    (label: '1 minute', value: 60),
    (label: '5 minutes', value: 300),
    (label: '15 minutes', value: 900),
    (label: '30 minutes', value: 1800),
    (label: 'Jamais', value: -1),
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
        _antiPhishingEnabled = apEnabled;
        _antiPhishingASActive = apASActive;
      });
    }
  }

  Future<void> _toggleAntiPhishing(bool v) async {
    if (v) {
      // Consent flow : explique le service d'accessibilité et ouvre les
      // Réglages Android (l'utilisateur DOIT activer manuellement).
      final go = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          icon: const Icon(Icons.verified_user_outlined, size: 36),
          title: const Text('Anti-phishing par domaine'),
          content: const Text(
            'Pass Tech peut vérifier que le navigateur frontal affiche bien le '
            'domaine de l\'entrée AVANT de copier votre mot de passe. Si le '
            'domaine ne correspond pas (typosquatting, faux site), une alerte '
            's\'affiche.\n\n'
            'Pour cela, l\'app utilise un service d\'accessibilité Android :\n'
            '• Lit uniquement le domaine racine de la barre d\'URL\n'
            '• Uniquement sur les navigateurs reconnus (Chrome, Firefox, '
            'Brave, Edge, Opera, Vivaldi, Samsung, DuckDuckGo)\n'
            '• Aucune donnée stockée ni transmise — mémoire volatile\n'
            '• Désactivable à tout moment dans les Réglages Android\n\n'
            'Sur l\'écran suivant, activez "Pass Tech — anti-phishing".',
            style: TextStyle(fontSize: 12),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Continuer'),
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

  String get _themeLabel {
    switch (_themeMode) {
      case ThemeMode.light:
        return 'Clair';
      case ThemeMode.dark:
        return 'Sombre';
      case ThemeMode.system:
        return 'Système';
    }
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
    // Avertissement explicatif
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(Icons.family_restroom, size: 36),
        title: const Text('Configurer un héritier'),
        content: const Text(
          'Un mot de passe distinct du vôtre permettra à un proche '
          '(conjoint, enfant, exécuteur testamentaire) d\'accéder au '
          'contenu du coffre **après une période d\'inactivité prolongée** '
          'de votre part (90 jours par défaut + 7 jours de grâce).\n\n'
          'À retenir :\n'
          '• Le mot de passe héritier doit être différent de votre mot '
          'de passe principal\n'
          '• Communiquez-le à votre héritier de manière sûre (oralement, '
          'testament, coffre bancaire) — il n\'est jamais stocké et ne '
          'peut pas être récupéré\n'
          '• L\'héritier accédera à un instantané (snapshot) du coffre. '
          'Pensez à le mettre à jour régulièrement.\n'
          '• Si vous vous reconnectez avant la fin de la grâce, le compte '
          'à rebours est réinitialisé.\n'
          '• L\'existence d\'un snapshot héritage est détectable par '
          'analyse forensique du téléphone (pas de déni plausible — '
          'pour ça, voir le coffre leurre).\n\n'
          'Ce système fonctionne 100 % localement, sans cloud ni tiers '
          'de confiance.',
          style: TextStyle(fontSize: 12),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Configurer'),
          ),
        ],
      ),
    );
    if (go != true || !mounted) return;

    // Refus si pas dans le primary (l'héritage doit refléter le vrai coffre)
    if (VaultService().isDecoyActive) {
      messenger.showSnackBar(
        const SnackBar(
          content: Text(
            'L\'héritage n\'est disponible que sur le coffre principal',
          ),
        ),
      );
      return;
    }

    final pwd = await showDialog<String>(
      context: context,
      builder: (_) => const _PassphraseDialog(
        title: 'Mot de passe de l\'héritier',
        confirm: true,
      ),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return;

    // Vérifie que le password diffère du primary
    final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
    if (matchesPrimary) {
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Le mot de passe héritier doit différer du principal'),
        ),
      );
      return;
    }

    try {
      await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
      if (!mounted) return;
      setState(() {});
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Héritage configuré ✓ Snapshot du coffre chiffré.'),
        ),
      );
    } on StateError catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    } on ArgumentError catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('${e.message}')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _manageHeritage() async {
    final action = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Gérer l\'héritage'),
        content: const Text(
          'L\'héritier accède à un instantané du coffre. Vous pouvez :\n\n'
          '• Mettre à jour le snapshot (avec le mot de passe héritier)\n'
          '• Désactiver l\'héritage et supprimer le snapshot',
          style: TextStyle(fontSize: 12),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'cancel'),
            child: const Text('Annuler'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'disable'),
            child: const Text(
              'Désactiver',
              style: TextStyle(color: Colors.red),
            ),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'update'),
            child: const Text('Mettre à jour'),
          ),
        ],
      ),
    );
    if (!mounted || action == null || action == 'cancel') return;
    if (action == 'disable') {
      await HeritageService().disable();
      if (!mounted) return;
      setState(() {});
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Héritage désactivé, snapshot supprimé')),
      );
    } else if (action == 'update') {
      // Re-prompt heir password pour confirmer + sauvegarder
      final pwd = await showDialog<String>(
        context: context,
        builder: (_) => const _PassphraseDialog(
          title: 'Mot de passe héritier (re-saisie)',
          confirm: true,
        ),
      );
      if (pwd == null || pwd.isEmpty || !mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
      if (matchesPrimary) {
        messenger.showSnackBar(
          const SnackBar(
            content: Text('Doit différer du mot de passe principal'),
          ),
        );
        return;
      }
      try {
        await HeritageService().setupOrUpdateSnapshot(heirPassword: pwd);
        if (!mounted) return;
        setState(() {});
        messenger.showSnackBar(
          const SnackBar(content: Text('Snapshot mis à jour ✓')),
        );
      } catch (e) {
        messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
      }
    }
  }

  Future<void> _changeHeritageThreshold(int current) async {
    final v = await showDialog<int>(
      context: context,
      builder: (_) => SimpleDialog(
        title: const Text('Période d\'inactivité avant accès héritier'),
        children: [30, 60, 90, 180, 365]
            .map(
              (d) => ListTile(
                title: Text('$d jours'),
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
      ).showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _setupDecoy() async {
    // Avertissement explicatif avant la configuration.
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(Icons.shield_moon_outlined, size: 36),
        title: const Text('Coffre leurre'),
        content: const Text(
          'Un coffre leurre est un 2e coffre, totalement séparé, ouvert avec '
          'un mot de passe différent.\n\n'
          'Usage : si quelqu\'un vous force à ouvrir Pass Tech (frontière, '
          'agression, contrôle), vous donnez le mot de passe du leurre. '
          'L\'app affiche alors un faux coffre — il est cryptographiquement '
          'impossible de prouver l\'existence du vrai.\n\n'
          'À retenir :\n'
          '• Le mot de passe du leurre doit être différent du vrai\n'
          '• Remplissez le leurre avec quelques entrées crédibles\n'
          '• Vous ne pourrez pas activer la biométrique sur le leurre',
          style: TextStyle(fontSize: 12),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Configurer'),
          ),
        ],
      ),
    );
    if (go != true || !mounted) return;

    final pwd = await showDialog<String>(
      context: context,
      builder: (_) => const _PassphraseDialog(
        title: 'Mot de passe du coffre leurre',
        confirm: true,
      ),
    );
    if (pwd == null || pwd.isEmpty || !mounted) return;

    // Vérifie qu'il diffère du primary : on tente l'unlock contre primary
    // et s'il réussit, on refuse le setup (sinon les 2 slots seraient ouverts
    // par le même password).
    final messenger = ScaffoldMessenger.of(context);
    final matchesPrimary = await VaultService().passwordMatchesPrimary(pwd);
    if (matchesPrimary) {
      messenger.showSnackBar(
        const SnackBar(
          content: Text(
            'Ce mot de passe est déjà celui du coffre principal — '
            'choisissez-en un différent.',
          ),
        ),
      );
      return;
    }

    // Le slot va changer pendant setupDecoy → on lock après pour forcer
    // le user à se reconnecter sur le primary s'il veut continuer.
    try {
      await VaultService().setupDecoyVault(pwd);
      VaultService().lock();
      if (!mounted) return;
      setState(() {});
      messenger.showSnackBar(
        const SnackBar(
          content: Text(
            'Coffre leurre configuré — déverrouillez à nouveau pour continuer',
          ),
        ),
      );
      // Retour au unlock screen
      Navigator.of(context).popUntil((r) => r.isFirst);
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _manageDecoy() async {
    final action = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Coffre leurre'),
        content: const Text(
          'Le coffre leurre est actif.\n\n'
          'Vous pouvez le supprimer (action visible et silencieuse pour '
          'l\'attaquant — il pensera juste qu\'il s\'est trompé de mot de passe).',
          style: TextStyle(fontSize: 12),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'cancel'),
            child: const Text('Annuler'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'delete'),
            child: const Text(
              'Supprimer le leurre',
              style: TextStyle(color: Colors.red),
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
    ).showSnackBar(const SnackBar(content: Text('Coffre leurre supprimé')));
  }

  Future<void> _triggerPanic() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        icon: const Icon(
          Icons.warning_amber_rounded,
          size: 36,
          color: Colors.red,
        ),
        title: const Text('Mode panique ?'),
        content: const Text(
          'Cette action effectue immédiatement :\n\n'
          '• Verrouillage du coffre\n'
          '• Vidage du presse-papiers\n'
          '• Camouflage de l\'icône en « Calculatrice » sur le launcher\n\n'
          'Vous pourrez restaurer l\'icône depuis les Réglages quand vous '
          'serez en sécurité.',
          style: TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Activer'),
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
    await PanicService.revealApp();
    if (!mounted) return;
    setState(() {}); // Rafraîchit le FutureBuilder
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text(
          'Icône Pass Tech restaurée — relancez le launcher si elle n\'apparaît pas',
        ),
        duration: Duration(seconds: 5),
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

  String get _autoLockLabel => _lockOptions
      .firstWhere(
        (o) => o.value == _autoLockSeconds,
        orElse: () => _lockOptions[2],
      )
      .label;

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
        title: const Text(
          'Export NON CHIFFRÉ',
          style: TextStyle(color: Colors.red, fontWeight: FontWeight.bold),
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Le fichier exporté contiendra TOUS vos mots de passe '
              'EN CLAIR (lisibles par n\'importe qui).',
              style: TextStyle(color: Colors.red, fontWeight: FontWeight.w600),
            ),
            SizedBox(height: 12),
            Text('• Toute application ayant accès au stockage pourra le lire.'),
            SizedBox(height: 6),
            Text(
              '• Si vous le partagez via cloud (Drive, Mail…), il y restera.',
            ),
            SizedBox(height: 6),
            Text('• Supprimez-le IMMÉDIATEMENT après usage.'),
            SizedBox(height: 12),
            Text(
              'Préférez « Sauvegarde chiffrée (.ptbak) » sauf migration vers '
              'un autre gestionnaire.',
              style: TextStyle(fontStyle: FontStyle.italic),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Annuler'),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Exporter en clair'),
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
      ], subject: 'Pass Tech — export');
    } finally {
      // Suppression best-effort : sur Android, share_plus copie le fichier
      // au consommateur via FileProvider donc on peut supprimer la source.
      try {
        if (file.existsSync()) file.deleteSync();
      } catch (_) {}
    }
  }

  Future<void> _exportEncrypted() async {
    final messenger = ScaffoldMessenger.of(context);
    final passphrase = await showDialog<String>(
      context: context,
      builder: (_) =>
          const _PassphraseDialog(title: 'Sauvegarde chiffrée', confirm: true),
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
        ], subject: 'Pass Tech — sauvegarde chiffrée');
      } finally {
        try {
          if (file.existsSync()) file.deleteSync();
        } catch (_) {}
      }
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context).pop();
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _importFile() async {
    final messenger = ScaffoldMessenger.of(context);
    final picked = await FilePicker.pickFiles(
      type: FileType.any,
      withData: true,
    );
    if (picked == null || picked.files.isEmpty || !mounted) return;
    final file = picked.files.first;
    final bytes = file.bytes;
    if (bytes == null) {
      messenger.showSnackBar(
        const SnackBar(content: Text('Lecture du fichier impossible')),
      );
      return;
    }

    String content;
    try {
      // M-1 : utf8.decode pour préserver les caractères accentués (é, è, ñ…)
      // dans les CSV/JSON exportés par Bitwarden, KeePass, etc. Avec
      // String.fromCharCodes, les bytes UTF-8 multi-octets étaient interprétés
      // comme du Latin-1, corrompant silencieusement les entries.
      content = utf8.decode(bytes, allowMalformed: true);
    } catch (_) {
      messenger.showSnackBar(
        const SnackBar(content: Text('Fichier non texte')),
      );
      return;
    }

    List<Entry>? imported;
    String formatLabel = '';

    // Detect .ptbak
    final isPtbak =
        file.name.toLowerCase().endsWith('.ptbak') ||
        content.contains('"magic":"PTBAK"') ||
        content.contains('"magic": "PTBAK"');

    if (isPtbak) {
      final passphrase = await showDialog<String>(
        context: context,
        builder: (_) => const _PassphraseDialog(
          title: 'Restaurer la sauvegarde',
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
          const SnackBar(
            content: Text('Mot de passe incorrect ou fichier corrompu'),
          ),
        );
        return;
      }
      formatLabel = 'Sauvegarde chiffrée';
    } else {
      final result = ImportExportService.parse(content);
      if (result.error != null) {
        messenger.showSnackBar(SnackBar(content: Text(result.error!)));
        return;
      }
      imported = result.entries;
      formatLabel = _formatLabel(result.format);
    }

    if (imported.isEmpty) {
      messenger.showSnackBar(
        const SnackBar(content: Text('Aucune entrée détectée')),
      );
      return;
    }

    if (!mounted) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Confirmer l\'import'),
        content: Text(
          '${imported!.length} entrée${imported.length > 1 ? 's' : ''} détectée${imported.length > 1 ? 's' : ''}\n'
          'Format : $formatLabel\n\n'
          'Les doublons (même titre + identifiant) seront ignorés.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Importer'),
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
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            '$added entrée${added > 1 ? 's' : ''} importée${added > 1 ? 's' : ''}'
            '${skipped > 0 ? ' • $skipped doublon${skipped > 1 ? 's' : ''} ignoré${skipped > 1 ? 's' : ''}' : ''}',
          ),
        ),
      );
    }
  }

  String _formatLabel(String f) {
    switch (f) {
      case 'bitwarden':
        return 'Bitwarden JSON';
      case 'pass_tech':
        return 'Pass Tech JSON';
      case 'csv':
        return 'CSV (Chrome / Edge / autre)';
      default:
        return 'Format inconnu';
    }
  }

  Future<void> _changePassword() async {
    final nav = Navigator.of(context);
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
      ).showSnackBar(const SnackBar(content: Text('Mot de passe modifié ✓')));
      setState(() => _biometricEnabled = false);
    }
  }

  Future<void> _deleteAll() async {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer toutes les données ?'),
        content: const Text(
          'Toutes vos entrées et le coffre-fort seront supprimés définitivement.',
        ),
        actions: [
          TextButton(
            onPressed: () => nav.pop(false),
            child: const Text('Annuler'),
          ),
          TextButton(
            onPressed: () => nav.pop(true),
            style: TextButton.styleFrom(foregroundColor: cs.error),
            child: const Text('Tout supprimer'),
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

  String get _clipLabel => _clipOptions
      .firstWhere(
        (o) => o.value == _clipboardClear,
        orElse: () => _clipOptions[1],
      )
      .label;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Paramètres')),
      body: ListView(
        children: [
          _section('Sécurité'),
          if (_biometricAvailable)
            SwitchListTile(
              title: const Text('Déverrouillage biométrique'),
              subtitle: const Text('Empreinte digitale ou Face ID'),
              secondary: const Icon(Icons.fingerprint),
              value: _biometricEnabled,
              onChanged: _toggleBiometric,
            ),
          ListTile(
            leading: const Icon(Icons.key_outlined),
            title: const Text('Changer le mot de passe maître'),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _changePassword,
          ),
          ListTile(
            leading: const Icon(Icons.timer_outlined),
            title: const Text('Verrouillage automatique'),
            subtitle: Text(_autoLockLabel),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final v = await showDialog<int>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: const Text('Verrouiller l\'app après'),
                  children: _lockOptions
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
            title: const Text('Verrouiller maintenant'),
            onTap: _lockNow,
          ),
          ListTile(
            leading: const Icon(Icons.gpp_good_outlined),
            title: const Text('Audit de sécurité'),
            subtitle: const Text('Mots de passe faibles, doublons, fuites…'),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const AuditScreen()),
            ),
          ),

          _section('Coffre leurre (anti-coercition)'),
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
                      hasDecoy
                          ? 'Coffre leurre configuré'
                          : 'Configurer un coffre leurre',
                    ),
                    subtitle: const Text(
                      'Un 2e mot de passe ouvre un faux coffre. Si quelqu\'un '
                      'vous force à ouvrir l\'app, donnez le faux.',
                      style: TextStyle(fontSize: 11),
                    ),
                    trailing: const Icon(Icons.chevron_right, size: 18),
                    onTap: hasDecoy ? _manageDecoy : _setupDecoy,
                  ),
                ],
              );
            },
          ),

          _section('Anti-phishing par domaine'),
          SwitchListTile(
            title: const Text('Vérifier le domaine avant copie'),
            subtitle: Text(
              _antiPhishingEnabled
                  ? (_antiPhishingASActive
                        ? 'Actif — alerte si le navigateur n\'est pas sur le bon domaine'
                        : '⚠ Activez "Pass Tech — anti-phishing" dans Accessibilité')
                  : 'Compare le domaine du navigateur à celui de l\'entrée',
              style: const TextStyle(fontSize: 11),
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
              title: const Text('Ouvrir Réglages > Accessibilité'),
              subtitle: const Text(
                'Activez "Pass Tech — anti-phishing" dans la liste',
                style: TextStyle(fontSize: 11),
              ),
              trailing: const Icon(Icons.chevron_right, size: 18),
              onTap: () async {
                await AntiPhishingService().openAccessibilitySettings();
                final active =
                    await AntiPhishingService().isAccessibilityServiceActive;
                if (mounted) setState(() => _antiPhishingASActive = active);
              },
            ),

          _section('Mode panique'),
          ListTile(
            leading: Icon(
              Icons.warning_amber_rounded,
              color: Colors.red.shade700,
            ),
            title: const Text('Activer le mode panique'),
            subtitle: const Text(
              'Verrouille immédiatement, vide le presse-papiers et '
              'camoufle l\'icône en "Calculatrice"',
              style: TextStyle(fontSize: 11),
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
                title: const Text('Restaurer l\'icône Pass Tech'),
                subtitle: const Text(
                  'L\'app est actuellement camouflée en "Calculatrice"',
                  style: TextStyle(fontSize: 11),
                ),
                trailing: const Icon(Icons.chevron_right, size: 18),
                onTap: _revealApp,
              );
            },
          ),

          _section('Héritage (dead man\'s switch)'),
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
                      enabled ? 'Héritier configuré' : 'Configurer un héritier',
                    ),
                    subtitle: Text(
                      enabled
                          ? 'Toucher pour mettre à jour le snapshot ou désactiver'
                          : 'Un proche pourra accéder au coffre après une période '
                                'd\'inactivité prolongée. Aucun cloud, aucun tiers.',
                      style: const TextStyle(fontSize: 11),
                    ),
                    trailing: const Icon(Icons.chevron_right, size: 18),
                    onTap: enabled ? _manageHeritage : _setupHeritage,
                  ),
                  if (enabled)
                    ListTile(
                      leading: const Icon(Icons.timer_outlined),
                      title: const Text('Période d\'inactivité avant accès'),
                      subtitle: Text(
                        'Seuil : $threshold jours · '
                        'inactivité actuelle : ${inactivity < 0 ? "—" : "$inactivity j"}',
                        style: const TextStyle(fontSize: 11),
                      ),
                      trailing: const Icon(Icons.chevron_right, size: 18),
                      onTap: () => _changeHeritageThreshold(threshold),
                    ),
                ],
              );
            },
          ),

          _section('Apparence'),
          ListTile(
            leading: const Icon(Icons.brightness_6_outlined),
            title: const Text('Thème'),
            subtitle: Text(_themeLabel),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final m = await showDialog<ThemeMode>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: const Text('Choisir le thème'),
                  children:
                      [
                        (
                          'Système',
                          ThemeMode.system,
                          Icons.settings_brightness,
                        ),
                        ('Clair', ThemeMode.light, Icons.light_mode_outlined),
                        ('Sombre', ThemeMode.dark, Icons.dark_mode_outlined),
                      ].map((t) {
                        return ListTile(
                          leading: Icon(t.$3, size: 20),
                          title: Text(t.$1),
                          trailing: _themeMode == t.$2
                              ? const Icon(Icons.check)
                              : null,
                          onTap: () => Navigator.pop(context, t.$2),
                        );
                      }).toList(),
                ),
              );
              if (m != null) _setTheme(m);
            },
          ),

          _section('Presse-papiers'),
          ListTile(
            leading: const Icon(Icons.content_paste_off_outlined),
            title: const Text('Effacer automatiquement'),
            subtitle: Text(_clipLabel),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: () async {
              final v = await showDialog<int>(
                context: context,
                builder: (_) => SimpleDialog(
                  title: const Text('Effacer le presse-papiers après'),
                  children: _clipOptions
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

          _section('Données'),
          ListTile(
            leading: const Icon(Icons.shield_outlined),
            title: const Text('Sauvegarde chiffrée (.ptbak)'),
            subtitle: const Text(
              'Recommandé — restaurable avec votre passphrase',
            ),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _exportEncrypted,
          ),
          ListTile(
            leading: const Icon(Icons.download_outlined),
            title: const Text('Importer un fichier'),
            subtitle: const Text('CSV, Bitwarden JSON ou .ptbak'),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _importFile,
          ),
          ListTile(
            leading: const Icon(Icons.upload_outlined),
            title: const Text('Exporter (JSON non chiffré)'),
            subtitle: const Text('⚠️ mots de passe en clair'),
            trailing: const Icon(Icons.chevron_right, size: 18),
            onTap: _exportVault,
          ),

          _section('Zone dangereuse'),
          ListTile(
            leading: Icon(Icons.delete_forever_outlined, color: cs.error),
            title: Text(
              'Supprimer toutes les données',
              style: TextStyle(color: cs.error),
            ),
            subtitle: const Text('Action irréversible'),
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
  bool _show = false;
  String? _error;

  @override
  void dispose() {
    _ctrl1.dispose();
    _ctrl2.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            widget.confirm
                ? 'Choisissez une passphrase pour chiffrer la sauvegarde. Vous en aurez besoin pour restaurer.'
                : 'Entrez la passphrase utilisée lors de la sauvegarde.',
            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _ctrl1,
            obscureText: !_show,
            autofocus: true,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.visiblePassword,
            decoration: InputDecoration(
              labelText: widget.confirm ? 'Passphrase (min. 12)' : 'Passphrase',
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                icon: Icon(
                  _show ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                ),
                onPressed: () => setState(() => _show = !_show),
              ),
            ),
          ),
          if (widget.confirm) ...[
            const SizedBox(height: 12),
            TextField(
              controller: _ctrl2,
              obscureText: !_show,
              enableSuggestions: false,
              autocorrect: false,
              keyboardType: TextInputType.visiblePassword,
              decoration: const InputDecoration(
                labelText: 'Confirmer',
                border: OutlineInputBorder(),
              ),
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: cs.error, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => nav.pop(), child: const Text('Annuler')),
        FilledButton(
          onPressed: () {
            if (_ctrl1.text.isEmpty) {
              setState(() => _error = 'Passphrase vide');
              return;
            }
            if (widget.confirm) {
              if (_ctrl1.text.length < 12) {
                setState(() => _error = 'Minimum 12 caractères');
                return;
              }
              if (_ctrl1.text != _ctrl2.text) {
                setState(() => _error = 'Les passphrases ne correspondent pas');
                return;
              }
            }
            nav.pop(_ctrl1.text);
          },
          child: Text(widget.confirm ? 'Chiffrer' : 'Déchiffrer'),
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
  bool _show = false;
  String? _error;

  @override
  void dispose() {
    _ctrl1.dispose();
    _ctrl2.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final nav = Navigator.of(context);
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Nouveau mot de passe'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _ctrl1,
            obscureText: !_show,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.visiblePassword,
            decoration: InputDecoration(
              labelText: 'Nouveau mot de passe (min. 12)',
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                icon: Icon(
                  _show ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                ),
                onPressed: () => setState(() => _show = !_show),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _ctrl2,
            obscureText: !_show,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.visiblePassword,
            decoration: const InputDecoration(
              labelText: 'Confirmer',
              border: OutlineInputBorder(),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: cs.error, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => nav.pop(), child: const Text('Annuler')),
        FilledButton(
          onPressed: () {
            if (_ctrl1.text.length < 12) {
              setState(() => _error = 'Minimum 12 caractères');
              return;
            }
            if (_ctrl1.text != _ctrl2.text) {
              setState(() => _error = 'Les mots de passe ne correspondent pas');
              return;
            }
            nav.pop(_ctrl1.text);
          },
          child: const Text('Modifier'),
        ),
      ],
    );
  }
}
