import 'package:flutter/material.dart';
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
  String _filter = 'Tous';
  final _searchCtrl = TextEditingController();
  bool _searchOpen = false;

  static const _typeChips = ['Mots de passe', 'Notes', 'Cartes bancaires'];
  static const _chips = [
    'Tous', 'Favoris',
    ..._typeChips,
    ...categories,
  ];

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  EntryType? _typeFromFilter(String f) {
    switch (f) {
      case 'Mots de passe':    return EntryType.password;
      case 'Notes':            return EntryType.note;
      case 'Cartes bancaires': return EntryType.card;
      default:                 return null;
    }
  }

  List<Entry> get _filtered {
    var list = VaultService().entries.toList();
    if (_filter == 'Favoris') {
      list = list.where((e) => e.isFavorite).toList();
    } else if (_typeFromFilter(_filter) != null) {
      final t = _typeFromFilter(_filter)!;
      list = list.where((e) => e.type == t).toList();
    } else if (_filter != 'Tous') {
      list = list.where((e) => e.category == _filter).toList();
    }
    if (_search.isNotEmpty) {
      final q = _search.toLowerCase();
      list = list.where((e) =>
          e.title.toLowerCase().contains(q) ||
          e.username.toLowerCase().contains(q) ||
          e.url.toLowerCase().contains(q) ||
          e.notes.toLowerCase().contains(q)).toList();
    }
    list.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return list;
  }

  void _refresh() => setState(() {});

  Future<void> _addEntry() async {
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
                child: Text('Quel type d\'entrée ?',
                    style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(fontWeight: FontWeight.w700)),
              ),
              _addOption(
                context: context,
                icon: Icons.key,
                color: const Color(0xFF58A6FF),
                title: 'Mot de passe',
                subtitle: 'Identifiant et mot de passe d\'un compte',
                onTap: () => Navigator.pop(context, EntryType.password),
              ),
              _addOption(
                context: context,
                icon: Icons.sticky_note_2_outlined,
                color: const Color(0xFFFF7043),
                title: 'Note sécurisée',
                subtitle: 'Texte confidentiel chiffré',
                onTap: () => Navigator.pop(context, EntryType.note),
              ),
              _addOption(
                context: context,
                icon: Icons.credit_card,
                color: const Color(0xFF43A047),
                title: 'Carte bancaire',
                subtitle: 'Numéro, CVV, expiration, PIN',
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
  }) =>
      ListTile(
        leading: Container(
          width: 44, height: 44,
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
    final cs      = Theme.of(context).colorScheme;
    final entries = _filtered;

    return Scaffold(
      appBar: AppBar(
        title: _searchOpen
            ? TextField(
                controller: _searchCtrl,
                autofocus: true,
                decoration: const InputDecoration(
                  hintText: 'Rechercher…',
                  border: InputBorder.none,
                  isDense: true,
                ),
                onChanged: (v) => setState(() => _search = v),
              )
            : const Text('Pass Tech'),
        actions: [
          IconButton(
            icon: Icon(_searchOpen ? Icons.close : Icons.search),
            onPressed: () => setState(() {
              _searchOpen = !_searchOpen;
              if (!_searchOpen) { _search = ''; _searchCtrl.clear(); }
            }),
          ),
          IconButton(
            icon: const Icon(Icons.password),
            tooltip: 'Générateur',
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const GeneratorScreen())),
          ),
          IconButton(
            icon: const Icon(Icons.lock_outline),
            tooltip: 'Verrouiller',
            onPressed: () {
              VaultService().lock();
              Navigator.of(context).pushAndRemoveUntil(
                MaterialPageRoute(builder: (_) => const UnlockScreen()),
                (_) => false,
              );
            },
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'settings', child: Text('Paramètres')),
              PopupMenuItem(value: 'about',    child: Text('À propos')),
            ],
            onSelected: (v) {
              if (v == 'settings') {
                Navigator.push(context,
                    MaterialPageRoute(
                        builder: (_) => SettingsScreen(onChanged: _refresh)));
              } else {
                Navigator.push(context,
                    MaterialPageRoute(builder: (_) => const AboutScreen()));
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
                final chip     = _chips[i];
                final selected = _filter == chip;
                final isType   = _typeChips.contains(chip);
                return FilterChip(
                  avatar: isType ? Icon(_chipIcon(chip), size: 14,
                      color: selected ? cs.onPrimary : null) : null,
                  label: Text(chip, style: const TextStyle(fontSize: 12)),
                  selected: selected,
                  onSelected: (_) => setState(() => _filter = chip),
                  showCheckmark: false,
                );
              },
            ),
          ),

          Padding(
            padding: const EdgeInsets.fromLTRB(16, 2, 16, 4),
            child: Row(children: [
              Text('${entries.length} entrée${entries.length != 1 ? 's' : ''}',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
            ]),
          ),

          Expanded(
            child: entries.isEmpty
                ? Center(
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.lock_outline, size: 64,
                          color: cs.onSurfaceVariant.withValues(alpha: 0.25)),
                      const SizedBox(height: 12),
                      Text(
                        _search.isNotEmpty ? 'Aucun résultat' : 'Aucune entrée',
                        style: TextStyle(color: cs.onSurfaceVariant),
                      ),
                      if (_search.isEmpty && _filter == 'Tous') ...[
                        const SizedBox(height: 6),
                        Text('Appuie sur + pour ajouter',
                            style: TextStyle(
                                fontSize: 12, color: cs.onSurfaceVariant)),
                      ],
                    ]),
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
        label: const Text('Ajouter'),
      ),
    );
  }

  IconData _chipIcon(String chip) {
    switch (chip) {
      case 'Mots de passe':    return Icons.key;
      case 'Notes':            return Icons.sticky_note_2_outlined;
      case 'Cartes bancaires': return Icons.credit_card;
      default:                 return Icons.folder_outlined;
    }
  }
}

class _EntryCard extends StatelessWidget {
  final Entry entry;
  final VoidCallback onChanged;
  const _EntryCard({required this.entry, required this.onChanged});

  IconData get _leadingIcon {
    switch (entry.type) {
      case EntryType.note: return Icons.sticky_note_2_outlined;
      case EntryType.card: return Icons.credit_card;
      case EntryType.password: return categoryIcon(entry.category);
    }
  }

  Color _leadingColor() {
    switch (entry.type) {
      case EntryType.note: return const Color(0xFFFF7043);
      case EntryType.card: return const Color(0xFF43A047);
      case EntryType.password: return categoryColor(entry.category);
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

    return Card(
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
          child: Row(children: [
            Container(
              width: 40, height: 40,
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
                  Row(children: [
                    Expanded(
                      child: Text(entry.title,
                          style: const TextStyle(
                              fontWeight: FontWeight.w600, fontSize: 14),
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                    ),
                    if (entry.type == EntryType.password &&
                        entry.totpSecret.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(left: 4),
                        child: Icon(Icons.shield_outlined,
                            size: 14, color: cs.primary),
                      ),
                  ]),
                  if (_subtitle.isNotEmpty)
                    Text(_subtitle,
                        style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                        maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
            const SizedBox(width: 6),
            if (entry.isFavorite)
              Icon(Icons.star, size: 16, color: Colors.amber.shade400),
            const SizedBox(width: 4),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: categoryColor(entry.category).withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(entry.category,
                  style: TextStyle(
                      fontSize: 10,
                      color: categoryColor(entry.category),
                      fontWeight: FontWeight.w600)),
            ),
          ]),
        ),
      ),
    );
  }
}
