import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/clipboard_service.dart';
import '../services/vault_service.dart';
import 'entry_edit_screen.dart';

class EntryDetailScreen extends StatefulWidget {
  final Entry entry;
  final VoidCallback onChanged;
  const EntryDetailScreen({super.key, required this.entry, required this.onChanged});

  @override
  State<EntryDetailScreen> createState() => _EntryDetailScreenState();
}

class _EntryDetailScreenState extends State<EntryDetailScreen> {
  late Entry _entry;
  bool _showPassword = false;

  @override
  void initState() {
    super.initState();
    _entry = widget.entry;
  }

  Future<void> _copy(String text, String label) async {
    final messenger = ScaffoldMessenger.of(context);
    await ClipboardService.copyWithAutoClear(text);
    messenger.showSnackBar(SnackBar(
      content: Text('$label copié — effacé dans ${ClipboardService.clearAfterSeconds}s'),
      duration: const Duration(seconds: 3),
    ));
  }

  Future<void> _toggleFav() async {
    final updated = _entry.copyWith(isFavorite: !_entry.isFavorite);
    await VaultService().updateEntry(updated);
    setState(() => _entry = updated);
    widget.onChanged();
  }

  Future<void> _delete() async {
    final nav = Navigator.of(context);
    final cs  = Theme.of(context).colorScheme;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer ?'),
        content: Text('Supprimer "${_entry.title}" définitivement ?'),
        actions: [
          TextButton(onPressed: () => nav.pop(false), child: const Text('Annuler')),
          TextButton(
            onPressed: () => nav.pop(true),
            style: TextButton.styleFrom(foregroundColor: cs.error),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    await VaultService().deleteEntry(_entry.id);
    widget.onChanged();
    if (mounted) nav.pop();
  }

  Future<void> _edit() async {
    final updated = await Navigator.push<Entry>(
      context,
      MaterialPageRoute(
        builder: (_) =>
            EntryEditScreen(entry: _entry, onSaved: widget.onChanged),
      ),
    );
    if (updated != null && mounted) setState(() => _entry = updated);
  }

  @override
  Widget build(BuildContext context) {
    final cs       = Theme.of(context).colorScheme;
    final catColor = categoryColor(_entry.category);
    final fmt      = DateFormat('dd/MM/yyyy HH:mm');

    return Scaffold(
      appBar: AppBar(
        title: Text(_entry.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            icon: Icon(
              _entry.isFavorite ? Icons.star : Icons.star_border,
              color: _entry.isFavorite ? Colors.amber.shade400 : null,
            ),
            onPressed: _toggleFav,
          ),
          IconButton(icon: const Icon(Icons.edit_outlined),    onPressed: _edit),
          IconButton(
            icon: Icon(Icons.delete_outline, color: cs.error),
            onPressed: _delete,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Center(
            child: Column(children: [
              Container(
                width: 64, height: 64,
                decoration: BoxDecoration(
                  color: catColor.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(categoryIcon(_entry.category), size: 32, color: catColor),
              ),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                decoration: BoxDecoration(
                  color: catColor.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(_entry.category,
                    style: TextStyle(fontSize: 12, color: catColor,
                        fontWeight: FontWeight.w600)),
              ),
            ]),
          ),
          const SizedBox(height: 24),

          _Field(
            label: 'Identifiant',
            value: _entry.username.isEmpty ? '—' : _entry.username,
            onCopy: _entry.username.isEmpty
                ? null
                : () => _copy(_entry.username, 'Identifiant'),
          ),
          const SizedBox(height: 10),

          _PasswordField(
            password: _entry.password,
            show: _showPassword,
            onToggle: () => setState(() => _showPassword = !_showPassword),
            onCopy: () => _copy(_entry.password, 'Mot de passe'),
          ),
          const SizedBox(height: 10),

          if (_entry.url.isNotEmpty) ...[
            _Field(
              label: 'URL',
              value: _entry.url,
              onCopy: () => _copy(_entry.url, 'URL'),
            ),
            const SizedBox(height: 10),
          ],

          if (_entry.notes.isNotEmpty) ...[
            _Field(label: 'Notes', value: _entry.notes),
            const SizedBox(height: 10),
          ],

          const Divider(),
          const SizedBox(height: 6),
          Text('Créé le ${fmt.format(_entry.createdAt)}',
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
          Text('Modifié le ${fmt.format(_entry.updatedAt)}',
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
        ],
      ),
    );
  }
}

class _Field extends StatelessWidget {
  final String label;
  final String value;
  final VoidCallback? onCopy;
  const _Field({required this.label, required this.value, this.onCopy});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label,
                  style: TextStyle(
                      fontSize: 11, color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600, letterSpacing: 0.4)),
              const SizedBox(height: 3),
              SelectableText(value, style: const TextStyle(fontSize: 14)),
            ]),
          ),
          if (onCopy != null)
            IconButton(
                icon: const Icon(Icons.copy, size: 18),
                onPressed: onCopy,
                tooltip: 'Copier'),
        ]),
      ),
    );
  }
}

class _PasswordField extends StatelessWidget {
  final String password;
  final bool show;
  final VoidCallback onToggle;
  final VoidCallback onCopy;
  const _PasswordField({
    required this.password,
    required this.show,
    required this.onToggle,
    required this.onCopy,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Mot de passe',
                  style: TextStyle(
                      fontSize: 11, color: cs.onSurfaceVariant,
                      fontWeight: FontWeight.w600, letterSpacing: 0.4)),
              const SizedBox(height: 3),
              Text(
                show ? password : '•' * 12,
                style: TextStyle(
                  fontSize: 14,
                  letterSpacing: show ? 0.5 : 2,
                  fontFamily: show ? 'monospace' : null,
                ),
              ),
            ]),
          ),
          IconButton(
            icon: Icon(show ? Icons.visibility_off : Icons.visibility, size: 18),
            onPressed: onToggle,
          ),
          IconButton(
            icon: const Icon(Icons.copy, size: 18),
            onPressed: onCopy,
            tooltip: 'Copier',
          ),
        ]),
      ),
    );
  }
}
