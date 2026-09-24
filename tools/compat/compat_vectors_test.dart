// Cross-compatibility vectors between Pass Tech 2.7.1 (Flutter) and the Kotlin rewrite.
//
// This file is NOT part of the Kotlin build. It runs inside a checkout of the Flutter app at 2.7.1
// (git tag v2.7.1), because its whole point is to exercise the Flutter production code itself:
//
//   cp tools/compat/compat_vectors_test.dart <flutter-checkout>/test/
//   cd <flutter-checkout>
//   # 1. generate the vectors the Kotlin tests read
//   PT_COMPAT_DIR=<kotlin-checkout>/app/src/test/resources/compat/2.7.1 \
//     flutter test test/compat_vectors_test.dart --plain-name generate
//   # 2. after `./gradlew testDebugUnitTest` in the Kotlin checkout, import what it wrote
//   PT_COMPAT_FROM_KOTLIN=<kotlin-checkout>/app/build/compat/from-kotlin \
//     flutter test test/compat_vectors_test.dart --plain-name verify
//
// Two directions, two groups:
//
//  1. "generate" writes vectors produced by the 2.7.1 code into PT_COMPAT_DIR. The Kotlin unit tests
//     must read every one of them back, to the byte. The v3 backup goes through the real
//     `ImportExportService.exportEncrypted`. The v1 and v2 writers no longer exist in 2.7.1 (the
//     formats are read-only there), so they are rebuilt from their historical source (git 70afd66
//     and v2.2.0), and each rebuilt file is then imported by the 2.7.1 reader: a file that reader
//     accepts is, by definition, a valid legacy backup.
//
//  2. "verify" imports, with the 2.7.1 reader, every backup the KOTLIN app wrote into
//     PT_COMPAT_FROM_KOTLIN. This is the proof of the way back: a backup made by the Kotlin app
//     opens in the Flutter app.

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart' show Argon2id, SecretKey;
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/models/entry.dart';
import 'package:pass_tech/services/aead_service.dart';
import 'package:pass_tech/services/import_export_service.dart';
import 'package:pass_tech/services/kdf_service.dart';
import 'package:pass_tech/services/vault_service.dart' show pbkdf2Worker;

const passphrase = 'Correct horse — batterie agrafée ✓ 2026';

Directory _outDir() {
  final path = Platform.environment['PT_COMPAT_DIR'];
  if (path == null || path.isEmpty) {
    fail('PT_COMPAT_DIR is not set: nothing to generate into or verify from.');
  }
  return Directory(path)..createSync(recursive: true);
}

Uint8List _bytes(int length, int Function(int i) f) =>
    Uint8List.fromList(List.generate(length, f));

String _hex(List<int> b) =>
    b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();

/// Entries covering every type and the characters that break naive encoders: quotes, backslash,
/// control characters, astral-plane emoji, right-to-left text, a decomposed accent, and the three
/// shapes `DateTime.toIso8601String` produces (local ms, local µs, UTC).
List<Entry> _fixtureEntries() => [
  Entry(
    id: '0b1f4a52-7d0c-4c7e-9a51-3f7d2c9e8a01',
    type: EntryType.password,
    title: 'Banque — compte courant',
    category: 'Banque',
    username: 'patrice@example.fr',
    password: 'p@ss "wörd" \\ 😀 <tag> & \'q\'',
    url: 'https://www.example.fr/connexion?x=1&y=2',
    totpSecret: 'JBSWY3DPEHPK3PXP',
    notes: 'Ligne 1\nLigne 2\ttab\r\nfin \u0000 nul',
    isFavorite: true,
    createdAt: DateTime(2026, 9, 21, 19, 46, 12, 345),
    updatedAt: DateTime(2026, 9, 21, 19, 47, 0, 0, 123),
  ),
  Entry(
    id: '5c2e9b18-1a44-4f0b-8e2d-6b0f1c3d9e02',
    type: EntryType.note,
    title: 'Note sécurisée',
    category: 'Autres',
    notes: '中文 · العربية · é · 🜲 · ${'x' * 300}',
    createdAt: DateTime.utc(2025, 1, 2, 3, 4, 5),
    updatedAt: DateTime.utc(2025, 1, 2, 3, 4, 5, 6, 7),
  ),
  Entry(
    id: '9a7d3e61-2b58-4d19-b0c3-8e4f5a6b7c03',
    type: EntryType.card,
    title: 'Visa',
    category: 'Cartes',
    cardholderName: 'PATRICE H',
    cardNumber: '4970101234567890',
    cardExpiry: '09/28',
    cardCvv: '123',
    cardPin: '0000',
    cardIssuer: 'La Banque',
    notes: 'carte de secours',
    createdAt: DateTime(2024, 2, 29, 23, 59, 59, 999),
    updatedAt: DateTime(2024, 3, 1),
  ),
  Entry(
    id: 'e3c1b2a4-5d6e-4f70-8192-a3b4c5d6e704',
    title: 'Catégorie libre',
    category: 'Catégorie perso',
    createdAt: DateTime(2020, 1, 1),
    updatedAt: DateTime(2020, 1, 1),
  ),
];

