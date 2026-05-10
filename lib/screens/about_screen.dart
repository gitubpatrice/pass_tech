import 'package:flutter/material.dart';
import '../l10n/app_localizations.dart';
import '../services/app_update.dart';
import 'package:files_tech_core/files_tech_core.dart';

class AboutScreen extends StatefulWidget {
  const AboutScreen({super.key});

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  static const _version = '2.3.8';
  static const _author = 'Patrice Haltaya';

  bool _checkingUpdate = false;

  List<({IconData icon, String label, String desc})> _featuresFor(
    AppLocalizations t,
  ) => [
    (
      icon: Icons.shield_outlined,
      label: t.aboutFeatureBackupLabel,
      desc: t.aboutFeatureBackupDesc,
    ),
    (
      icon: Icons.download_outlined,
      label: t.aboutFeatureImportLabel,
      desc: t.aboutFeatureImportDesc,
    ),
    (
      icon: Icons.gpp_good_outlined,
      label: t.aboutFeatureAuditLabel,
      desc: t.aboutFeatureAuditDesc,
    ),
    (
      icon: Icons.travel_explore_outlined,
      label: t.aboutFeatureBreachLabel,
      desc: t.aboutFeatureBreachDesc,
    ),
    (
      icon: Icons.qr_code_scanner,
      label: t.aboutFeatureQrLabel,
      desc: t.aboutFeatureQrDesc,
    ),
    (
      icon: Icons.brightness_6_outlined,
      label: t.aboutFeatureThemeLabel,
      desc: t.aboutFeatureThemeDesc,
    ),
    (
      icon: Icons.sort,
      label: t.aboutFeatureSortLabel,
      desc: t.aboutFeatureSortDesc,
    ),
    (
      icon: Icons.key,
      label: t.aboutFeatureTypesLabel,
      desc: t.aboutFeatureTypesDesc,
    ),
    (
      icon: Icons.shield_outlined,
      label: t.aboutFeatureTotpLabel,
      desc: t.aboutFeatureTotpDesc,
    ),
    (
      icon: Icons.credit_card,
      label: t.aboutFeatureCardsLabel,
      desc: t.aboutFeatureCardsDesc,
    ),
    (
      icon: Icons.sticky_note_2_outlined,
      label: t.aboutFeatureNotesLabel,
      desc: t.aboutFeatureNotesDesc,
    ),
    (
      icon: Icons.lock_outline,
      label: t.aboutFeatureVaultLabel,
      desc: t.aboutFeatureVaultDesc,
    ),
    (
      icon: Icons.fingerprint,
      label: t.aboutFeatureBiometricLabel,
      desc: t.aboutFeatureBiometricDesc,
    ),
    (
      icon: Icons.gpp_good_outlined,
      label: t.aboutFeatureBruteforceLabel,
      desc: t.aboutFeatureBruteforceDesc,
    ),
    (
      icon: Icons.verified_user_outlined,
      label: t.aboutFeatureEnvLabel,
      desc: t.aboutFeatureEnvDesc,
    ),
    (
      icon: Icons.no_photography_outlined,
      label: t.aboutFeatureScreenshotLabel,
      desc: t.aboutFeatureScreenshotDesc,
    ),
    (
      icon: Icons.cloud_off_outlined,
      label: t.aboutFeatureBackupOffLabel,
      desc: t.aboutFeatureBackupOffDesc,
    ),
    (
      icon: Icons.timer_outlined,
      label: t.aboutFeatureAutoLockLabel,
      desc: t.aboutFeatureAutoLockDesc,
    ),
    (
      icon: Icons.password,
      label: t.aboutFeatureGeneratorLabel,
      desc: t.aboutFeatureGeneratorDesc,
    ),
    (
      icon: Icons.shield_moon_outlined,
      label: t.aboutFeatureDecoyLabel,
      desc: t.aboutFeatureDecoyDesc,
    ),
    (
      icon: Icons.warning_amber_rounded,
      label: t.aboutFeaturePanicLabel,
      desc: t.aboutFeaturePanicDesc,
    ),
    (
      icon: Icons.family_restroom,
      label: t.aboutFeatureHeritageLabel,
      desc: t.aboutFeatureHeritageDesc,
    ),
    (
      icon: Icons.verified_user_outlined,
      label: t.aboutFeaturePhishingLabel,
      desc: t.aboutFeaturePhishingDesc,
    ),
    (
      icon: Icons.content_paste_off_outlined,
      label: t.aboutFeatureClipboardLabel,
      desc: t.aboutFeatureClipboardDesc,
    ),
    (
      icon: Icons.search,
      label: t.aboutFeatureSearchLabel,
      desc: t.aboutFeatureSearchDesc,
    ),
  ];

