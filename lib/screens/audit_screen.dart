import 'package:flutter/material.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/breach_service.dart';
import '../services/vault_service.dart';
import 'entry_detail_screen.dart';

class AuditScreen extends StatefulWidget {
  const AuditScreen({super.key});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  static const _sensitiveCategories = {'Banque', 'Email'};

  late List<Entry> _all;
  late List<Entry> _weak;
  late List<Entry> _duplicates;
  late List<Entry> _old;
  late List<Entry> _missing2fa;
  late int _score;

  // Breach check state
  List<Entry>? _breached;
  bool _checkingBreach = false;
  int _breachProgress = 0;
  int _breachTotal = 0;
  String? _breachError;

  @override
  void initState() {
    super.initState();
    _analyze();
  }

  void _analyze() {
    _all = VaultService().entries.toList();

    // Weak passwords (password type only)
    _weak = _all
        .where(
          (e) =>
              e.type == EntryType.password &&
              e.password.isNotEmpty &&
              _isWeak(e.password),
        )
        .toList();

    // Duplicate passwords (same password used in 2+ entries)
    final counts = <String, int>{};
    for (final e in _all) {
      if (e.type == EntryType.password && e.password.isNotEmpty) {
        counts[e.password] = (counts[e.password] ?? 0) + 1;
      }
    }
    _duplicates = _all
        .where(
          (e) =>
              e.type == EntryType.password &&
              e.password.isNotEmpty &&
              (counts[e.password] ?? 0) > 1,
        )
        .toList();

    // Old entries (> 1 year since update)
    final yearAgo = DateTime.now().subtract(const Duration(days: 365));
    _old = _all
        .where(
          (e) => e.type == EntryType.password && e.updatedAt.isBefore(yearAgo),
        )
        .toList();

    // Sensitive categories without TOTP
    _missing2fa = _all
        .where(
          (e) =>
              e.type == EntryType.password &&
              _sensitiveCategories.contains(e.category) &&
              e.totpSecret.isEmpty,
        )
        .toList();

    // Score
    int s = 100;
    s -= _weak.length.clamp(0, 6) * 5; // -5 each, max -30
    s -= _duplicates.length.clamp(0, 6) * 5; // -5 each, max -30
    s -= _old.length.clamp(0, 4) * 5; // -5 each, max -20
    s -= _missing2fa.length.clamp(0, 4) * 5; // -5 each, max -20
    _score = s.clamp(0, 100);
  }

  static bool _isWeak(String pwd) {
    if (pwd.length < 10) return true;
    int variety = 0;
    if (pwd.contains(RegExp(r'[A-Z]'))) variety++;
    if (pwd.contains(RegExp(r'[a-z]'))) variety++;
    if (pwd.contains(RegExp(r'[0-9]'))) variety++;
    if (pwd.contains(RegExp(r'[^A-Za-z0-9]'))) variety++;
    return variety < 3;
  }

  Color _scoreColor(BuildContext ctx) {
    if (_score >= 90) return const Color(0xFF43A047);
    if (_score >= 70) return const Color(0xFFFDD835);
    if (_score >= 50) return const Color(0xFFFF7043);
    return const Color(0xFFE53935);
  }

