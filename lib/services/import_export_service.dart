import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter/foundation.dart';

import '../models/entry.dart';
import 'vault_service.dart' show pbkdf2Worker;

class ImportResult {
  final List<Entry> entries;
  final String format;
  final String? error;
  const ImportResult({required this.entries, required this.format, this.error});
}

class ImportExportService {
  // ── Plain JSON / CSV parsing ────────────────────────────────────────────────

  static ImportResult parse(String content) {
    final trimmed = content.trim();
    if (trimmed.isEmpty) {
      return const ImportResult(entries: [], format: 'unknown', error: 'Fichier vide');
    }
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      return _parseJson(trimmed);
    }
    return _parseCsv(content);
  }

  static ImportResult _parseJson(String content) {
    try {
      final json = jsonDecode(content);
      // Bitwarden: { items: [...], folders: [...] }
      if (json is Map && json.containsKey('items') && json['items'] is List) {
        return _parseBitwarden(json['items'] as List);
      }
      // Pass Tech native: [ {id, type, title, ...}, ... ]
      if (json is List) {
        final entries = <Entry>[];
        for (final item in json) {
          try {
            entries.add(Entry.fromJson(item as Map<String, dynamic>));
          } catch (_) {}
        }
        return ImportResult(entries: entries, format: 'pass_tech');
      }
      return const ImportResult(
          entries: [], format: 'unknown', error: 'Format JSON non reconnu');
    } catch (e) {
      return ImportResult(
          entries: [], format: 'unknown', error: 'JSON invalide : $e');
    }
  }

  static ImportResult _parseBitwarden(List items) {
    final entries = <Entry>[];
    for (final raw in items) {
      if (raw is! Map) continue;
      final item = Map<String, dynamic>.from(raw);
      final type = item['type'] as int? ?? 1;
      final name = (item['name'] as String?) ?? 'Sans titre';
      final notes = (item['notes'] as String?) ?? '';
      final favorite = item['favorite'] as bool? ?? false;

      try {
        switch (type) {
          case 1: // login
            final login = item['login'] as Map<String, dynamic>?;
            final username = login?['username'] as String? ?? '';
            final password = login?['password'] as String? ?? '';
            final totp = login?['totp'] as String? ?? '';
            String url = '';
            final uris = login?['uris'] as List?;
            if (uris != null && uris.isNotEmpty) {
              final first = uris.first;
              if (first is Map) url = (first['uri'] as String?) ?? '';
            }
            entries.add(Entry(
              type: EntryType.password,
              title: name,
              category: _guessCategory(name, url),
              username: username,
              password: password,
              url: url,
              totpSecret: totp,
              notes: notes,
              isFavorite: favorite,
            ));
            break;
          case 2: // secure note
            entries.add(Entry(
              type: EntryType.note,
              title: name,
              category: 'Autres',
              notes: notes,
              isFavorite: favorite,
            ));
            break;
          case 3: // card
            final card = item['card'] as Map<String, dynamic>?;
            final exp = (card?['expMonth'] as String? ?? '').padLeft(2, '0');
            final yr  = (card?['expYear'] as String? ?? '').replaceAll(
                RegExp(r'^20'), '');
            entries.add(Entry(
              type: EntryType.card,
              title: name,
              category: 'Banque',
              cardholderName: card?['cardholderName'] as String? ?? '',
              cardNumber: (card?['number'] as String? ?? '').replaceAll(' ', ''),
              cardExpiry: exp.isEmpty || yr.isEmpty ? '' : '$exp/$yr',
              cardCvv: card?['code'] as String? ?? '',
              cardIssuer: card?['brand'] as String? ?? '',
              notes: notes,
              isFavorite: favorite,
            ));
            break;
        }
      } catch (_) {}
    }
    return ImportResult(entries: entries, format: 'bitwarden');
  }

  static ImportResult _parseCsv(String content) {
    final rows = _parseCsvRows(content);
    if (rows.isEmpty) {
      return const ImportResult(entries: [], format: 'unknown', error: 'CSV vide');
    }
    final header = rows.first.map((s) => s.toLowerCase().trim()).toList();
    final data = rows.skip(1).toList();

    int? idx(List<String> names) {
      for (final n in names) {
        final i = header.indexOf(n);
        if (i >= 0) return i;
      }
      return null;
    }

    final iName = idx(['name', 'title', 'site', 'label']);
    final iUrl  = idx(['url', 'login_uri', 'website', 'web site']);
    final iUser = idx(['username', 'user', 'login', 'login_username', 'email']);
    final iPass = idx(['password', 'login_password', 'pass']);
    final iNote = idx(['note', 'notes', 'comment', 'comments']);
    final iTotp = idx(['totp', 'otp', 'login_totp']);

    if (iPass == null) {
      return const ImportResult(
          entries: [], format: 'unknown',
          error: 'Colonne "password" introuvable dans le CSV');
    }

    final entries = <Entry>[];
    for (final row in data) {
      if (row.length < 2) continue;
      String at(int? i) =>
          (i == null || i >= row.length) ? '' : row[i].trim();
      final title = at(iName).isEmpty ? at(iUrl) : at(iName);
      if (title.isEmpty && at(iPass).isEmpty) continue;
      final url = at(iUrl);
      entries.add(Entry(
        type: EntryType.password,
        title: title.isEmpty ? 'Sans titre' : title,
        category: _guessCategory(title, url),
        username: at(iUser),
        password: at(iPass),
        url: url,
        totpSecret: at(iTotp),
        notes: at(iNote),
      ));
    }

    return ImportResult(entries: entries, format: 'csv');
  }

  static String _guessCategory(String title, String url) {
    final s = '${title.toLowerCase()} ${url.toLowerCase()}';
    if (s.contains('bank') || s.contains('banque') || s.contains('boursorama') ||
        s.contains('credit') || s.contains('paypal') || s.contains('revolut')) {
      return 'Banque';
    }
    if (s.contains('mail') || s.contains('outlook') || s.contains('gmail') ||
        s.contains('proton') || s.contains('yahoo')) {
      return 'Email';
    }
    if (s.contains('facebook') || s.contains('twitter') || s.contains('insta') ||
        s.contains('linkedin') || s.contains('tiktok') || s.contains('snap')) {
      return 'Réseaux sociaux';
    }
    if (url.startsWith('http')) return 'Web';
    return 'Autres';
  }

  /// Minimal CSV parser handling quoted fields and escaped quotes.
  static List<List<String>> _parseCsvRows(String content) {
    final rows = <List<String>>[];
    final row = <String>[];
    final buf = StringBuffer();
    bool inQuotes = false;

    for (int i = 0; i < content.length; i++) {
      final c = content[i];
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < content.length && content[i + 1] == '"') {
            buf.write('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          buf.write(c);
        }
        continue;
      }
      if (c == '"') {
        inQuotes = true;
      } else if (c == ',') {
        row.add(buf.toString());
        buf.clear();
      } else if (c == '\n' || c == '\r') {
        if (buf.isNotEmpty || row.isNotEmpty) {
          row.add(buf.toString());
          buf.clear();
          rows.add(List.from(row));
          row.clear();
        }
        if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++;
      } else {
        buf.write(c);
      }
    }
    if (buf.isNotEmpty || row.isNotEmpty) {
      row.add(buf.toString());
      rows.add(row);
    }
    return rows;
  }

  // ── Encrypted .ptbak format ─────────────────────────────────────────────────
  // {magic:'PTBAK', version:1, iterations, salt, iv, mac, data}
  // PBKDF2 100k → 64 bytes (32 enc + 32 mac)
  // AES-256-CBC + HMAC-SHA256(IV || ciphertext)

  static const _backupIterations = 100000;

  static Future<String> exportEncrypted(
      List<Entry> entries, String passphrase) async {
    final salt = _randomBytes(32);
    final key = await compute(
        pbkdf2Worker, [utf8.encode(passphrase), salt, _backupIterations, 64]);
    final iv  = enc.IV.fromSecureRandom(16);
    final encKey = enc.Key(key.sublist(0, 32));
    final macKey = key.sublist(32);
    final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
    final plain = jsonEncode(entries.map((e) => e.toJson()).toList());
    final encrypted = encrypter.encrypt(plain, iv: iv);
    final mac = Hmac(sha256, macKey).convert([...iv.bytes, ...encrypted.bytes]).bytes;

    return jsonEncode({
      'magic':      'PTBAK',
      'version':    1,
      'iterations': _backupIterations,
      'salt':       base64Encode(salt),
      'iv':         base64Encode(iv.bytes),
      'mac':        base64Encode(mac),
      'data':       base64Encode(encrypted.bytes),
      'count':      entries.length,
      'exportedAt': DateTime.now().toIso8601String(),
    });
  }

  /// Returns null if passphrase wrong / file corrupted.
  static Future<List<Entry>?> importEncrypted(
      String fileContent, String passphrase) async {
    try {
      final json = jsonDecode(fileContent) as Map<String, dynamic>;
      if (json['magic'] != 'PTBAK') return null;

      final iterations = json['iterations'] as int? ?? _backupIterations;
      final salt = base64Decode(json['salt'] as String);
      final iv  = base64Decode(json['iv']   as String);
      final mac = base64Decode(json['mac']  as String);
      final cipher = base64Decode(json['data'] as String);

      final key = await compute(
          pbkdf2Worker, [utf8.encode(passphrase), salt, iterations, 64]);
      final macKey = key.sublist(32);
      final computed = Hmac(sha256, macKey).convert([...iv, ...cipher]).bytes;
      if (!_constEq(computed, mac)) return null;

      final encKey = enc.Key(key.sublist(0, 32));
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
      final plain = encrypter.decrypt(
          enc.Encrypted(Uint8List.fromList(cipher)),
          iv: enc.IV(Uint8List.fromList(iv)));

      final list = jsonDecode(plain) as List;
      final entries = <Entry>[];
      for (final item in list) {
        try {
          entries.add(Entry.fromJson(item as Map<String, dynamic>));
        } catch (_) {}
      }
      return entries;
    } catch (_) {
      return null;
    }
  }

  static bool _constEq(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) { diff |= a[i] ^ b[i]; }
    return diff == 0;
  }

  static Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List.generate(n, (_) => rng.nextInt(256)));
  }
}
