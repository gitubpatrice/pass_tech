import 'package:flutter/material.dart';
import '../services/app_update.dart';
import 'package:files_tech_core/files_tech_core.dart';

class AboutScreen extends StatefulWidget {
  const AboutScreen({super.key});

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  static const _version = '1.12.2';
  static const _author = 'Patrice Haltaya';

  bool _checkingUpdate = false;

  static const _features = [
    (
      icon: Icons.shield_outlined,
      label: 'Sauvegarde chiffrée (.ptbak)',
      desc: 'Export chiffré AES-256 + restaurable avec passphrase',
    ),
    (
      icon: Icons.download_outlined,
      label: 'Import multi-format',
      desc: 'Chrome, Edge, Bitwarden JSON, CSV générique',
    ),
    (
      icon: Icons.gpp_good_outlined,
      label: 'Audit de sécurité',
      desc: 'Score, mots de passe faibles, doublons, anciens, sans 2FA',
    ),
    (
      icon: Icons.travel_explore_outlined,
      label: 'Détection de fuites',
      desc: 'Vérifie 800M+ fuites publiques (HaveIBeenPwned, k-anonymity)',
    ),
    (
      icon: Icons.qr_code_scanner,
      label: 'Scanner QR pour 2FA',
      desc: 'Scanne le QR code TOTP fourni par tout site',
    ),
    (
      icon: Icons.brightness_6_outlined,
      label: 'Thème clair / sombre',
      desc: 'Au choix ou en suivant le système',
    ),
    (
      icon: Icons.sort,
      label: 'Tri personnalisé',
      desc: 'Récent, ancien, A → Z, Z → A',
    ),
    (
      icon: Icons.key,
      label: '3 types d\'entrées',
      desc: 'Mots de passe, notes sécurisées, cartes bancaires',
    ),
    (
      icon: Icons.shield_outlined,
      label: 'Codes 2FA intégrés (TOTP)',
      desc: 'Génère les codes à 6 chiffres comme Google Authenticator',
    ),
    (
      icon: Icons.credit_card,
      label: 'Cartes bancaires',
      desc: 'Numéro, CVV, expiration, PIN — affichage carte 3D',
    ),
    (
      icon: Icons.sticky_note_2_outlined,
      label: 'Notes sécurisées',
      desc: 'Texte confidentiel chiffré (RIB, codes, recovery keys)',
    ),
    (
      icon: Icons.lock_outline,
      label: 'Coffre-fort chiffré',
      desc: 'AES-256-CBC + HMAC-SHA256, PBKDF2 600 000 itérations (OWASP)',
    ),
    (
      icon: Icons.fingerprint,
      label: 'Biométrie hardware-bound',
      desc:
          'Clé liée à Android Keystore — la biométrie est obligatoire pour lire',
    ),
    (
      icon: Icons.gpp_good_outlined,
      label: 'Anti-brute force',
      desc: 'Verrouillage progressif après 5 tentatives (30s → 30min)',
    ),
    (
      icon: Icons.verified_user_outlined,
      label: 'Détection d\'environnement',
      desc: 'Avertit en cas de root, émulateur ou debugger détecté',
    ),
    (
      icon: Icons.no_photography_outlined,
      label: 'Captures bloquées',
      desc: 'Aucune capture d\'écran ni aperçu dans le sélecteur récent',
    ),
    (
      icon: Icons.cloud_off_outlined,
      label: 'Backup désactivé',
      desc: 'Le coffre-fort n\'est jamais sauvegardé dans le cloud Android',
    ),
    (
      icon: Icons.timer_outlined,
      label: 'Auto-lock configurable',
      desc: 'Immédiat, 1 / 5 / 15 / 30 minutes ou jamais',
    ),
    (
      icon: Icons.password,
      label: 'Générateur',
      desc: 'Caractères 8–64 OU phrases de passe FR (Diceware) mémorables',
    ),
    (
      icon: Icons.shield_moon_outlined,
      label: 'Coffre leurre',
      desc: 'Un 2e mot de passe ouvre un faux coffre — déni plausible',
    ),
    (
      icon: Icons.warning_amber_rounded,
      label: 'Mode panique',
      desc: 'Lock + clipboard wipe + camouflage icône en « Calculatrice »',
    ),
    (
      icon: Icons.family_restroom,
      label: 'Héritage',
      desc:
          'Un proche accède au coffre après inactivité prolongée — local, sans cloud',
    ),
    (
      icon: Icons.verified_user_outlined,
      label: 'Anti-phishing',
      desc:
          'Vérifie le domaine du navigateur avant de copier — alerte typosquatting',
    ),
    (
      icon: Icons.content_paste_off_outlined,
      label: 'Presse-papiers sécurisé',
      desc: 'Effacement automatique configurable (15s–60s)',
    ),
    (
      icon: Icons.search,
      label: 'Recherche',
      desc: 'Par titre, identifiant, URL ou contenu',
    ),
  ];

