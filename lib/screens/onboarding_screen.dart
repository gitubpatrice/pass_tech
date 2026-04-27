import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'setup_screen.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _ctrl = PageController();
  int _page = 0;

  static final _pages = [
    _OnboardPage(
      icon: Icons.lock,
      iconColor: Color(0xFF58A6FF),
      title: 'Bienvenue dans Pass Tech',
      subtitle: 'Votre coffre-fort de mots de passe 100 % local',
      points: [
        'Aucun serveur, aucun cloud, aucun tracker',
        'Chiffrement AES-256 + PBKDF2 250 000 itérations',
        'Audit de sécurité et détection de fuites intégrés',
      ],
    ),
    _OnboardPage(
      icon: Icons.key,
      iconColor: Color(0xFFFF7043),
      title: 'Mot de passe maître',
      subtitle: 'Une seule clé protège tout votre coffre',
      points: [
        'Choisissez un mot de passe long et unique (14+ caractères)',
        'Il ne peut PAS être récupéré : si vous l\'oubliez, tout est perdu',
        'L\'empreinte / Face ID peut le remplacer après le 1er déverrouillage',
      ],
    ),
    _OnboardPage(
      icon: Icons.backup_outlined,
      iconColor: Color(0xFF43A047),
      title: 'Pensez à sauvegarder',
      subtitle: 'Le coffre vit uniquement sur ce téléphone',
      points: [
        'Si vous perdez le téléphone, le coffre est perdu',
        'Exportez régulièrement un backup chiffré (.ptbak)',
        'Conservez-le dans un endroit sûr (autre appareil, clé USB…)',
      ],
    ),
  ];

  Future<void> _finish() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('onboarding_completed', true);
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const SetupScreen()),
    );
  }

  void _next() {
    if (_page < _pages.length - 1) {
      _ctrl.nextPage(
          duration: const Duration(milliseconds: 280), curve: Curves.easeOut);
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
    final isLast = _page == _pages.length - 1;

    return Scaffold(
      body: SafeArea(
        child: Column(children: [
          // Skip
          Align(
            alignment: Alignment.topRight,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(0, 12, 12, 0),
              child: TextButton(
                onPressed: _finish,
                child: const Text('Passer'),
              ),
            ),
          ),
          Expanded(
            child: PageView.builder(
              controller: _ctrl,
              itemCount: _pages.length,
              onPageChanged: (i) => setState(() => _page = i),
              itemBuilder: (_, i) => _pages[i].build(context),
            ),
          ),
          // Dots
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(_pages.length, (i) {
              final active = i == _page;
              return AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                margin: const EdgeInsets.symmetric(horizontal: 4),
                width: active ? 22 : 8,
                height: 8,
                decoration: BoxDecoration(
                  color: active ? cs.primary : cs.onSurfaceVariant.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(4),
                ),
              );
            }),
          ),
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.fromLTRB(28, 0, 28, 24),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _next,
                icon: Icon(isLast ? Icons.check : Icons.arrow_forward, size: 18),
                label: Text(isLast ? 'Commencer' : 'Suivant'),
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48)),
              ),
            ),
          ),
        ]),
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
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 16, 28, 16),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 120, height: 120,
            decoration: BoxDecoration(
              color: iconColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(28),
            ),
            child: Icon(icon, size: 64, color: iconColor),
          ),
          const SizedBox(height: 32),
          Text(title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          Text(subtitle,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(color: cs.onSurfaceVariant)),
          const SizedBox(height: 28),
          ...points.map((p) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(Icons.check_circle_outline, size: 18, color: iconColor),
              const SizedBox(width: 10),
              Expanded(
                child: Text(p,
                    style: const TextStyle(fontSize: 13, height: 1.4)),
              ),
            ]),
          )),
        ],
      ),
    );
  }
}
