import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';

import '../models/entry.dart';
import 'aead_service.dart';
import 'kdf_service.dart';
import 'vault_service.dart' show pbkdf2Worker;

class ImportResult {
  final List<Entry> entries;
  final String format;
  final String? error;
  const ImportResult({required this.entries, required this.format, this.error});
}

class ImportExportService {
  // ── Plain JSON / CSV parsing ────────────────────────────────────────────────

  /// Plain JSON/CSV files larger than this are rejected (DoS safety).
  static const _maxImportBytes = 50 * 1024 * 1024; // 50 MB

  static ImportResult parse(String content) {
    if (content.length > _maxImportBytes) {
      return const ImportResult(
        entries: [],
        format: 'unknown',
        error: 'Fichier trop volumineux (max 50 Mo)',
      );
    }
    final trimmed = content.trim();
    if (trimmed.isEmpty) {
      return const ImportResult(
        entries: [],
        format: 'unknown',
        error: 'Fichier vide',
      );
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
        entries: [],
        format: 'unknown',
        error: 'Format JSON non reconnu',
      );
    } catch (e) {
      return ImportResult(
        entries: [],
        format: 'unknown',
        error: 'JSON invalide : $e',
      );
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
            entries.add(
              Entry(
                type: EntryType.password,
                title: name,
                category: _guessCategory(name, url),
                username: username,
                password: password,
                url: url,
                totpSecret: totp,
                notes: notes,
                isFavorite: favorite,
              ),
            );
            break;
          case 2: // secure note
            entries.add(
              Entry(
                type: EntryType.note,
                title: name,
                category: 'Autres',
                notes: notes,
                isFavorite: favorite,
              ),
            );
            break;
          case 3: // card
            final card = item['card'] as Map<String, dynamic>?;
            final exp = (card?['expMonth'] as String? ?? '').padLeft(2, '0');
            final yr = (card?['expYear'] as String? ?? '').replaceAll(
              RegExp(r'^20'),
              '',
            );
            entries.add(
              Entry(
                type: EntryType.card,
                title: name,
                category: 'Banque',
                cardholderName: card?['cardholderName'] as String? ?? '',
                cardNumber: (card?['number'] as String? ?? '').replaceAll(
                  ' ',
                  '',
                ),
                cardExpiry: exp.isEmpty || yr.isEmpty ? '' : '$exp/$yr',
                cardCvv: card?['code'] as String? ?? '',
                cardIssuer: card?['brand'] as String? ?? '',
                notes: notes,
                isFavorite: favorite,
              ),
            );
            break;
        }
      } catch (_) {}
    }
    return ImportResult(entries: entries, format: 'bitwarden');
  }

  static ImportResult _parseCsv(String content) {
    final List<List<String>> rows;
    try {
      rows = _parseCsvRows(content);
    } on FormatException catch (e) {
      return ImportResult(
        entries: [],
        format: 'unknown',
        error: 'CSV invalide : ${e.message}',
      );
    }
    if (rows.isEmpty) {
      return const ImportResult(
        entries: [],
        format: 'unknown',
        error: 'CSV vide',
      );
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
    final iUrl = idx(['url', 'login_uri', 'website', 'web site']);
    final iUser = idx(['username', 'user', 'login', 'login_username', 'email']);
    final iPass = idx(['password', 'login_password', 'pass']);
    final iNote = idx(['note', 'notes', 'comment', 'comments']);
    final iTotp = idx(['totp', 'otp', 'login_totp']);

    if (iPass == null) {
      return const ImportResult(
        entries: [],
        format: 'unknown',
        error: 'Colonne "password" introuvable dans le CSV',
      );
    }

    final entries = <Entry>[];
    for (final row in data) {
      if (row.length < 2) continue;
      String at(int? i) => (i == null || i >= row.length) ? '' : row[i].trim();
      final title = at(iName).isEmpty ? at(iUrl) : at(iName);
      if (title.isEmpty && at(iPass).isEmpty) continue;
      final url = at(iUrl);
      entries.add(
        Entry(
          type: EntryType.password,
          title: title.isEmpty ? 'Sans titre' : title,
          category: _guessCategory(title, url),
          username: at(iUser),
          password: at(iPass),
          url: url,
          totpSecret: at(iTotp),
          notes: at(iNote),
        ),
      );
    }

    return ImportResult(entries: entries, format: 'csv');
  }

  static String _guessCategory(String title, String url) {
    final s = '${title.toLowerCase()} ${url.toLowerCase()}';
    if (s.contains('bank') ||
        s.contains('banque') ||
        s.contains('boursorama') ||
        s.contains('credit') ||
        s.contains('paypal') ||
        s.contains('revolut')) {
      return 'Banque';
    }
    if (s.contains('mail') ||
        s.contains('outlook') ||
        s.contains('gmail') ||
        s.contains('proton') ||
        s.contains('yahoo')) {
      return 'Email';
    }
    if (s.contains('facebook') ||
        s.contains('twitter') ||
        s.contains('insta') ||
        s.contains('linkedin') ||
        s.contains('tiktok') ||
        s.contains('snap')) {
      return 'Réseaux sociaux';
    }
    if (url.startsWith('http')) return 'Web';
    return 'Autres';
  }

  /// Cap par cellule CSV (M-8). Un CSV malicieux avec une cellule de 50 Mo
  /// en quotes ouvertes aurait fait croître le StringBuffer sans borne. La
  /// limite globale `_maxImportBytes` (50 Mo) cap déjà l'input total, mais
  /// on borne aussi par cellule pour limiter le worst-case mémoire.
  static const _maxCsvCellBytes = 64 * 1024;

  /// Minimal CSV parser handling quoted fields and escaped quotes.
  /// Lance FormatException si une cellule dépasse [_maxCsvCellBytes].
  static List<List<String>> _parseCsvRows(String content) {
    final rows = <List<String>>[];
    final row = <String>[];
    final buf = StringBuffer();
    bool inQuotes = false;

    void checkCellSize() {
      if (buf.length > _maxCsvCellBytes) {
        throw const FormatException('Cellule CSV trop volumineuse (max 64 Ko)');
      }
    }

    for (int i = 0; i < content.length; i++) {
      final c = content[i];
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < content.length && content[i + 1] == '"') {
            buf.write('"');
            checkCellSize();
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          buf.write(c);
          checkCellSize();
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
        checkCellSize();
      }
    }
    if (buf.isNotEmpty || row.isNotEmpty) {
      row.add(buf.toString());
      rows.add(row);
    }
    return rows;
  }

  // ── Encrypted .ptbak format ─────────────────────────────────────────────────
  // v3 (current, F1 v2.4.3): Argon2id (KdfParams.owaspMobile2024) + AES-GCM-256
  //                          AAD = "ptbak:v=3|kdf=argon2id|m=19456|t=2|p=1|salt=..."
  //                          → ~1 try/sec sur mobile, ~10/sec sur GPU offline
  //                          (vs ~50M/sec pour PBKDF2-SHA256 600k).
  // v2 (legacy):  MAC = HMAC(macKey, AAD || IV || ciphertext)
  //               AAD = "ptbak:v=2|iter=N|salt=..."  (covers metadata)
  // v1 (legacy):  MAC = HMAC(macKey, IV || ciphertext)
  // v1/v2 : PBKDF2-SHA256 600 000 → 64 bytes (32 enc + 32 mac), lecture seule.

  static const _backupIterations = 600000;
  static const _maxBackupIterations = 2000000;
  static const _backupVersionV3 = 3;

  /// Construit l'AAD canonique v3 (anti-downgrade).
  static List<int> _aadV3(String saltB64) => utf8.encode(
    'ptbak:v=$_backupVersionV3|kdf=argon2id'
    '|m=${KdfParams.owaspMobile2024.memoryKiB}'
    '|t=${KdfParams.owaspMobile2024.iterations}'
    '|p=${KdfParams.owaspMobile2024.parallelism}'
    '|salt=$saltB64',
  );

  /// Exporte les entrées chiffrées au format .ptbak v3 (Argon2id + AES-GCM).
  ///
  /// F1 v2.4.3 — Migration de PBKDF2-SHA256 600k vers Argon2id, alignant le
  /// `.ptbak` (fichier qui circule hors device) sur la robustesse du vault
  /// v4. Empêche le brute-force GPU offline (~50 M tries/s pour PBKDF2 vs
  /// ~10/s pour Argon2id même sur GPU haut de gamme).
  static Future<String> exportEncrypted(
    List<Entry> entries,
    String passphrase,
  ) async {
    final salt = SecretBytes.randomBytes(32);
    Uint8List? key;
    try {
      key = await KdfService.argon2id(password: passphrase, salt: salt);
      final saltB64 = base64Encode(salt);
      final aad = Uint8List.fromList(_aadV3(saltB64));
      final plain = Uint8List.fromList(
        utf8.encode(jsonEncode(entries.map((e) => e.toJson()).toList())),
      );
      final res = await AeadService.encryptGcm(
        key: key,
        plaintext: plain,
        aad: aad,
      );
      return jsonEncode({
        'magic': 'PTBAK',
        'version': _backupVersionV3,
        'kdf': {
          'algo': 'argon2id',
          'm': KdfParams.owaspMobile2024.memoryKiB,
          't': KdfParams.owaspMobile2024.iterations,
          'p': KdfParams.owaspMobile2024.parallelism,
          'salt': saltB64,
        },
        'cipher': {
          'nonce': base64Encode(res.nonce),
          // ciphertext || tag concaténés (compat AeadResult.cipherAndTag).
          'data': base64Encode(res.cipherAndTag),
        },
        'count': entries.length,
        'exportedAt': DateTime.now().toIso8601String(),
      });
    } finally {
      if (key != null) SecretBytes.wipe(key);
    }
  }

  /// Returns null if passphrase wrong / file corrupted.
  static Future<List<Entry>?> importEncrypted(
    String fileContent,
    String passphrase,
  ) async {
    try {
      if (fileContent.length > _maxImportBytes) return null;
      final json = jsonDecode(fileContent) as Map<String, dynamic>;
      if (json['magic'] != 'PTBAK') return null;

      final version = json['version'] as int? ?? 1;
      // M-9 : borne stricte de la version pour rejeter les .ptbak forgés avec
      // une version inconnue (sinon branche future potentiellement traversée
      // sur fichier malicieux).
      if (version < 1 || version > _backupVersionV3) return null;

      // ── v3 path (Argon2id + AES-GCM) ─────────────────────────────────────
      if (version == _backupVersionV3) {
        final kdf = json['kdf'];
        final cipher = json['cipher'];
        if (kdf is! Map || cipher is! Map) return null;
        if (kdf['algo'] != 'argon2id') return null;
        final m = kdf['m'] as int? ?? 0;
        final t = kdf['t'] as int? ?? 0;
        final p = kdf['p'] as int? ?? 0;
        // F1 v2.4.3 — bornes strictes anti-DoS sur params Argon2 forgés
        // (un fichier malicieux pourrait spécifier m=1Go t=64 pour épuiser
        // la RAM/CPU au déchiffrement).
        if (m < 4096 || m > 1024 * 1024) return null;
        if (t < 1 || t > 16) return null;
        if (p < 1 || p > 4) return null;
        final saltB64 = kdf['salt'] as String? ?? '';
        final salt = base64Decode(saltB64);
        if (salt.length < 16) return null;
        final nonce = base64Decode(cipher['nonce'] as String);
        final dataBlob = base64Decode(cipher['data'] as String);
        if (dataBlob.length < AeadService.tagBytes) return null;
        final split = AeadService.splitCipherAndTag(dataBlob);
        Uint8List? key;
        try {
          key = await KdfService.argon2id(
            password: passphrase,
            salt: salt,
            params: KdfParams(
              memoryKiB: m,
              iterations: t,
              parallelism: p,
              outLen: 32,
            ),
          );
          final aad = Uint8List.fromList(_aadV3(saltB64));
          final plain = await AeadService.decryptGcm(
            key: key,
            nonce: nonce,
            ciphertext: split.ciphertext,
            tag: split.tag,
            aad: aad,
          );
          if (plain == null) return null;
          final list = jsonDecode(utf8.decode(plain)) as List;
          final entries = <Entry>[];
          for (final item in list) {
            try {
              entries.add(Entry.fromJson(item as Map<String, dynamic>));
            } catch (_) {}
          }
          // Wipe plaintext bytes après parsing JSON.
          SecretBytes.wipe(plain);
          return entries;
        } finally {
          if (key != null) SecretBytes.wipe(key);
        }
      }

      // ── v1/v2 path legacy (PBKDF2 + AES-CBC + HMAC-SHA256) ────────────────
      final iterations = json['iterations'] as int? ?? _backupIterations;
      if (iterations < 1 || iterations > _maxBackupIterations) return null;

      final saltB64 = json['salt'] as String;
      final salt = base64Decode(saltB64);
      final iv = base64Decode(json['iv'] as String);
      final mac = base64Decode(json['mac'] as String);
      final cipher = base64Decode(json['data'] as String);

      // F9 v2.4.4 — borne stricte sur la longueur du MAC AVANT le compute.
      // `SecretBytes.constantTimeEq` retourne tôt sur length-mismatch
      // (commentaire M-2 ci-dessous), donc un .ptbak v1/v2 forgé avec
      // `mac="AAA="` (3 octets) court-circuitait immédiatement la
      // vérification sans même calculer HMAC. Inoffensif tant que les
      // autres vérifications restent strictes (le decrypt AES-CBC échouera
      // de toute façon), mais on évite d'exposer un oracle de longueur
      // côté API (un futur appelant pourrait s'y fier).
      if (mac.length != 32) return null;

      final key = await compute(pbkdf2Worker, [
        utf8.encode(passphrase),
        salt,
        iterations,
        64,
      ]);
      // M-3 : zéroïser key + sublists après usage.
      final macKey = key.sublist(32);
      Uint8List? encKeyBytes;
      try {
        // v2+: MAC covers metadata. v1 (legacy): MAC over IV||cipher only.
        final List<int> macInput;
        if (version >= 2) {
          final aad = utf8.encode(
            'ptbak:v=$version|iter=$iterations|salt=$saltB64',
          );
          macInput = [...aad, ...iv, ...cipher];
        } else {
          macInput = [...iv, ...cipher];
        }
        final computed = Hmac(sha256, macKey).convert(macInput).bytes;
        if (!SecretBytes.constantTimeEq(computed, mac)) return null;

        encKeyBytes = key.sublist(0, 32);
        final encKey = enc.Key(encKeyBytes);
        final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
        final plain = encrypter.decrypt(
          enc.Encrypted(Uint8List.fromList(cipher)),
          iv: enc.IV(Uint8List.fromList(iv)),
        );

        final list = jsonDecode(plain) as List;
        final entries = <Entry>[];
        for (final item in list) {
          try {
            entries.add(Entry.fromJson(item as Map<String, dynamic>));
          } catch (_) {}
        }
        return entries;
      } finally {
        SecretBytes.wipe(macKey);
        if (encKeyBytes != null) SecretBytes.wipe(encKeyBytes);
        SecretBytes.wipe(key);
      }
    } catch (_) {
      return null;
    }
  }

  // v2.2.0 — shims locaux supprimés. Les callsites utilisent `SecretBytes.*`
  // directement (cf. files_tech_core 0.3.0).
  //
  // M-2 : `SecretBytes.constantTimeEq` retourne tôt sur length mismatch.
  // Inoffensif ici (HMAC-SHA256, 32 octets fixes). Ne PAS l'utiliser pour
  // comparer des secrets de longueur variable.
}
