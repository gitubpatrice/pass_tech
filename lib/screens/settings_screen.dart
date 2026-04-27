import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:biometric_storage/biometric_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/entry.dart';
import '../services/clipboard_service.dart';
import '../services/import_export_service.dart';
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

class _SettingsScreenState extends State<SettingsScreen> {
  bool _biometricEnabled   = false;
  bool _biometricAvailable = false;
  int  _clipboardClear     = 30;
  int  _autoLockSeconds    = 300;
  ThemeMode _themeMode     = ThemeMode.system;

  static const _clipOptions = [
    (label: '15 secondes', value: 15),
    (label: '30 secondes', value: 30),
    (label: '60 secondes', value: 60),
    (label: 'Jamais',      value: 0),
  ];

  static const _lockOptions = [
    (label: 'Immédiatement', value: 0),
    (label: '1 minute',      value: 60),
    (label: '5 minutes',     value: 300),
    (label: '15 minutes',    value: 900),
    (label: '30 minutes',    value: 1800),
    (label: 'Jamais',        value: -1),
  ];

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final canAuth  = await BiometricStorage().canAuthenticate();
    final canCheck = canAuth == CanAuthenticateResponse.success;
    final hasKey   = await VaultService().hasBiometricKey;
    final prefs    = await SharedPreferences.getInstance();
    final clip     = prefs.getInt('clipboard_clear')   ?? 30;
    final lock     = prefs.getInt('auto_lock_seconds') ?? 300;
    final theme    = parseThemeMode(prefs.getString('theme_mode') ?? 'system');
    ClipboardService.clearAfterSeconds = clip;
    if (mounted) {
      setState(() {
        _biometricAvailable = canCheck;
        _biometricEnabled   = canCheck && hasKey;
        _clipboardClear     = clip;
        _autoLockSeconds    = lock;
        _themeMode          = theme;
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
      case ThemeMode.light:  return 'Clair';
      case ThemeMode.dark:   return 'Sombre';
      case ThemeMode.system: return 'Système';
    }
  }

  Future<void> _setAutoLock(int v) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('auto_lock_seconds', v);
    if (mounted) setState(() => _autoLockSeconds = v);
  }