  String get _scoreLabel {
    if (_score >= 90) return 'Excellent';
    if (_score >= 70) return 'Bon';
    if (_score >= 50) return 'Moyen';
    return 'Faible';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final scoreColor = _scoreColor(context);

    final passCount = _all.where((e) => e.type == EntryType.password).length;
    final noteCount = _all.where((e) => e.type == EntryType.note).length;
    final cardCount = _all.where((e) => e.type == EntryType.card).length;
    final with2fa = _all
        .where((e) => e.type == EntryType.password && e.totpSecret.isNotEmpty)
        .length;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Audit sécurité'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => setState(_analyze),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Score
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: [
                  SizedBox(
                    width: 90,
                    height: 90,
                    child: Stack(
                      alignment: Alignment.center,
                      children: [
                        SizedBox(
                          width: 90,
                          height: 90,
                          child: CircularProgressIndicator(
                            value: _score / 100,
                            strokeWidth: 8,
                            backgroundColor: cs.surfaceContainerHighest,
                            valueColor: AlwaysStoppedAnimation(scoreColor),
                          ),
                        ),
                        Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '$_score',
                              style: TextStyle(
                                fontSize: 24,
                                fontWeight: FontWeight.w700,
                                color: scoreColor,
                              ),
                            ),
                            Text(
                              '/100',
                              style: TextStyle(
                                fontSize: 10,
                                color: cs.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 20),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Score de sécurité',
                          style: TextStyle(
                            fontSize: 12,
                            color: cs.onSurfaceVariant,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _scoreLabel,
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.w700,
                            color: scoreColor,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _score == 100
                              ? 'Aucun problème détecté 🎉'
                              : 'Améliorations recommandées ci-dessous',
                          style: TextStyle(
                            fontSize: 11,
                            color: cs.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Quick stats
          Row(
            children: [
              Expanded(
                child: _StatTile(
                  value: '${_all.length}',
                  label: 'Entrées',
                  icon: Icons.folder_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$passCount',
                  label: 'Mots de passe',
                  icon: Icons.key,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _StatTile(
                  value: '$with2fa',
                  label: 'Avec 2FA',
                  icon: Icons.shield_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$noteCount',
                  label: 'Notes',
                  icon: Icons.sticky_note_2_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$cardCount',
                  label: 'Cartes',
                  icon: Icons.credit_card,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),

          // Breach check (HaveIBeenPwned)
          _BreachCard(
            checking: _checkingBreach,
            progress: _breachProgress,
            total: _breachTotal,
            breached: _breached,
            error: _breachError,
            onRun: _runBreachCheck,
            onTapEntry: (e) async {
              await Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => EntryDetailScreen(
                    entry: e,
                    onChanged: _refreshFromDetail,
                  ),
                ),
              );
              _refreshFromDetail();
            },
          ),
          const SizedBox(height: 10),

          // Issues
          _IssueSection(
            title: 'Mots de passe faibles',
            description: 'Moins de 10 caractères ou peu de variété',
            color: const Color(0xFFE53935),
            icon: Icons.warning_amber_outlined,
            entries: _weak,
            onTap: _refreshFromDetail,
            emptyText: 'Tous vos mots de passe sont robustes',
          ),
          _IssueSection(
            title: 'Doublons',
            description: 'Même mot de passe utilisé sur plusieurs comptes',
            color: const Color(0xFFFF7043),
            icon: Icons.content_copy_outlined,
            entries: _duplicates,
            onTap: _refreshFromDetail,
            emptyText: 'Aucun mot de passe réutilisé',
          ),
          _IssueSection(
            title: 'Sans 2FA sur compte sensible',
            description: 'Banque ou Email sans code TOTP configuré',
            color: const Color(0xFF1976D2),
            icon: Icons.shield_outlined,
            entries: _missing2fa,
            onTap: _refreshFromDetail,
            emptyText: 'Tous vos comptes sensibles ont un 2FA',
          ),
          _IssueSection(
            title: 'Anciens (> 1 an)',
            description: 'Pensez à les renouveler',
            color: const Color(0xFFFDD835),
            icon: Icons.schedule_outlined,
            entries: _old,
            onTap: _refreshFromDetail,
            emptyText: 'Aucun mot de passe ancien',
          ),
        ],
      ),
    );
  }

  void _refreshFromDetail() {
    setState(_analyze);
  }

  Future<void> _runBreachCheck() async {
    final messenger = ScaffoldMessenger.of(context);
    final candidates = _all
        .where((e) => e.type == EntryType.password && e.password.isNotEmpty)
        .toList();

    setState(() {
      _checkingBreach = true;
      _breachProgress = 0;
      _breachTotal = candidates.length;
      _breachError = null;
      _breached = null;
    });

    final breached = <Entry>[];
    bool networkOk = true;

    for (final e in candidates) {
      if (!mounted) return;
      final n = await BreachService.checkPassword(e.password);
      if (n < 0) {
        networkOk = false;
        break;
      }
      if (n > 0) breached.add(e);
      if (mounted) setState(() => _breachProgress++);
    }

    if (!mounted) return;
    if (!networkOk) {
      setState(() {
        _checkingBreach = false;
        _breachError = 'Erreur réseau — vérification annulée';
      });
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Vérification impossible : pas de connexion ?'),
        ),
      );
      return;
    }
    setState(() {
      _checkingBreach = false;
      _breached = breached;
    });
  }
}

class _BreachCard extends StatelessWidget {
  final bool checking;
  final int progress;
  final int total;
  final List<Entry>? breached;
  final String? error;
  final VoidCallback onRun;
  final void Function(Entry) onTapEntry;
  const _BreachCard({
    required this.checking,
    required this.progress,
    required this.total,
    required this.breached,
    required this.error,
    required this.onRun,
    required this.onTapEntry,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final purple = const Color(0xFF7B1FA2);
    final hasResult = breached != null;
    final isClean = hasResult && breached!.isEmpty;
    final hasBreach = hasResult && breached!.isNotEmpty;
    final color = isClean
        ? const Color(0xFF43A047)
        : hasBreach
        ? const Color(0xFFE53935)
        : purple;

    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    isClean
                        ? Icons.verified_outlined
                        : hasBreach
                        ? Icons.dangerous_outlined
                        : Icons.travel_explore_outlined,
                    color: color,
                    size: 18,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Vérification de fuites de données',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        hasResult
                            ? (isClean
                                  ? 'Aucun mot de passe compromis détecté'
                                  : '${breached!.length} mot${breached!.length > 1 ? 's' : ''} de passe compromis')
                            : 'Compare vos mots de passe à 800M+ fuites publiques',
                        style: TextStyle(
                          fontSize: 11,
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (checking) ...[
              Row(
                children: [
                  Expanded(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: total == 0 ? 0 : progress / total,
                        minHeight: 6,
                        backgroundColor: cs.surfaceContainerHighest,
                        valueColor: AlwaysStoppedAnimation(purple),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    '$progress / $total',
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ] else if (error != null) ...[
              Text(error!, style: TextStyle(color: cs.error, fontSize: 12)),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Réessayer'),
              ),
            ] else if (hasBreach) ...[
              ...breached!.map(
                (e) => InkWell(
                  borderRadius: BorderRadius.circular(8),
                  onTap: () => onTapEntry(e),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      vertical: 6,
                      horizontal: 4,
                    ),
                    child: Row(
                      children: [
                        Icon(
                          categoryIcon(e.category),
                          size: 14,
                          color: categoryColor(e.category),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            e.title,
                            style: const TextStyle(fontSize: 13),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        Icon(
                          Icons.chevron_right,
                          size: 16,
                          color: cs.onSurfaceVariant,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 4),
              OutlinedButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Vérifier à nouveau'),
              ),
            ] else if (isClean) ...[
              OutlinedButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Vérifier à nouveau'),
              ),
            ] else ...[
              FilledButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.travel_explore_outlined, size: 16),
                label: const Text('Lancer la vérification'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(40),
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Confidentialité préservée : seuls 5 caractères du hash SHA-1 quittent l\'appareil (k-anonymity).',
                style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StatTile extends StatelessWidget {
  final String value;
  final String label;
  final IconData icon;
  const _StatTile({
    required this.value,
    required this.label,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
        child: Column(
          children: [
            Icon(icon, size: 18, color: cs.primary),
            const SizedBox(height: 4),
            Text(
              value,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            Text(
              label,
              style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

class _IssueSection extends StatelessWidget {
  final String title;
  final String description;
  final Color color;
  final IconData icon;
  final List<Entry> entries;
  final VoidCallback onTap;
  final String emptyText;
  const _IssueSection({
    required this.title,
    required this.description,
    required this.color,
    required this.icon,
    required this.entries,
    required this.onTap,
    required this.emptyText,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isEmpty = entries.isEmpty;

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: (isEmpty ? const Color(0xFF43A047) : color)
                        .withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    isEmpty ? Icons.check_circle_outline : icon,
                    color: isEmpty ? const Color(0xFF43A047) : color,
                    size: 18,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        description,
                        style: TextStyle(
                          fontSize: 11,
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: (isEmpty ? const Color(0xFF43A047) : color)
                        .withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    '${entries.length}',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: isEmpty ? const Color(0xFF43A047) : color,
                    ),
                  ),
                ),
              ],
            ),
            if (isEmpty) ...[
              const SizedBox(height: 10),
              Text(
                emptyText,
                style: TextStyle(
                  fontSize: 11,
                  color: cs.onSurfaceVariant,
                  fontStyle: FontStyle.italic,
                ),
              ),
            ] else ...[
              const SizedBox(height: 10),
              ...entries.map(
                (e) => InkWell(
                  borderRadius: BorderRadius.circular(8),
                  onTap: () async {
                    await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) =>
                            EntryDetailScreen(entry: e, onChanged: onTap),
                      ),
                    );
                    onTap();
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      vertical: 6,
                      horizontal: 4,
                    ),
                    child: Row(
                      children: [
                        Icon(
                          categoryIcon(e.category),
                          size: 14,
                          color: categoryColor(e.category),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            e.title,
                            style: const TextStyle(fontSize: 13),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (e.username.isNotEmpty)
                          Text(
                            e.username,
                            style: TextStyle(
                              fontSize: 11,
                              color: cs.onSurfaceVariant,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
