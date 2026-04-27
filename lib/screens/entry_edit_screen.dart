import 'package:flutter/material.dart';
import '../models/category.dart';
import '../models/entry.dart';
import '../services/vault_service.dart';
import 'generator_screen.dart';

class EntryEditScreen extends StatefulWidget {
  final Entry? entry;
  final VoidCallback onSaved;
  const EntryEditScreen({super.key, this.entry, required this.onSaved});

  @override
  State<EntryEditScreen> createState() => _EntryEditScreenState();
}

class _EntryEditScreenState extends State<EntryEditScreen> {
  final _titleCtrl = TextEditingController();
  final _userCtrl  = TextEditingController();
  final _passCtrl  = TextEditingController();
  final _urlCtrl   = TextEditingController();
  final _notesCtrl = TextEditingController();

  String _category  = 'Autres';
  bool _showPass    = false;
  bool _isFavorite  = false;
  bool _saving      = false;

  bool get _isEdit => widget.entry != null;

  @override
  void initState() {
    super.initState();
    final e = widget.entry;
    if (e != null) {
      _titleCtrl.text = e.title;
      _userCtrl.text  = e.username;
      _passCtrl.text  = e.password;
      _urlCtrl.text   = e.url;
      _notesCtrl.text = e.notes;
      _category       = e.category;
      _isFavorite     = e.isFavorite;
    }
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _urlCtrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_titleCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Le titre est obligatoire')));
      return;
    }
    setState(() => _saving = true);

    Entry entry;
    if (_isEdit) {
      entry = widget.entry!.copyWith(
        title:      _titleCtrl.text.trim(),
        category:   _category,
        username:   _userCtrl.text.trim(),
        password:   _passCtrl.text,
        url:        _urlCtrl.text.trim(),
        notes:      _notesCtrl.text.trim(),
        isFavorite: _isFavorite,
      );
      await VaultService().updateEntry(entry);
    } else {
      entry = Entry(
        title:      _titleCtrl.text.trim(),
        category:   _category,
        username:   _userCtrl.text.trim(),
        password:   _passCtrl.text,
        url:        _urlCtrl.text.trim(),
        notes:      _notesCtrl.text.trim(),
        isFavorite: _isFavorite,
      );
      await VaultService().addEntry(entry);
    }

    widget.onSaved();
    if (mounted) Navigator.of(context).pop(entry);
  }

  Future<void> _pickGenerated() async {
    final pass = await Navigator.push<String>(
      context,
      MaterialPageRoute(
          builder: (_) => const GeneratorScreen(returnPassword: true)),
    );
    if (pass != null) setState(() => _passCtrl.text = pass);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEdit ? 'Modifier' : 'Nouveau'),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: Text(
              _saving ? 'Enregistrement…' : 'Enregistrer',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(children: [

          // Category
          _label(context, 'Catégorie'),
          const SizedBox(height: 6),
          SizedBox(
            height: 48,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: categories.length,
              separatorBuilder: (_, i) => const SizedBox(width: 6),
              itemBuilder: (context, i) {
                final cat      = categories[i];
                final selected = _category == cat;
                final color    = categoryColor(cat);
                return ChoiceChip(
                  label: Text(cat, style: const TextStyle(fontSize: 12)),
                  avatar: Icon(categoryIcon(cat), size: 14,
                      color: selected ? cs.onPrimary : color),
                  selected: selected,
                  selectedColor: color,
                  onSelected: (_) => setState(() => _category = cat),
                  showCheckmark: false,
                );
              },
            ),
          ),
          const SizedBox(height: 16),

          // Title
          _label(context, 'Titre *'),
          const SizedBox(height: 6),
          TextField(
            controller: _titleCtrl,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              hintText: 'ex: Google, Netflix, BNP…',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.title, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Username
          _label(context, 'Identifiant'),
          const SizedBox(height: 6),
          TextField(
            controller: _userCtrl,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(
              hintText: 'Email, nom d\'utilisateur…',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.person_outline, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Password
          _label(context, 'Mot de passe'),
          const SizedBox(height: 6),
          TextField(
            controller: _passCtrl,
            obscureText: !_showPass,
            decoration: InputDecoration(
              hintText: 'Mot de passe',
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline, size: 20),
              suffixIcon: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: Icon(
                        _showPass ? Icons.visibility_off : Icons.visibility,
                        size: 20),
                    onPressed: () => setState(() => _showPass = !_showPass),
                  ),
                  IconButton(
                    icon: const Icon(Icons.auto_fix_high, size: 20),
                    tooltip: 'Générer',
                    onPressed: _pickGenerated,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // URL
          _label(context, 'URL (optionnel)'),
          const SizedBox(height: 6),
          TextField(
            controller: _urlCtrl,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
              hintText: 'https://…',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.link, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Notes
          _label(context, 'Notes (optionnel)'),
          const SizedBox(height: 6),
          TextField(
            controller: _notesCtrl,
            maxLines: 3,
            decoration: const InputDecoration(
              hintText: 'Informations supplémentaires…',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
              prefixIcon: Icon(Icons.notes, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Favorite
          Card(
            child: SwitchListTile(
              title: const Text('Favori'),
              secondary: Icon(
                _isFavorite ? Icons.star : Icons.star_border,
                color: _isFavorite ? Colors.amber : null,
              ),
              value: _isFavorite,
              onChanged: (v) => setState(() => _isFavorite = v),
            ),
          ),
          const SizedBox(height: 24),

          FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save_outlined, size: 18),
            label: Text(_saving ? 'Enregistrement…' : 'Enregistrer'),
            style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48)),
          ),
        ]),
      ),
    );
  }

  Widget _label(BuildContext context, String text) => Align(
        alignment: Alignment.centerLeft,
        child: Text(text,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                letterSpacing: 0.4)),
      );
}
