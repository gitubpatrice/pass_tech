import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show HapticFeedback;
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../l10n/app_localizations.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/vault_service.dart';
import 'entry_detail_screen.dart';
import 'entry_edit_screen.dart';
import 'generator_screen.dart';
import 'settings_screen.dart';
import 'about_screen.dart';
import 'unlock_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String _search = '';
  // Internal stable filter keys (i18n-safe). UI labels are resolved via
  // _filterLabel() against the current locale; categories keep their raw
  // (data-side) string.
  static const _filterAll = '__all__';
  static const _filterFavorites = '__fav__';
  static const _filterPasswords = '__type_password__';
  static const _filterNotes = '__type_note__';
  static const _filterCards = '__type_card__';
  String _filter = _filterAll;
  final _searchCtrl = TextEditingController();
  bool _searchOpen = false;
  String _sort = 'recent';
  // P1.1 v2.4.3 — debounce 150 ms sur la barre de recherche. Avant : un
  // `setState(() => _search = v)` à chaque frappe relançait le tri+filtrage
  // complet (toLowerCase × N entries × N champs) → 8-12 ms par char sur S9
  // avec 500 entries, jank visible. Le debounce regroupe les frappes.
  Timer? _searchDebounce;

  static const _sortValues = ['recent', 'oldest', 'alpha', 'alphaDesc'];
  static const _sortIcons = {
    'recent': Icons.schedule,
    'oldest': Icons.history,
    'alpha': Icons.sort_by_alpha,
    'alphaDesc': Icons.sort_by_alpha,
  };

  String _sortLabel(String v, AppLocalizations t) {
    switch (v) {
      case 'oldest':
        return t.homeSortOldest;
      case 'alpha':
        return t.homeSortAlpha;
      case 'alphaDesc':
        return t.homeSortAlphaDesc;
      default:
        return t.homeSortRecent;
    }
  }

  String _filterLabel(String f, AppLocalizations t) {
    switch (f) {
      case _filterAll:
        return t.homeFilterAll;
      case _filterFavorites:
        return t.homeFilterFavorites;
      case _filterPasswords:
        return t.homeFilterPasswords;
      case _filterNotes:
        return t.homeFilterNotes;
      case _filterCards:
        return t.homeFilterCards;
      default:
        return categoryLabel(f, t);
    }
  }

  @override
  void initState() {
    super.initState();
    _loadSort();
  }

  Future<void> _loadSort() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted) {
      setState(() {
        _sort = prefs.getString('sort_mode') ?? 'recent';
      });
    }
  }

  Future<void> _setSort(String s) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('sort_mode', s);
    if (mounted) {
      setState(() {
        _sort = s;
        _invalidateFiltered();
      });
    }
  }

  static const _typeChips = [_filterPasswords, _filterNotes, _filterCards];
  static const _chips = [
    _filterAll,
    _filterFavorites,
    ..._typeChips,
    ...categories,
  ];

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchCtrl.dispose();
    super.dispose();
  }

  EntryType? _typeFromFilter(String f) {
    switch (f) {
      case _filterPasswords:
        return EntryType.password;
      case _filterNotes:
        return EntryType.note;
      case _filterCards:
        return EntryType.card;
      default:
        return null;
    }
  }

  /// P1 v2.4.4 — Liste filtrée/triée mémoïsée. Avant : getter recalculé à
  /// CHAQUE build (4 passes `where().toList()` + `toLowerCase × N` + `sort`)
  /// même quand le rebuild était trigger par un `setState` non lié au filter
  /// (ex. ouverture/fermeture de la barre de recherche, FAB hover). Avec 500
  /// entries : 4-8 ms × ~6 rebuilds/écran = 25-50 ms évités, 150 ms sur 1000
  /// entries. Le cache est invalidé via `_invalidateFiltered()` aux endroits
  /// où l'input change : `_filter`, `_sort`, `_search`, mutations entries
  /// (add/update/delete via `_refresh`).
  ///
  /// Pour garantir la fraîcheur après mutations indirectes (édition depuis
  /// AuditScreen via callback parallèle), `_refresh()` invalide aussi le
  /// cache et trigger un rebuild.
  List<Entry>? _cachedFiltered;
  int? _cachedEntriesSignature;

  void _invalidateFiltered() {
    _cachedFiltered = null;
    _cachedEntriesSignature = null;
  }

  /// v2.5.0 (F3) : signature basée sur (length × 31) XOR max(updatedAt).
  /// Ancien cache invalidait seulement sur changement de longueur — un
  /// update d'entry (titre, password) sans add/delete laissait le cache
  /// stale si `_refresh()` n'était pas appelé par tous les paths de retour
  /// (cas confirmé : édition depuis AuditScreen via callback parallèle).
  /// Le `updatedAt.millisecondsSinceEpoch` est touché par chaque
  /// `VaultService.save()` → mutation visible dans la signature.
  int _entriesSignature(List<Entry> entries) {
    if (entries.isEmpty) return 0;
    var maxUpdated = 0;
    for (final e in entries) {
      final ms = e.updatedAt.millisecondsSinceEpoch;
      if (ms > maxUpdated) maxUpdated = ms;
    }
    return entries.length * 31 ^ maxUpdated;
  }

  List<Entry> get _filtered {
    final entries = VaultService().entries;
    final sig = _entriesSignature(entries);
    // Garde robuste : la signature change sur add/delete (length) ET sur
    // update d'une entry (updatedAt). Évite tout cache stale même si une
    // mutation indirecte oublie d'appeler `_refresh()`.
    if (_cachedFiltered != null && _cachedEntriesSignature == sig) {
      return _cachedFiltered!;
    }
    var list = entries.toList();
    if (_filter == _filterFavorites) {
      list = list.where((e) => e.isFavorite).toList();
    } else if (_typeFromFilter(_filter) != null) {
      final t = _typeFromFilter(_filter)!;
      list = list.where((e) => e.type == t).toList();
    } else if (_filter != _filterAll) {
      list = list.where((e) => e.category == _filter).toList();
    }
    if (_search.isNotEmpty) {
      final q = _search.toLowerCase();
      list = list
          .where(
            (e) =>
                e.title.toLowerCase().contains(q) ||
                e.username.toLowerCase().contains(q) ||
                e.url.toLowerCase().contains(q) ||
                e.notes.toLowerCase().contains(q),
          )
          .toList();
    }
    switch (_sort) {
      case 'oldest':
        list.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
        break;
      case 'alpha':
        list.sort(
          (a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
        );
        break;
      case 'alphaDesc':
        list.sort(
          (a, b) => b.title.toLowerCase().compareTo(a.title.toLowerCase()),
        );
        break;
      default:
        list.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    }
    _cachedFiltered = list;
    _cachedEntriesSignature = sig;
    return list;
  }

  void _refresh() => setState(_invalidateFiltered);

  Future<void> _addEntry() async {
    final t = AppLocalizations.of(context);
    final type = await showModalBottomSheet<EntryType>(
      context: context,
      showDragHandle: true,
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 0, 8, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 0, 8, 12),
                child: Text(
                  t.homeAddSheetTitle,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              _addOption(
                context: context,
                icon: Icons.key,
                color: const Color(0xFF58A6FF),
                title: t.homeAddPassword,
                subtitle: t.homeAddPasswordSub,
                onTap: () => Navigator.pop(context, EntryType.password),
              ),
              _addOption(
                context: context,
                icon: Icons.sticky_note_2_outlined,
                color: const Color(0xFFFF7043),
                title: t.homeAddNote,
                subtitle: t.homeAddNoteSub,
                onTap: () => Navigator.pop(context, EntryType.note),
              ),
              _addOption(
                context: context,
                icon: Icons.credit_card,
                color: const Color(0xFF43A047),
                title: t.homeAddCard,
                subtitle: t.homeAddCardSub,
                onTap: () => Navigator.pop(context, EntryType.card),
              ),
            ],
          ),
        ),
      ),
    );
    if (type == null || !mounted) return;
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => EntryEditScreen(type: type, onSaved: _refresh),
      ),
    );
  }

  Widget _addOption({
    required BuildContext context,
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) => ListTile(
    leading: Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Icon(icon, color: color),
    ),
    title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
    subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
    onTap: onTap,
  );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final entries = _filtered;

    return Scaffold(
      appBar: AppBar(
        title: _searchOpen
            ? TextField(
                controller: _searchCtrl,
                autofocus: true,
                textInputAction: TextInputAction.search,
                decoration: InputDecoration(
                  hintText: t.homeSearchHint,
                  border: InputBorder.none,
                  isDense: true,
                  // U14 v2.4.3 — bouton clear inline (croix) plus
                  // découvrable que l'action AppBar Close. Disparaît quand
                  // le champ est vide.
                  suffixIcon: _search.isNotEmpty
                      ? IconButton(
                          icon: const Icon(Icons.close, size: 18),
                          tooltip: MaterialLocalizations.of(
                            context,
                          ).deleteButtonTooltip,
                          onPressed: () {
                            _searchCtrl.clear();
                            _searchDebounce?.cancel();
                            setState(() {
                              _search = '';
                              _invalidateFiltered();
                            });
                          },
                        )
                      : null,
                ),
                onChanged: (v) {
                  _searchDebounce?.cancel();
                  _searchDebounce = Timer(
                    const Duration(milliseconds: 150),
                    () {
                      if (mounted) {
                        setState(() {
                          _search = v;
                          _invalidateFiltered();
                        });
                      }
                    },
                  );
                },
              )
            : Text(t.homeTitle),
        actions: [
          IconButton(
            // U3 v2.4.3 — tooltip explicite pour TalkBack + survol pointeur.
            tooltip: _searchOpen
                ? MaterialLocalizations.of(context).closeButtonTooltip
                : MaterialLocalizations.of(context).searchFieldLabel,
            icon: Icon(_searchOpen ? Icons.close : Icons.search),
            onPressed: () => setState(() {
              _searchOpen = !_searchOpen;
              if (!_searchOpen) {
                _search = '';
                _searchCtrl.clear();
                _invalidateFiltered();
              }
            }),
          ),
          IconButton(
            icon: const Icon(Icons.password),
            tooltip: t.homeTooltipGenerator,
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const GeneratorScreen()),
            ),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.sort),
            tooltip: t.homeTooltipSort,
            itemBuilder: (_) => _sortValues
                .map(
                  (v) => PopupMenuItem(
                    value: v,
                    child: Row(
                      children: [
                        Icon(_sortIcons[v], size: 16),
                        const SizedBox(width: 10),
                        Text(_sortLabel(v, t)),
                        if (_sort == v) ...[
                          const SizedBox(width: 8),
                          const Icon(Icons.check, size: 14),
                        ],
                      ],
                    ),
                  ),
                )
                .toList(),
            onSelected: _setSort,
          ),
          IconButton(
            icon: const Icon(Icons.lock_outline),
            tooltip: t.homeTooltipLock,
            onPressed: () {
              // U9 v2.4.4 — feedback haptique sur lock manuel.
              HapticFeedback.mediumImpact();
              VaultService().lock();
              Navigator.of(context).pushAndRemoveUntil(
                MaterialPageRoute(builder: (_) => const UnlockScreen()),
                (_) => false,
              );
            },
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            // U4 v2.4.3 — tooltip pour TalkBack (Flutter built-in en
            // FR/EN/etc., évite d'ajouter une clé l10n custom).
            tooltip: MaterialLocalizations.of(context).moreButtonTooltip,
            itemBuilder: (_) => [
              PopupMenuItem(value: 'settings', child: Text(t.actionSettings)),
              PopupMenuItem(value: 'about', child: Text(t.actionAbout)),
            ],
            onSelected: (v) {
              if (v == 'settings') {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => SettingsScreen(onChanged: _refresh),
                  ),
                );
              } else {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const AboutScreen()),
                );
              }
            },
          ),
        ],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 46,
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              scrollDirection: Axis.horizontal,
              itemCount: _chips.length,
              separatorBuilder: (_, i) => const SizedBox(width: 6),
              itemBuilder: (context, i) {
                final chip = _chips[i];
                final selected = _filter == chip;
                final isType = _typeChips.contains(chip);
                return FilterChip(
                  avatar: isType
                      ? Icon(
                          _chipIcon(chip),
                          size: 14,
                          color: selected ? cs.onPrimary : null,
                        )
                      : null,
                  label: Text(
                    _filterLabel(chip, t),
                    style: const TextStyle(fontSize: 12),
                  ),
                  selected: selected,
                  onSelected: (_) => setState(() {
                    _filter = chip;
                    _invalidateFiltered();
                  }),
                  showCheckmark: false,
                );
              },
            ),
          ),

          Padding(
            padding: const EdgeInsets.fromLTRB(16, 2, 16, 4),
            child: Row(
              children: [
                Text(
                  t.homeEntryCount(entries.length),
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
              ],
            ),
          ),

          Expanded(
            child: entries.isEmpty
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.lock_outline,
                          size: 64,
                          color: cs.onSurfaceVariant.withValues(alpha: 0.25),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          _search.isNotEmpty
                              ? t.homeEmptyNoResult
                              : t.homeEmptyNoEntry,
                          style: TextStyle(color: cs.onSurfaceVariant),
                        ),
                        if (_search.isEmpty && _filter == _filterAll) ...[
                          const SizedBox(height: 6),
                          Text(
                            t.homeEmptyHint,
                            style: TextStyle(
                              fontSize: 12,
                              color: cs.onSurfaceVariant,
                            ),
                          ),
                          // U12 v2.4.3 — CTA bouton "Ajouter" inline plutôt
                          // que seulement laisser l'utilisateur découvrir
                          // le FAB en bas. Utile pour le premier lancement.
                          const SizedBox(height: 16),
                          FilledButton.tonalIcon(
                            icon: const Icon(Icons.add),
                            label: Text(t.actionAdd),
                            onPressed: () => Navigator.push(
                              context,
                              MaterialPageRoute(
                                builder: (_) =>
                                    EntryEditScreen(onSaved: _refresh),
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 80),
                    itemCount: entries.length,
                    itemBuilder: (ctx, i) =>
                        _EntryCard(entry: entries[i], onChanged: _refresh),
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _addEntry,
        icon: const Icon(Icons.add),
        label: Text(t.actionAdd),
      ),
    );
  }

  IconData _chipIcon(String chip) {
    switch (chip) {
      case _filterPasswords:
        return Icons.key;
      case _filterNotes:
        return Icons.sticky_note_2_outlined;
      case _filterCards:
        return Icons.credit_card;
      default:
        return Icons.folder_outlined;
    }
  }
}