List<Map<String, dynamic>> _json(List<Entry> entries) =>
    entries.map((e) => e.toJson()).toList();

/// Legacy writer, v1 (git 70afd66): PBKDF2 100k, AES-256-CBC, HMAC(iv || ciphertext).
/// The payload is the OLD entry schema, without type nor card fields: the 2.7.1 reader must fill
/// the defaults in.
Future<String> _writeV1(String plainJson, String pass) async {
  const iterations = 100000;
  final salt = _bytes(32, (i) => (i * 7 + 1) & 0xff);
  final key = pbkdf2Worker([utf8.encode(pass), salt, iterations, 64]);
  final iv = enc.IV(_bytes(16, (i) => (i * 13 + 5) & 0xff));
  final encrypter = enc.Encrypter(
    enc.AES(enc.Key(key.sublist(0, 32)), mode: enc.AESMode.cbc),
  );
  final encrypted = encrypter.encrypt(plainJson, iv: iv);
  final mac = Hmac(
    sha256,
    key.sublist(32),
  ).convert([...iv.bytes, ...encrypted.bytes]).bytes;
  return jsonEncode({
    'magic': 'PTBAK',
    'version': 1,
    'iterations': iterations,
    'salt': base64Encode(salt),
    'iv': base64Encode(iv.bytes),
    'mac': base64Encode(mac),
    'data': base64Encode(encrypted.bytes),
    'count': 2,
    'exportedAt': '2024-05-01T10:00:00.000',
  });
}

/// Legacy writer, v2 (git v2.2.0): PBKDF2 600k, AES-256-CBC, HMAC(aad || iv || ciphertext) with
/// aad = "ptbak:v=2|iter=N|salt=<base64>".
Future<String> _writeV2(List<Entry> entries, String pass) async {
  const iterations = 600000;
  final salt = _bytes(32, (i) => (i * 11 + 3) & 0xff);
  final key = pbkdf2Worker([utf8.encode(pass), salt, iterations, 64]);
  final iv = enc.IV(_bytes(16, (i) => (i * 17 + 9) & 0xff));
  final encrypter = enc.Encrypter(
    enc.AES(enc.Key(key.sublist(0, 32)), mode: enc.AESMode.cbc),
  );
  final plain = jsonEncode(_json(entries));
  final encrypted = encrypter.encrypt(plain, iv: iv);
  final saltB64 = base64Encode(salt);
  final aad = utf8.encode('ptbak:v=2|iter=$iterations|salt=$saltB64');
  final mac = Hmac(
    sha256,
    key.sublist(32),
  ).convert([...aad, ...iv.bytes, ...encrypted.bytes]).bytes;
  return jsonEncode({
    'magic': 'PTBAK',
    'version': 2,
    'iterations': iterations,
    'salt': saltB64,
    'iv': base64Encode(iv.bytes),
    'mac': base64Encode(mac),
    'data': base64Encode(encrypted.bytes),
    'count': entries.length,
    'exportedAt': '2025-06-01T10:00:00.000',
  });
}

