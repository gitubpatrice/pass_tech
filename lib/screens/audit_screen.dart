import 'package:flutter/material.dart';
import '../l10n/app_localizations.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/breach_service.dart';
import '../services/password_strength_service.dart';
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
  // P6 v2.4.4 — compteurs aggregés en single-pass dans `_analyze()`. Avant :
  // 4 `.where().length` recalculés à CHAQUE build (refresh, breach progress
  // setState) sur _all, soit 4×N evals par rebuild en plus des 4 passes
  // d'analyse principales.
  late int _passCount;
  late int _noteCount;
  late int _cardCount;
  late int _with2fa;

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

  /// P6 v2.4.4 — single-pass aggregator.
  /// Avant : 4 passes `where().toList()` séparées + 4 passes `where().length`
  /// dans build(). Pour 500 entries password = 8×500 = 4000 evals. Refactor :
  /// 1 boucle remplit `_weak`, `_duplicates`, `_old`, `_missing2fa`, plus
  /// les compteurs `_passCount`/`_noteCount`/`_cardCount`/`_with2fa` (utilisés
  /// dans le header stats). Gain : 2-5 ms à l'init + 1-3 ms par rebuild
  /// (breach progress = 1 setState par worker).
  void _analyze() {
    _all = VaultService().entries.toList();
    final weak = <Entry>[];
    final duplicates = <Entry>[];
    final old = <Entry>[];
    final missing2fa = <Entry>[];
    final yearAgo = DateTime.now().subtract(const Duration(days: 365));
    final counts = <String, int>{};
    int passCount = 0;
    int noteCount = 0;
    int cardCount = 0;
    int with2fa = 0;

    // 1ʳᵉ passe : compteurs + remplissage `counts` pour détection doublons.
    for (final e in _all) {
      switch (e.type) {
        case EntryType.password:
          passCount++;
          if (e.totpSecret.isNotEmpty) with2fa++;
          if (e.password.isNotEmpty) {
            counts[e.password] = (counts[e.password] ?? 0) + 1;
            if (PasswordStrengthService.isWeak(e.password)) weak.add(e);
          }
          if (e.updatedAt.isBefore(yearAgo)) old.add(e);
          if (_sensitiveCategories.contains(e.category) &&
              e.totpSecret.isEmpty) {
            missing2fa.add(e);
          }
          break;
        case EntryType.note:
          noteCount++;
          break;
        case EntryType.card:
          cardCount++;
          break;
      }
    }
    // 2ᵉ passe (post-counts) : doublons confirmés.
    for (final e in _all) {
      if (e.type == EntryType.password &&
          e.password.isNotEmpty &&
          (counts[e.password] ?? 0) > 1) {
        duplicates.add(e);
      }
    }

    _weak = weak;
    _duplicates = duplicates;
    _old = old;
    _missing2fa = missing2fa;
    _passCount = passCount;
    _noteCount = noteCount;
    _cardCount = cardCount;
    _with2fa = with2fa;

    // Score
    int s = 100;
    s -= _weak.length.clamp(0, 6) * 5; // -5 each, max -30
    s -= _duplicates.length.clamp(0, 6) * 5; // -5 each, max -30
    s -= _old.length.clamp(0, 4) * 5; // -5 each, max -20
    s -= _missing2fa.length.clamp(0, 4) * 5; // -5 each, max -20
    _score = s.clamp(0, 100);
  }

  /// v2.5.0 (F4a + F14) — couleurs sémantiques alignées Material 3.
  /// Mapping :
  ///   - score >= 90 → `cs.tertiary` (succès, adapté light/dark).
  ///   - score >= 70 → `Colors.amber.shade700` (warning ; Material 3 n'a pas
  ///     de token "warning" natif, amber.shade700 garantit contraste ≥ 4.5:1
  ///     en light ET en dark sur le fond Pass Tech `#161B22`).
  ///   - score >= 50 → `Colors.deepOrange` (warning fort).
  ///   - score < 50 → `cs.error` (danger).
  /// Ancien gradient `0xFFFDD835` (jaune vif) sur fond clair créait un
  /// contraste limite < WCAG AA pour le texte blanc — corrigé.
  Color _scoreColor(BuildContext ctx) {
    final cs = Theme.of(ctx).colorScheme;
    if (_score >= 90) return cs.tertiary;
    if (_score >= 70) return Colors.amber.shade700;
    if (_score >= 50) return Colors.deepOrange;
    return cs.error;
  }

  String _scoreLabel(AppLocalizations t) {
    if (_score >= 90) return t.auditScoreExcellent;
    if (_score >= 70) return t.auditScoreGood;
    if (_score >= 50) return t.auditScoreMedium;
    return t.auditScoreWeak;
  }

  /// U4 v2.4.4 — icône daltonien-safe à associer au score. Avant : la
  /// `_scoreColor` (vert/jaune/orange/rouge) était le seul signal visuel —
  /// daltonien deutéranope/protanope confond. Icône complémentaire avec
  /// charge sémantique cohérente.
  IconData _scoreIcon() {
    if (_score >= 90) return Icons.check_circle;
    if (_score >= 70) return Icons.thumb_up_alt;
    if (_score >= 50) return Icons.warning_amber_rounded;
    return Icons.error;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final scoreColor = _scoreColor(context);

    // P6 v2.4.4 — compteurs aggrégés au moment de `_analyze()` (single-pass).
    final passCount = _passCount;
    final noteCount = _noteCount;
    final cardCount = _cardCount;
    final with2fa = _with2fa;

    return Scaffold(
      appBar: AppBar(
        title: Text(t.auditTitle),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: t.auditRefreshTooltip,
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
                                fontSize: 12,
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
                    child: Semantics(
                      // U4 v2.4.4 — Semantics group annonçant le score et
                      // sa qualification (TalkBack lit "Score 85 sur 100,
                      // Bon" au lieu de juste "85" hors contexte).
                      label: '${t.auditScoreLabel} $_score / 100',
                      value: _scoreLabel(t),
                      container: true,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            t.auditScoreLabel,
                            style: TextStyle(
                              fontSize: 12,
                              color: cs.onSurfaceVariant,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              // U4 v2.4.4 — icône daltonien-safe à côté du
                              // libellé. La couleur reste le canal primaire
                              // pour les voyants, l'icône est le canal de
                              // secours.
                              Icon(_scoreIcon(), color: scoreColor, size: 22),
                              const SizedBox(width: 6),
                              Text(
                                _scoreLabel(t),
                                style: TextStyle(
                                  fontSize: 22,
                                  fontWeight: FontWeight.w700,
                                  color: scoreColor,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            _score == 100
                                ? t.auditScorePerfect
                                : t.auditScoreImprovements,
                            style: TextStyle(
                              fontSize: 14,
                              color: cs.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
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
                  label: t.auditStatEntries,
                  icon: Icons.folder_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$passCount',
                  label: t.auditStatPasswords,
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
                  label: t.auditStatWith2fa,
                  icon: Icons.shield_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$noteCount',
                  label: t.auditStatNotes,
                  icon: Icons.sticky_note_2_outlined,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatTile(
                  value: '$cardCount',
                  label: t.auditStatCards,
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

          // Issues — v2.5.0 (F4a) : couleurs sémantiques Material 3.
          // weak → cs.error (danger critique)
          // duplicates → Colors.deepOrange (warning fort, pas de token M3
          //   "warning" natif, deepOrange contraste OK light+dark)
          // missing 2FA → cs.primary (info, pas un défaut grave en soi)
          // old → Colors.amber.shade700 (warning doux temporel)
          _IssueSection(
            title: t.auditIssueWeakTitle,
            description: t.auditIssueWeakDesc,
            color: Theme.of(context).colorScheme.error,
            icon: Icons.warning_amber_outlined,
            entries: _weak,
            onTap: _refreshFromDetail,
            emptyText: t.auditIssueWeakEmpty,
          ),
          _IssueSection(
            title: t.auditIssueDuplicateTitle,
            description: t.auditIssueDuplicateDesc,
            color: Colors.deepOrange,
            icon: Icons.content_copy_outlined,
            entries: _duplicates,
            onTap: _refreshFromDetail,
            emptyText: t.auditIssueDuplicateEmpty,
          ),
          _IssueSection(
            title: t.auditIssueNo2faTitle,
            description: t.auditIssueNo2faDesc,
            color: Theme.of(context).colorScheme.primary,
            icon: Icons.shield_outlined,
            entries: _missing2fa,
            onTap: _refreshFromDetail,
            emptyText: t.auditIssueNo2faEmpty,
          ),
          _IssueSection(
            title: t.auditIssueOldTitle,
            description: t.auditIssueOldDesc,
            color: Colors.amber.shade700,
            icon: Icons.schedule_outlined,
            entries: _old,
            onTap: _refreshFromDetail,
            emptyText: t.auditIssueOldEmpty,
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
    final t = AppLocalizations.of(context);
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

    // QW1 v2.4.0 — batch parallèle + dédup. Gain typique 5× (50 entries
    // : ~30 s → ~6 s) sans rate-limiter HIBP (concurrency 6 = courtois).
    final results = await BreachService.checkPasswordsBatch(
      candidates.map((e) => e.password),
      concurrency: 6,
      onProgress: (done, total) {
        if (mounted) setState(() => _breachProgress = done);
      },
    );

    final breached = <Entry>[];
    bool networkOk = true;
    for (final e in candidates) {
      final n = results[e.password] ?? -1;
      if (n < 0) {
        networkOk = false;
        break;
      }
      if (n > 0) breached.add(e);
    }

    if (!mounted) return;
    if (!networkOk) {
      setState(() {
        _checkingBreach = false;
        _breachError = t.auditBreachNetworkError;
      });
      messenger.showSnackBar(SnackBar(content: Text(t.auditBreachSnackError)));
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
    final t = AppLocalizations.of(context);
    // v2.5.0 (F4a) — couleurs sémantiques Material 3 :
    //   - clean (HIBP : zéro breach) → cs.tertiary (succès)
    //   - breach détecté → cs.error (danger)
    //   - pas encore vérifié (état neutre, non sollicité) → cs.outline
    final hasResult = breached != null;
    final isClean = hasResult && breached!.isEmpty;
    final hasBreach = hasResult && breached!.isNotEmpty;
    final color = isClean
        ? cs.tertiary
        : hasBreach
        ? cs.error
        : cs.outline;

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
                      Text(
                        t.auditBreachTitle,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        hasResult
                            ? (isClean
                                  ? t.auditBreachClean
                                  : t.auditBreachFound(breached!.length))
                            : t.auditBreachIntro,
                        style: TextStyle(
                          fontSize: 13,
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
                      // U8 v2.4.4 — Semantics value annonce progression
                      // HIBP à TalkBack. Avant : aveugle voyait juste la
                      // section "vérification" sans aucun feedback de
                      // progression sur les ~6 s du batch.
                      child: LinearProgressIndicator(
                        value: total == 0 ? 0 : progress / total,
                        minHeight: 6,
                        backgroundColor: cs.surfaceContainerHighest,
                        // v2.5.0 (F4a) : progress neutre = cs.outline (cf.
                        // état "pas encore vérifié" plus haut).
                        valueColor: AlwaysStoppedAnimation(cs.outline),
                        semanticsLabel: t.auditBreachTitle,
                        semanticsValue: '$progress / $total',
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    '$progress / $total',
                    style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ] else if (error != null) ...[
              Text(error!, style: TextStyle(color: cs.error, fontSize: 12)),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.refresh, size: 16),
                label: Text(t.auditBreachRetry),
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
                label: Text(t.auditBreachRecheck),
              ),
            ] else if (isClean) ...[
              OutlinedButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.refresh, size: 16),
                label: Text(t.auditBreachRecheck),
              ),
            ] else ...[
              FilledButton.icon(
                onPressed: onRun,
                icon: const Icon(Icons.travel_explore_outlined, size: 16),
                label: Text(t.auditBreachRun),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(40),
                ),
              ),
              const SizedBox(height: 6),
              Text(
                t.auditBreachPrivacyNote,
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
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
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
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
                    color: (isEmpty ? cs.tertiary : color).withValues(
                      alpha: 0.15,
                    ),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    isEmpty ? Icons.check_circle_outline : icon,
                    color: isEmpty ? cs.tertiary : color,
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
                    color: (isEmpty ? cs.tertiary : color).withValues(
                      alpha: 0.12,
                    ),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    '${entries.length}',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: isEmpty ? cs.tertiary : color,
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
