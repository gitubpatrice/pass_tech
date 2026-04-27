import 'dart:io';
import 'package:flutter/material.dart';
import 'package:local_auth/local_auth.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/clipboard_service.dart';
import '../services/vault_service.dart';
import 'setup_screen.dart';
import 'unlock_screen.dart';

class SettingsScreen extends StatefulWidget {
  final VoidCallback onChanged;
  const SettingsScreen({super.key, required this.onChanged});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _auth = LocalAuthentication();
  bool _biometricEnabled   = false;
  bool _biometricAvailable = false;
  int  _clipboardClear     = 30;
  int  _autoLockSeconds    = 300;

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
    final canCheck = await _auth.canCheckBiometrics;
    final hasKey   = await VaultService().hasBiometricKey;
    final prefs    = await SharedPreferences.getInstance();
    final clip     = prefs.getInt('clipboard_clear')   ?? 30;
    final lock     = prefs.getInt('auto_lock_seconds') ?? 300;
    ClipboardService.clearAfterSeconds = clip;
    if (mounted) {
      setState(() {
        _biometricAvailable = canCheck;
        _biometricEnabled   = canCheck && hasKey;
        _clipboardClear     = clip;
        _autoLockSeconds    = lock;
      });
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
        final ok = await _auth.authenticate(
          localizedReason: 'Activer le déverrouillage biométrique',
        );
        if (!ok) return;
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
            leading: const Icon(Icons.upload_outlined),
            title: const Text('Exporter (JSON non chiffré)'),
            subtitle: const Text('Partager vos entrées en clair'),
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
          decoration: InputDecoration(
            labelText: 'Nouveau mot de passe',
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
            if (_ctrl1.text.length < 8) {
              setState(() => _error = 'Minimum 8 caractères');
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
