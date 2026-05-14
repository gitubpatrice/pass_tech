import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../l10n/app_localizations.dart';
import 'setup_screen.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _ctrl = PageController();
  int _page = 0;

  List<_OnboardPage> _buildPages(AppLocalizations t) => [
    _OnboardPage(
      icon: Icons.lock,
      iconColor: const Color(0xFF58A6FF),
      title: t.onboardWelcomeTitle,
      subtitle: t.onboardWelcomeSubtitle,
      points: [
        t.onboardWelcomePoint1,
        t.onboardWelcomePoint2,
        t.onboardWelcomePoint3,
      ],
    ),
    _OnboardPage(
      icon: Icons.key,
      iconColor: const Color(0xFFFF7043),
      title: t.onboardMasterTitle,
      subtitle: t.onboardMasterSubtitle,
      points: [
        t.onboardMasterPoint1,
        t.onboardMasterPoint2,
        t.onboardMasterPoint3,
      ],
    ),
    _OnboardPage(
      icon: Icons.backup_outlined,
      iconColor: const Color(0xFF43A047),
      title: t.onboardBackupTitle,
      subtitle: t.onboardBackupSubtitle,
      points: [
        t.onboardBackupPoint1,
        t.onboardBackupPoint2,
        t.onboardBackupPoint3,
      ],
    ),
  ];

  Future<void> _finish() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('onboarding_completed', true);
    if (!mounted) return;
    Navigator.of(
      context,
    ).pushReplacement(MaterialPageRoute(builder: (_) => const SetupScreen()));
  }

  void _next(int total) {
    if (_page < total - 1) {
      _ctrl.nextPage(
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOut,
      );
    } else {
      _finish();
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final pages = _buildPages(t);
    final isLast = _page == pages.length - 1;

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // Skip
            Align(
              alignment: Alignment.topRight,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(0, 12, 12, 0),
                child: TextButton(
                  onPressed: _finish,
                  child: Text(t.actionSkip),
                ),
              ),
            ),
            Expanded(
              child: PageView.builder(
                controller: _ctrl,
                itemCount: pages.length,
                onPageChanged: (i) => setState(() => _page = i),
                itemBuilder: (_, i) => pages[i].build(context),
              ),
            ),
            // Dots
            // U10 v2.4.4 — Semantics group "Page X / Y" pour TalkBack.
            // Avant : les dots étaient purement visuels (3 AnimatedContainer
            // sans label), le swipe entre pages ne s'annonçait pas pour les
            // utilisateurs aveugles. Format universel `X / Y` (pas besoin
            // d'ajouter une clé ARB).
            Semantics(
              label: '${_page + 1} / ${pages.length}',
              container: true,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(pages.length, (i) {
                  final active = i == _page;
                  return ExcludeSemantics(
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      margin: const EdgeInsets.symmetric(horizontal: 4),
                      width: active ? 22 : 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: active
                            ? cs.primary
                            : cs.onSurfaceVariant.withValues(alpha: 0.3),
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                  );
                }),
              ),
            ),
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.fromLTRB(28, 0, 28, 24),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () => _next(pages.length),
                  icon: Icon(
                    isLast ? Icons.check : Icons.arrow_forward,
                    size: 18,
                  ),
                  label: Text(isLast ? t.actionStart : t.actionNext),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OnboardPage {
  final IconData icon;
  final Color iconColor;
  final String title;
  final String subtitle;
  final List<String> points;
  const _OnboardPage({
    required this.icon,
    required this.iconColor,
    required this.title,
    required this.subtitle,
    required this.points,
  });

  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(28, 16, 28, 16),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 120,
            height: 120,
            decoration: BoxDecoration(
              color: iconColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(28),
            ),
            child: Icon(icon, size: 64, color: iconColor),
          ),
          const SizedBox(height: 32),
          Text(
            title,
            textAlign: TextAlign.center,
            style: Theme.of(
              context,
            ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
          ),
          const SizedBox(height: 28),
          ...points.map(
            (p) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.check_circle_outline, size: 18, color: iconColor),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      p,
                      style: const TextStyle(fontSize: 13, height: 1.4),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