  Future<void> _checkUpdate() async {
    final t = AppLocalizations.of(context);
    setState(() => _checkingUpdate = true);
    final info = await appUpdateService.checkForUpdate(force: true);
    if (!mounted) return;
    setState(() => _checkingUpdate = false);
    if (info == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(t.aboutAlreadyLatest)));
      return;
    }
    showDialog(
      context: context,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          title: Text(t.aboutVersionAvailable(info.version)),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  info.body.isNotEmpty ? info.body : t.aboutNewVersionAvailable,
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
                            Text(
                              t.aboutShaExpected,
                              style: const TextStyle(
                                fontSize: 14,
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
                            fontSize: 12,
                            letterSpacing: 0.3,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          t.aboutShaHint,
                          style: TextStyle(
                            fontSize: 12,
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
              child: Text(t.actionLater),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text(t.actionOk),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final features = _featuresFor(t);
    return Scaffold(
      appBar: AppBar(title: Text(t.aboutTitle)),
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
                  t.appTitle,
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
                  t.aboutTagline,
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
                        ? t.aboutCheckingUpdates
                        : t.aboutCheckUpdates,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 28),

          // ── Confidentialité ─────────────────────────────────────────────────
          _sectionTitle(context, t.aboutSectionPrivacy),
          const SizedBox(height: 8),
          const _PrivacyCard(),

          const SizedBox(height: 24),

          // ── Fonctionnalités ─────────────────────────────────────────────────
          _sectionTitle(context, t.aboutSectionFeatures),
          const SizedBox(height: 8),
          ...features.map(
            (f) => _FeatureRow(icon: f.icon, label: f.label, desc: f.desc),
          ),

          const SizedBox(height: 24),

          // ── Auteur ──────────────────────────────────────────────────────────
          _sectionTitle(context, t.aboutSectionAuthor),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Icon(Icons.person_outline, color: cs.primary),
              ),
              title: const Text(_author),
              subtitle: Text(t.aboutAuthorRole),
            ),
          ),

          const SizedBox(height: 16),

          // ── Aide ────────────────────────────────────────────────────────────
          _sectionTitle(context, t.aboutSectionHelp),
          const SizedBox(height: 8),
          _HelpCard(
            title: t.aboutHelpFirstLaunchTitle,
            steps: [t.aboutHelpFirstLaunchStep1, t.aboutHelpFirstLaunchStep2],
          ),
          _HelpCard(
            title: t.aboutHelpAddTitle,
            steps: [t.aboutHelpAddStep1, t.aboutHelpAddStep2],
          ),
          _HelpCard(
            title: t.aboutHelpUpdateTitle,
            steps: [t.aboutHelpUpdateStep1, t.aboutHelpUpdateStep2],
          ),

          const SizedBox(height: 24),

          // ── Sections partagées Files Tech (support + légal) ─────────────────
          LegalSupportSections(
            appName: 'Pass Tech',
            version: _version,
            privacyAsset: Localizations.localeOf(context).languageCode == 'en'
                ? 'assets/legal/PRIVACY.en.md'
                : 'assets/legal/PRIVACY.fr.md',
            termsAsset: Localizations.localeOf(context).languageCode == 'en'
                ? 'assets/legal/TERMS.en.md'
                : 'assets/legal/TERMS.fr.md',
            helpSectionTitle: t.legalHelpSection,
            legalSectionTitle: t.legalLegalSection,
            contactSupportTitle: t.legalContactSupport,
            officialWebsiteTitle: t.legalOfficialWebsite,
            reportBugTitle: t.legalReportBug,
            reportBugSubtitle: t.legalReportBugSubtitle,
            bugBodyIntro: t.legalBugBodyIntro,
            bugBodyVersionLabel: t.legalBugBodyVersion,
            bugBodyDeviceLabel: t.legalBugBodyDevice,
            privacyTitle: t.legalPrivacy,
            termsTitle: t.legalTerms,
            licenseTitle: t.legalLicense,
          ),
        ],
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.only(left: 2),
    child: Text(
      title,
      style: Theme.of(context).textTheme.titleSmall?.copyWith(
        color: Theme.of(context).colorScheme.onSurfaceVariant,
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
  Widget build(BuildContext context) => Semantics(
    label: '$label. $desc',
    container: true,
    child: ExcludeSemantics(
      child: Card(
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
          subtitle: Text(desc, style: const TextStyle(fontSize: 12)),
        ),
      ),
    ),
  );
}

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final items = <({IconData icon, Color color, String label})>[
      (
        icon: Icons.block,
        color: const Color(0xFFE53935),
        label: t.aboutPrivacyNoAds,
      ),
      (
        icon: Icons.analytics_outlined,
        color: const Color(0xFFFF7043),
        label: t.aboutPrivacyNoTracker,
      ),
      (
        icon: Icons.wifi_off,
        color: const Color(0xFF43A047),
        label: t.aboutPrivacyOffline,
      ),
      (
        icon: Icons.visibility_off,
        color: const Color(0xFF1976D2),
        label: t.aboutPrivacyNoCollect,
      ),
      (
        icon: Icons.share_outlined,
        color: const Color(0xFF7B1FA2),
        label: t.aboutPrivacyNoShare,
      ),
      (
        icon: Icons.store_mall_directory_outlined,
        color: const Color(0xFF00897B),
        label: t.aboutPrivacyNoStore,
      ),
    ];
    return Card(
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
                  t.aboutPrivacyHeader,
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: Theme.of(context).colorScheme.onSurface,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: items
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
            fontSize: 12,
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
