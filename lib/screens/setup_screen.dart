import 'package:flutter/material.dart';
import '../l10n/app_localizations.dart';
import '../services/heritage_service.dart';
import '../services/password_strength_service.dart';
import '../services/vault_service.dart';
import '../widgets/password_text_field.dart';
import 'home_screen.dart';

class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  final _pass1 = TextEditingController();
  final _pass2 = TextEditingController();
  bool _loading = false;
  String? _error;

  /// P5 v2.4.4 — `ValueNotifier` lié à `_pass1.text` pour rebuilder UNIQUEMENT
  /// la jauge de force. Avant : `onChanged: (_) => setState(() {})` sur les
  /// 2 PasswordTextField rebuilder TOUT l'écran à chaque frappe (3-5 ms × N
  /// frappes pour un master password 30 chars), et `_pass2.onChanged` ne
  /// change pas la jauge donc le rebuild était gaspillé. Désormais : jauge
  /// scope-isolée dans un `ValueListenableBuilder`, le rebuild plein écran
  /// se limite aux clics utiles (toggle show, error reset).
  final ValueNotifier<String> _pass1Notifier = ValueNotifier<String>('');

  @override
  void dispose() {
    _pass1.dispose();
    _pass2.dispose();
    _pass1Notifier.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    final t = AppLocalizations.of(context);
    final p1 = _pass1.text;
    final p2 = _pass2.text;
    if (p1.length < 12) {
      setState(() => _error = t.setupErrorMin);
      return;
    }
    if (PasswordStrengthService.score(p1) < 0.6) {
      setState(() => _error = t.setupErrorWeak);
      return;
    }
    if (p1 != p2) {
      setState(() => _error = t.setupErrorMismatch);
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    await VaultService().createVault(p1);
    // Initialise le timestamp dead-man : le compteur d'inactivité commence
    // à 0 dès la création du vault.
    await HeritageService().markActive();
    _pass1.clear();
    _pass2.clear();
    if (!mounted) return;
    Navigator.of(
      context,
    ).pushReplacement(MaterialPageRoute(builder: (_) => const HomeScreen()));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 80,
                  height: 80,
                  decoration: BoxDecoration(
                    color: cs.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Icon(Icons.lock_outline, size: 44, color: cs.primary),
                ),
                const SizedBox(height: 24),
                Text(
                  t.setupTitle,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Text(
                  t.setupSubtitle,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 32),

                PasswordTextField(
                  controller: _pass1,
                  labelText: t.setupMasterLabel,
                  helperText: t.setupMasterHelper,
                  onChanged: (v) => _pass1Notifier.value = v,
                ),

                // P5 v2.4.4 — jauge isolée dans ValueListenableBuilder. Le
                // rebuild plein écran ne se déclenche plus à chaque frappe.
                ValueListenableBuilder<String>(
                  valueListenable: _pass1Notifier,
                  builder: (context, pass, _) {
                    if (pass.isEmpty) return const SizedBox.shrink();
                    final s = PasswordStrengthService.score(pass);
                    return Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        children: [
                          Expanded(
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: LinearProgressIndicator(
                                value: s,
                                minHeight: 6,
                                backgroundColor: cs.surfaceContainerHighest,
                                valueColor: AlwaysStoppedAnimation(
                                  PasswordStrengthService.color(s),
                                ),
                                // U8 v2.4.4 — Semantics value% pour TalkBack
                                // (avant : muet, l'aveugle ne sait pas que
                                // sa frappe a renforcé / affaibli le score).
                                semanticsLabel: t.setupMasterLabel,
                                semanticsValue:
                                    '${(s * 100).round()}% — '
                                    '${PasswordStrengthService.label(s, t)}',
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Text(
                            PasswordStrengthService.label(s, t),
                            style: TextStyle(
                              fontSize: 12,
                              color: PasswordStrengthService.color(s),
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),

                const SizedBox(height: 16),

                PasswordTextField(
                  controller: _pass2,
                  labelText: t.setupConfirmLabel,
                  // P5 v2.4.4 — pas de setState au pass2 : la mismatch est
                  // levée DANS `_create()`, pas pendant la frappe. Évite un
                  // rebuild plein écran par caractère.
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
                  // U6 v2.4.3 — Semantics.liveRegion annonce le statut à
                  // TalkBack et inclut la phrase d'action explicite. Sans ça,
                  // les 1-3 s d'Argon2id sur device contraint passaient sans
                  // feedback audio pour l'utilisateur aveugle.
                  Semantics(
                    liveRegion: true,
                    label: t.setupEncrypting,
                    child: Column(
                      children: [
                        const CircularProgressIndicator(),
                        const SizedBox(height: 12),
                        Text(
                          t.setupEncrypting,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  )
                else
                  FilledButton.icon(
                    onPressed: _create,
                    icon: const Icon(Icons.shield_outlined, size: 18),
                    label: Text(t.setupCreateCta),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(48),
                    ),
                  ),

                const SizedBox(height: 24),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.info_outline,
                        size: 16,
                        color: cs.onSurfaceVariant,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          t.setupWarning,
                          style: TextStyle(
                            fontSize: 12,
                            color: cs.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
