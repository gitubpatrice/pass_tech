import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show SystemNavigator;
import '../l10n/app_localizations.dart';
import '../models/entry.dart';
import '../services/clipboard_service.dart';

/// Vue lecture seule du coffre destinée à l'héritier après que le dead man's
/// switch s'est déclenché. Affiche les entries du snapshot, permet de copier
/// le username/password de chacune dans le presse-papier (auto-clear actif).
///
/// **Pas de modification possible** : c'est un instantané du coffre tel qu'il
/// était au dernier `setupOrUpdateSnapshot` du propriétaire. Le vrai vault
/// n'est PAS déverrouillé — l'héritier voit uniquement ce snapshot.
class HeirViewScreen extends StatelessWidget {
  final List<Entry> entries;
  const HeirViewScreen({super.key, required this.entries});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        // Empêcher le retour arrière — pour quitter, l'héritier doit
        // explicitement fermer l'app via le bouton "Fermer".
      },
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          title: Text(t.heirViewTitle),
          backgroundColor: cs.errorContainer,
          foregroundColor: cs.onErrorContainer,
          actions: [
            IconButton(
              icon: const Icon(Icons.close),
              tooltip: t.heirViewClose,
              onPressed: () => SystemNavigator.pop(),
            ),
          ],
        ),
        body: Column(
          children: [
            Container(
              width: double.infinity,
              color: cs.errorContainer.withValues(alpha: 0.40),
              padding: const EdgeInsets.all(12),
              child: Text(
                t.heirViewBanner(entries.length),
                style: TextStyle(fontSize: 12, color: cs.onErrorContainer),
                textAlign: TextAlign.center,
              ),
            ),
            Expanded(
              child: entries.isEmpty
                  ? Center(child: Text(t.heirViewEmpty))
                  : ListView.separated(
                      itemCount: entries.length,
                      separatorBuilder: (_, i) => const Divider(height: 1),
                      itemBuilder: (_, i) => _EntryTile(entry: entries[i]),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EntryTile extends StatelessWidget {
  final Entry entry;
  const _EntryTile({required this.entry});

  Future<void> _copy(BuildContext context, String label, String value) async {
    if (value.isEmpty) return;
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    await ClipboardService.copyWithAutoClear(value);
    messenger.showSnackBar(
      SnackBar(
        content: Text(t.heirViewCopiedSnack(label)),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final cs = Theme.of(context).colorScheme;
    return ExpansionTile(
      leading: const Icon(Icons.lock_outline),
      title: Text(
        entry.title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
      ),
      // U5 v2.4.4 — `cs.onSurfaceVariant` au lieu de `Colors.grey` hardcodé
      // (contraste pauvre en dark mode WCAG ~3:1, exigence AA 4.5:1).
      subtitle: entry.username.isEmpty
          ? null
          : Text(
              entry.username,
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
              overflow: TextOverflow.ellipsis,
            ),
      children: [
        if (entry.username.isNotEmpty)
          ListTile(
            dense: true,
            leading: const Icon(Icons.person_outline, size: 18),
            title: Text(
              t.heirViewFieldUsername,
              style: const TextStyle(fontSize: 12),
            ),
            subtitle: SelectableText(
              entry.username,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            // U5 v2.4.4 — tooltip explicite (l'héritier n'est par
            // définition pas familier de l'app — c'est son SEUL usage).
            trailing: IconButton(
              icon: const Icon(Icons.copy, size: 18),
              tooltip: t.heirViewFieldUsername,
              onPressed: () =>
                  _copy(context, t.heirViewFieldUsername, entry.username),
            ),
          ),
        if (entry.password.isNotEmpty)
          ListTile(
            dense: true,
            leading: const Icon(Icons.key_outlined, size: 18),
            title: Text(
              t.heirViewFieldPassword,
              style: const TextStyle(fontSize: 12),
            ),
            subtitle: SelectableText(
              entry.password,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            trailing: IconButton(
              icon: const Icon(Icons.copy, size: 18),
              tooltip: t.heirViewFieldPassword,
              onPressed: () =>
                  _copy(context, t.heirViewFieldPassword, entry.password),
            ),
          ),
        if (entry.url.isNotEmpty)
          ListTile(
            dense: true,
            leading: const Icon(Icons.link, size: 18),
            title: Text(
              t.heirViewFieldUrl,
              style: const TextStyle(fontSize: 12),
            ),
            subtitle: SelectableText(
              entry.url,
              style: const TextStyle(fontSize: 12),
            ),
          ),
        if (entry.notes.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // U5 v2.4.4 — `cs.onSurfaceVariant` au lieu de `Colors.grey`
                // hardcodé (contraste pauvre en dark mode).
                Text(
                  t.heirViewFieldNotes,
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 4),
                SelectableText(
                  entry.notes,
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