  Future<void> _checkUpdate() async {
    setState(() => _checkingUpdate = true);
    final info = await appUpdateService.checkForUpdate(force: true);
    if (!mounted) return;
    setState(() => _checkingUpdate = false);
    if (info == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vous avez déjà la dernière version ✓')),
      );
      return;
    }
    showDialog(
      context: context,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          title: Text('v${info.version} disponible'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  info.body.isNotEmpty
                      ? info.body
                      : 'Une nouvelle version est disponible.',
                ),
                if (info.expectedSha256 != null) ...[
                  const SizedBox(height: 14),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: cs.outline),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              Icons.verified_outlined,
                              size: 14,
                              color: cs.primary,
                            ),
                            const SizedBox(width: 6),
                            const Text(
                              'SHA-256 attendu (APK arm64-v8a)',
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        SelectableText(
                          info.expectedSha256!,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 10,
                            letterSpacing: 0.3,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Avant install, vérifiez ce hash : '
                          'sha256sum app-arm64-v8a-release.apk',
                          style: TextStyle(
                            fontSize: 10,
                            color: cs.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Plus tard'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('OK'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('À propos')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 40),
        children: [
          // ── Header ──────────────────────────────────────────────────────────
          Center(
            child: Column(
              children: [
                Container(
                  width: 80,
                  height: 80,
                  decoration: BoxDecoration(
                    color: cs.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Icon(Icons.lock, size: 44, color: cs.primary),
                ),
                const SizedBox(height: 14),
                Text(
                  'Pass Tech',
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: cs.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    'v$_version',
                    style: TextStyle(
                      color: cs.primary,
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Gestionnaire de mots de passe 100 % local',
                  style: Theme.of(context).textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: _checkingUpdate ? null : _checkUpdate,
                  icon: _checkingUpdate
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.system_update_outlined, size: 18),
                  label: Text(
                    _checkingUpdate
                        ? 'Vérification…'
                        : 'Vérifier les mises à jour',
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 28),

          // ── Confidentialité ─────────────────────────────────────────────────
          _sectionTitle(context, 'Confidentialité'),
          const SizedBox(height: 8),
          const _PrivacyCard(),

          const SizedBox(height: 24),

          // ── Fonctionnalités ─────────────────────────────────────────────────
          _sectionTitle(context, 'Fonctionnalités'),
          const SizedBox(height: 8),
          ..._features.map(
            (f) => _FeatureRow(icon: f.icon, label: f.label, desc: f.desc),
          ),

          const SizedBox(height: 24),

          // ── Auteur ──────────────────────────────────────────────────────────
          _sectionTitle(context, 'Auteur'),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Icon(Icons.person_outline, color: cs.primary),
              ),
              title: const Text(_author),
              subtitle: const Text('Développeur'),
            ),
          ),

          const SizedBox(height: 16),

          // ── Aide ────────────────────────────────────────────────────────────
          _sectionTitle(context, 'Aide rapide'),
          const SizedBox(height: 8),
          _HelpCard(
            title: 'Premier lancement',
            steps: [
              'Choisissez un mot de passe maître fort (min. 8 caractères)',
              'Il chiffre toutes vos données — ne peut pas être récupéré',
            ],
          ),
          _HelpCard(
            title: 'Ajouter un mot de passe',
            steps: [
              'Bouton "+ Ajouter" en bas de l\'écran principal',
              'Ou utilisez le générateur pour créer un mot de passe sécurisé',
            ],
          ),
          _HelpCard(
            title: 'Mise à jour',
            steps: [
              'L\'app vérifie automatiquement les mises à jour au lancement',
              'Ou appuyez sur "Vérifier les mises à jour" ci-dessus',
            ],
          ),

          const SizedBox(height: 24),

          // ── Sections partagées Files Tech (support + légal) ─────────────────
          const LegalSupportSections(appName: 'Pass Tech', version: _version),
        ],
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.only(left: 2),
    child: Text(
      title,
      style: Theme.of(context).textTheme.titleSmall?.copyWith(
        color: Colors.grey.shade600,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.5,
      ),
    ),
  );
}

class _FeatureRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String desc;
  const _FeatureRow({
    required this.icon,
    required this.label,
    required this.desc,
  });

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 6),
    child: ListTile(
      dense: true,
      leading: Icon(
        icon,
        size: 20,
        color: Theme.of(context).colorScheme.primary,
      ),
      title: Text(
        label,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
      ),
      subtitle: Text(desc, style: const TextStyle(fontSize: 11)),
    ),
  );
}

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard();

  static const _items = [
    (icon: Icons.block, color: Color(0xFFE53935), label: 'Aucune publicité'),
    (
      icon: Icons.analytics_outlined,
      color: Color(0xFFFF7043),
      label: 'Aucun tracker',
    ),
    (
      icon: Icons.wifi_off,
      color: Color(0xFF43A047),
      label: 'Fonctionne hors ligne',
    ),
    (
      icon: Icons.visibility_off,
      color: Color(0xFF1976D2),
      label: 'Aucune collecte de données',
    ),
    (
      icon: Icons.share_outlined,
      color: Color(0xFF7B1FA2),
      label: 'Aucun partage de données',
    ),
    (
      icon: Icons.store_mall_directory_outlined,
      color: Color(0xFF00897B),
      label: 'Sans Play Store',
    ),
  ];

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.shield_outlined,
                color: Color(0xFF43A047),
                size: 18,
              ),
              const SizedBox(width: 6),
              Text(
                '100 % privé — zéro surveillance',
                style: TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 13,
                  color: Colors.grey.shade300,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _items
                .map(
                  (item) => _Badge(
                    icon: item.icon,
                    label: item.label,
                    color: item.color,
                  ),
                )
                .toList(),
          ),
        ],
      ),
    ),
  );
}

class _Badge extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  const _Badge({required this.icon, required this.label, required this.color});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: color.withValues(alpha: 0.3)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: color),
        const SizedBox(width: 5),
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            color: color,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    ),
  );
}

class _HelpCard extends StatelessWidget {
  final String title;
  final List<String> steps;
  const _HelpCard({required this.title, required this.steps});

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 8),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
          ),
          const SizedBox(height: 6),
          ...steps.asMap().entries.map(
            (e) => Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${e.key + 1}. ',
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Expanded(
                    child: Text(e.value, style: const TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
  );
}