class _EntryCard extends StatelessWidget {
  final Entry entry;
  final VoidCallback onChanged;
  const _EntryCard({required this.entry, required this.onChanged});

  IconData get _leadingIcon {
    switch (entry.type) {
      case EntryType.note:
        return Icons.sticky_note_2_outlined;
      case EntryType.card:
        return Icons.credit_card;
      case EntryType.password:
        return categoryIcon(entry.category);
    }
  }

  Color _leadingColor() {
    switch (entry.type) {
      case EntryType.note:
        return const Color(0xFFFF7043);
      case EntryType.card:
        return const Color(0xFF43A047);
      case EntryType.password:
        return categoryColor(entry.category);
    }
  }

  String get _subtitle {
    switch (entry.type) {
      case EntryType.password:
        return entry.username;
      case EntryType.note:
        final preview = entry.notes.replaceAll('\n', ' ').trim();
        return preview.length > 60 ? '${preview.substring(0, 60)}…' : preview;
      case EntryType.card:
        final n = entry.cardNumber.replaceAll(' ', '');
        if (n.length >= 4) return '•••• ${n.substring(n.length - 4)}';
        return entry.cardIssuer;
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final color = _leadingColor();

    return Slidable(
      key: ValueKey(entry.id),
      startActionPane: ActionPane(
        motion: const BehindMotion(),
        extentRatio: 0.25,
        children: [
          SlidableAction(
            onPressed: (_) async {
              final updated = entry.copyWith(isFavorite: !entry.isFavorite);
              await VaultService().updateEntry(updated);
              onChanged();
            },
            backgroundColor: Colors.amber.shade600,
            foregroundColor: Colors.white,
            icon: entry.isFavorite ? Icons.star : Icons.star_border,
            label: entry.isFavorite
                ? AppLocalizations.of(context).actionRemove
                : AppLocalizations.of(context).actionFavorite,
            borderRadius: BorderRadius.circular(12),
          ),
        ],
      ),
      endActionPane: ActionPane(
        motion: const BehindMotion(),
        extentRatio: 0.25,
        children: [
          SlidableAction(
            onPressed: (ctx) async {
              final t = AppLocalizations.of(ctx);
              final messenger = ScaffoldMessenger.of(ctx);
              final confirm = await showDialog<bool>(
                context: ctx,
                builder: (dctx) => AlertDialog(
                  title: Text(t.homeDeleteTitle),
                  content: Text(t.homeDeleteConfirm(entry.title)),
                  actions: [
                    // U10 v2.4.3 — `autofocus: true` sur Annuler : un swipe
                    // accidentel jusqu'à la zone delete + dialog s'affiche →
                    // la touche Entrée (clavier physique / a11y) ne déclenche
                    // plus la suppression mais l'annulation. Cancel devient
                    // l'action par défaut (safe default).
                    TextButton(
                      autofocus: true,
                      onPressed: () => Navigator.pop(dctx, false),
                      child: Text(t.actionCancel),
                    ),
                    FilledButton.tonal(
                      onPressed: () => Navigator.pop(dctx, true),
                      style: FilledButton.styleFrom(
                        backgroundColor: Theme.of(
                          ctx,
                        ).colorScheme.errorContainer,
                        foregroundColor: Theme.of(
                          ctx,
                        ).colorScheme.onErrorContainer,
                      ),
                      child: Text(t.actionDelete),
                    ),
                  ],
                ),
              );
              if (confirm != true) return;
              await VaultService().deleteEntry(entry.id);
              onChanged();
              messenger.showSnackBar(
                SnackBar(
                  content: Text(t.homeDeletedSnack(entry.title)),
                  duration: const Duration(seconds: 2),
                ),
              );
            },
            backgroundColor: cs.error,
            foregroundColor: Colors.white,
            icon: Icons.delete_outline,
            label: AppLocalizations.of(context).actionDelete,
            borderRadius: BorderRadius.circular(12),
          ),
        ],
      ),
      child: Card(
        margin: const EdgeInsets.only(bottom: 6),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () => Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) =>
                  EntryDetailScreen(entry: entry, onChanged: onChanged),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(_leadingIcon, size: 20, color: color),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              entry.title,
                              style: const TextStyle(
                                fontWeight: FontWeight.w600,
                                fontSize: 14,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (entry.type == EntryType.password &&
                              entry.totpSecret.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(left: 4),
                              child: Icon(
                                Icons.shield_outlined,
                                size: 14,
                                color: cs.primary,
                              ),
                            ),
                        ],
                      ),
                      if (_subtitle.isNotEmpty)
                        Text(
                          _subtitle,
                          style: TextStyle(
                            fontSize: 12,
                            color: cs.onSurfaceVariant,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                  ),
                ),
                const SizedBox(width: 6),
                if (entry.isFavorite)
                  Icon(Icons.star, size: 16, color: Colors.amber.shade400),
                const SizedBox(width: 4),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: categoryColor(
                      entry.category,
                    ).withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    categoryLabel(entry.category, AppLocalizations.of(context)),
                    style: TextStyle(
                      fontSize: 10,
                      color: categoryColor(entry.category),
                      fontWeight: FontWeight.w600,
                    ),
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