/// A v3 backup with NON-default Argon2id parameters, built from the same primitives as
/// `exportEncrypted`. 2.7.1 reads m, t and p from the file (they are bound into the AAD); a reader
/// that silently used the defaults would open `ptbak_v3.ptbak` and fail on this one.
Future<String> _writeV3WithParams(
  List<Entry> entries,
  String pass,
  KdfParams params,
) async {
  final salt = _bytes(32, (i) => (i * 5 + 2) & 0xff);
  final saltB64 = base64Encode(salt);
  final key = await KdfService.argon2id(
    password: pass,
    salt: salt,
    params: params,
  );
  final aad = Uint8List.fromList(
    utf8.encode(
      'ptbak:v=3|kdf=argon2id|m=${params.memoryKiB}|t=${params.iterations}'
      '|p=${params.parallelism}|salt=$saltB64',
    ),
  );
  final res = await AeadService.encryptGcm(
    key: key,
    plaintext: utf8.encode(jsonEncode(_json(entries))),
    aad: aad,
  );
  return jsonEncode({
    'magic': 'PTBAK',
    'version': 3,
    'kdf': {
      'algo': 'argon2id',
      'm': params.memoryKiB,
      't': params.iterations,
      'p': params.parallelism,
      'salt': saltB64,
    },
    'cipher': {
      'nonce': base64Encode(res.nonce),
      'data': base64Encode(res.cipherAndTag),
    },
  });
}

Future<List<Map<String, dynamic>>> _importOrFail(
  String content,
  String pass,
) async {
  final imported = await ImportExportService.importEncrypted(content, pass);
  if (imported == null) fail('the 2.7.1 reader rejected the file');
  return _json(imported);
}