  void _lockNow() {
    VaultService().lock();
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const UnlockScreen()),
      (_) => false,
    );
  }

  String get _autoLockLabel => _lockOptions
      .firstWhere((o) => o.value == _autoLockSeconds,
          orElse: () => _lockOptions[2])
      .label;

  Future<void> _toggleBiometric(bool v) async {
    if (v) {
      try {
        // saveBiometricKey() writes to biometric_storage, which creates a
        // Keystore key with setUserAuthenticationRequired(true). The first
        // write — and every subsequent read — triggers BiometricPrompt.
        await VaultService().saveBiometricKey();
      } catch (_) { return; }
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
    final json = VaultService().exportJson();
    final dir  = await getTemporaryDirectory();
    final file = File('${dir.path}/pass_tech_export.json');
    await file.writeAsString(json);
    await Share.shareXFiles(
      [XFile(file.path, mimeType: 'application/json')],
      subject: 'Pass Tech — export',
    );
  }

  Future<void> _exportEncrypted() async {
    final messenger = ScaffoldMessenger.of(context);
    final passphrase = await showDialog<String>(
      context: context,
      builder: (_) => const _PassphraseDialog(
        title: 'Sauvegarde chiffrée',
        confirm: true,
      ),
    );
    if (passphrase == null || !mounted) return;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );

    try {
      final content = await ImportExportService.exportEncrypted(
          VaultService().entries, passphrase);
      final date = DateTime.now().toIso8601String().substring(0, 10);
      final dir  = await getTemporaryDirectory();
      final file = File('${dir.path}/pass_tech_$date.ptbak');
      await file.writeAsString(content);
      if (!mounted) return;
      Navigator.of(context).pop(); // close progress
      await Share.shareXFiles(
        [XFile(file.path, mimeType: 'application/octet-stream')],
        subject: 'Pass Tech — sauvegarde chiffrée',
      );
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context).pop();
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _importFile() async {
    final messenger = ScaffoldMessenger.of(context);
    final picked = await FilePicker.platform.pickFiles(
      type: FileType.any,
      withData: true,
    );
    if (picked == null || picked.files.isEmpty || !mounted) return;
    final file = picked.files.first;
    final bytes = file.bytes;
    if (bytes == null) {
      messenger.showSnackBar(
          const SnackBar(content: Text('Lecture du fichier impossible')));
      return;
    }

    String content;
    try {
      content = String.fromCharCodes(bytes);
    } catch (_) {
      messenger.showSnackBar(
          const SnackBar(content: Text('Fichier non texte')));
      return;
    }

    List<Entry>? imported;
    String formatLabel = '';

    // Detect .ptbak
    final isPtbak = file.name.toLowerCase().endsWith('.ptbak') ||
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
        messenger.showSnackBar(const SnackBar(
            content: Text('Mot de passe incorrect ou fichier corrompu')));
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
          const SnackBar(content: Text('Aucune entrée détectée')));
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
            'Les doublons (même titre + identifiant) seront ignorés.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Importer')),
        ],
      ),
    );
    if (go != true) return;

    final existing = VaultService().entries;
    int added = 0;
    int skipped = 0;
    for (final e in imported) {
      final dup = existing.any((x) =>
          x.title.toLowerCase() == e.title.toLowerCase() &&
          x.username.toLowerCase() == e.username.toLowerCase());
      if (dup) {
        skipped++;
      } else {
        await VaultService().addEntry(e);
        added++;
      }
    }
    widget.onChanged();
    if (mounted) {
      messenger.showSnackBar(SnackBar(content: Text(
          '$added entrée${added > 1 ? 's' : ''} importée${added > 1 ? 's' : ''}'
          '${skipped > 0 ? ' • $skipped doublon${skipped > 1 ? 's' : ''} ignoré${skipped > 1 ? 's' : ''}' : ''}')));
    }
  }

  String _formatLabel(String f) {
    switch (f) {
      case 'bitwarden': return 'Bitwarden JSON';
      case 'pass_tech': return 'Pass Tech JSON';
      case 'csv':       return 'CSV (Chrome / Edge / autre)';
      default:          return 'Format inconnu';
    }
  }

  Future<void> _changePassword() async {
    final nav    = Navigator.of(context);
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
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Mot de passe modifié ✓')));
      setState(() => _biometricEnabled = false);
    }
  }

  Future<void> _deleteAll() async {
    final nav = Navigator.of(context);
    final cs  = Theme.of(context).colorScheme;
    final ok  = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer toutes les données ?'),
        content: const Text(
            'Toutes vos entrées et le coffre-fort seront supprimés définitivement.'),
        actions: [
          TextButton(onPressed: () => nav.pop(false), child: const Text('Annuler')),
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
      .firstWhere((o) => o.value == _clipboardClear,
          orElse: () => _clipOptions[1])
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
                      .map((o) => ListTile(
                            title: Text(o.label),
                            trailing: _autoLockSeconds == o.value
                                ? const Icon(Icons.check)
                                : null,
                            onTap: () => Navigator.pop(context, o.value),
                          ))
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
            onTap: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const AuditScreen())),
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
                  children: [
                    ('Système', ThemeMode.system, Icons.settings_brightness),
                    ('Clair',   ThemeMode.light,  Icons.light_mode_outlined),
                    ('Sombre',  ThemeMode.dark,   Icons.dark_mode_outlined),
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
                      .map((o) => ListTile(
                            title: Text(o.label),
                            trailing: _clipboardClear == o.value
                                ? const Icon(Icons.check)
                                : null,
                            onTap: () => Navigator.pop(context, o.value),
                          ))
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
            subtitle: const Text('Recommandé — restaurable avec votre passphrase'),
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
            title: Text('Supprimer toutes les données',
                style: TextStyle(color: cs.error)),
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
        child: Text(title,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: Theme.of(context).colorScheme.primary,
                letterSpacing: 0.5)),
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
    final cs  = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(widget.title),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
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
              icon: Icon(_show ? Icons.visibility_off : Icons.visibility, size: 20),
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
      ]),
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
  bool _show   = false;
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
    final cs  = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Nouveau mot de passe'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
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
              icon: Icon(_show ? Icons.visibility_off : Icons.visibility, size: 20),
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
          Text(_error!,
              style: TextStyle(color: cs.error, fontSize: 12)),
        ],
      ]),
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