void main() {
  group('generate', () {
    test('RFC 9106 Argon2id vector, through the Dart implementation', () async {
      // RFC 9106 §5.3. If the Dart library disagrees with the RFC, every other vector below is
      // suspect, so this one runs first.
      final algo = Argon2id(
        memory: 32,
        parallelism: 4,
        iterations: 3,
        hashLength: 32,
      );
      final key = await algo.deriveKey(
        secretKey: SecretKey(List.filled(32, 0x01)),
        nonce: List.filled(16, 0x02),
        optionalSecret: List.filled(8, 0x03),
        associatedData: List.filled(12, 0x04),
      );
      expect(
        _hex(await key.extractBytes()),
        '0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659',
      );
    });

    test('Argon2id known answers, through KdfService (production path)', () async {
      final cases = <Map<String, dynamic>>[];
      final inputs = <(String, Uint8List, KdfParams)>[
        ('password', _bytes(32, (i) => i), KdfParams.owaspMobile2024),
        (passphrase, _bytes(16, (i) => 0xA0 + i), const KdfParams(
          memoryKiB: 4096, iterations: 1, parallelism: 1, outLen: 32)),
        ('', _bytes(32, (i) => 255 - i), const KdfParams(
          memoryKiB: 8192, iterations: 3, parallelism: 2, outLen: 32)),
      ];
      for (final (pw, salt, params) in inputs) {
        final out = await KdfService.argon2id(
          password: pw,
          salt: salt,
          params: params,
        );
        cases.add({
          'password': pw,
          'salt': base64Encode(salt),
          'm': params.memoryKiB,
          't': params.iterations,
          'p': params.parallelism,
          'outLen': params.outLen,
          'hex': _hex(out),
        });
      }
      File('${_outDir().path}/argon2id_kat.json').writeAsStringSync(
        const JsonEncoder.withIndent('  ').convert(cases),
      );
    });

    test('backups v1, v2, v3, readable by the 2.7.1 reader', () async {
      final dir = _outDir();
      final entries = _fixtureEntries();
      final expected = _json(entries);

      File('${dir.path}/entries.json').writeAsStringSync(jsonEncode(expected));

      // v3, the real export path.
      final v3 = await ImportExportService.exportEncrypted(entries, passphrase);
      expect(await _importOrFail(v3, passphrase), expected);
      File('${dir.path}/ptbak_v3.ptbak').writeAsStringSync(v3);

      // v3 with non-default parameters.
      final v3p = await _writeV3WithParams(
        entries,
        passphrase,
        const KdfParams(memoryKiB: 4096, iterations: 3, parallelism: 2, outLen: 32),
      );
      expect(await _importOrFail(v3p, passphrase), expected);
      File('${dir.path}/ptbak_v3_params.ptbak').writeAsStringSync(v3p);

      // v2, rebuilt legacy writer.
      final v2 = await _writeV2(entries, passphrase);
      expect(await _importOrFail(v2, passphrase), expected);
      File('${dir.path}/ptbak_v2.ptbak').writeAsStringSync(v2);

      // v1, rebuilt legacy writer, old schema: what 2.7.1 makes of it is the expected result.
      final v1Plain = jsonEncode([
        {
          'id': '11111111-2222-4333-8444-555555555501',
          'title': 'Ancienne entrée',
          'category': 'Web',
          'username': 'old@example.fr',
          'password': 'old-pass',
          'url': 'https://old.example.fr',
          'notes': 'v1',
          'isFavorite': false,
          'createdAt': '2024-04-01T08:00:00.000',
          'updatedAt': '2024-04-02T09:30:00.000',
        },
        {
          'id': '11111111-2222-4333-8444-555555555502',
          'title': 'Sans catégorie',
          'createdAt': '2024-04-03T08:00:00.000',
          'updatedAt': '2024-04-03T08:00:00.000',
        },
      ]);
      final v1 = await _writeV1(v1Plain, passphrase);
      final v1Imported = await _importOrFail(v1, passphrase);
      expect(v1Imported, hasLength(2));
      File('${dir.path}/ptbak_v1.ptbak').writeAsStringSync(v1);
      File('${dir.path}/ptbak_v1.expected.json').writeAsStringSync(
        jsonEncode(v1Imported),
      );

      // Negative controls: the same reader, the wrong passphrase.
      expect(await ImportExportService.importEncrypted(v3, '$passphrase!'), isNull);
      expect(await ImportExportService.importEncrypted(v2, '$passphrase!'), isNull);

      File('${dir.path}/README.md').writeAsStringSync(
        '# Vectors produced by Pass Tech 2.7.1\n\n'
        'Generated by `tools/compat/compat_vectors_test.dart` inside a 2.7.1 checkout. '
        'Do not edit by hand.\n\n'
        'Passphrase of every backup: `$passphrase`\n\n'
        '| File | Content |\n|---|---|\n'
        '| `entries.json` | `Entry.toJson` of the fixture entries: the expected result of every '
        'backup below except v1 |\n'
        '| `ptbak_v3.ptbak` | real `exportEncrypted` output (Argon2id 19456/2/1, AES-256-GCM) |\n'
        '| `ptbak_v3_params.ptbak` | v3 with m=4096 t=3 p=2, read from the file |\n'
        '| `ptbak_v2.ptbak` | legacy v2, PBKDF2 600k + AES-CBC + HMAC over aad, iv, data |\n'
        '| `ptbak_v1.ptbak` | legacy v1, PBKDF2 100k + AES-CBC + HMAC over iv, data, old schema |\n'
        '| `ptbak_v1.expected.json` | what 2.7.1 imports from `ptbak_v1.ptbak` |\n'
        '| `argon2id_kat.json` | Argon2id outputs of `KdfService.argon2id` |\n',
      );
    });
  });

  group('verify', () {
    test('backups written by the Kotlin app open in 2.7.1', () async {
      // Written by `PtbakCodecTest` under app/build/compat/from-kotlin of the Kotlin checkout.
      final path = Platform.environment['PT_COMPAT_FROM_KOTLIN'];
      if (path == null || path.isEmpty) {
        markTestSkipped('PT_COMPAT_FROM_KOTLIN is not set');
        return;
      }
      final dir = Directory(path);
      final files = dir
          .listSync()
          .whereType<File>()
          .where((f) => f.path.endsWith('.ptbak'))
          .toList();
      expect(files, isNotEmpty);
      for (final f in files) {
        final expectedFile = File(
          f.path.replaceFirst(RegExp(r'\.ptbak$'), '.expected.json'),
        );
        final expected = jsonDecode(expectedFile.readAsStringSync());
        expect(
          await _importOrFail(f.readAsStringSync(), passphrase),
          expected,
          reason: f.path,
        );
      }
    });
  });
}
