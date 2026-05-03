// Migration v3 → v4 — round-trip test (no device, no Keystore, no plugin).
//
// We rebuild the migration pipeline from primitives so the test runs on the
// host VM:
//   1. Create a fake v3 vault file (PBKDF2 + AES-CBC + HMAC-SHA256) in memory.
//   2. Decrypt it with the v3 routine (mirroring vault_service `_decryptVaultV3`).
//   3. Run the v4 setup pipeline: Argon2id → wrap hwSecret with an
//      InMemoryKeystoreBackend → HKDF → AES-GCM encrypt new vault.
//   4. Decrypt the v4 vault, verify the entries survived the migration
//      byte-for-byte.
//   5. Negative path: tamper the v4 ciphertext, verify GCM tag check fails.
//
// The InMemoryKeystoreBackend exposes the same `wrap`/`unwrap` semantics as
// the real Kotlin bridge (AES-GCM-256, ciphertext||tag layout, fresh nonce
// per call). Production code paths that use VaultService.setKeystoreForTesting
// can substitute it directly to exercise the full _migrateV3ToV4 method on
// a device-less host once a path_provider/secure_storage harness is wired up.

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as cr;
import 'package:cryptography/cryptography.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/aead_service.dart';
import 'package:pass_tech/services/keystore_service.dart';

// ── v3 fixture generator (matches vault_service.dart _saveVault v3 logic) ──

Uint8List _pbkdf2(Uint8List password, Uint8List salt, int iter, int outLen) {
  final hmacGen = cr.Hmac(cr.sha256, password);
  const hLen = 32;
  final blocks = (outLen / hLen).ceil();
  final dk = BytesBuilder();
  for (var i = 1; i <= blocks; i++) {
    final saltI = Uint8List(salt.length + 4);
    saltI.setRange(0, salt.length, salt);
    saltI[salt.length] = (i >> 24) & 0xFF;
    saltI[salt.length + 1] = (i >> 16) & 0xFF;
    saltI[salt.length + 2] = (i >> 8) & 0xFF;
    saltI[salt.length + 3] = i & 0xFF;
    var u = Uint8List.fromList(hmacGen.convert(saltI).bytes);
    final t = Uint8List.fromList(u);
    for (var j = 1; j < iter; j++) {
      u = Uint8List.fromList(hmacGen.convert(u).bytes);
      for (var k = 0; k < t.length; k++) {
        t[k] ^= u[k];
      }
    }
    dk.add(t);
  }
  return dk.toBytes().sublist(0, outLen);
}

String _buildV3VaultFile({
  required String password,
  required Uint8List salt,
  required int iterations,
  required List<Map<String, dynamic>> entries,
}) {
  final key = _pbkdf2(
    Uint8List.fromList(utf8.encode(password)),
    salt,
    iterations,
    64,
  );
  final encKeyBytes = key.sublist(0, 32);
  final macKey = key.sublist(32);

  final iv = enc.IV.fromSecureRandom(16);
  final encrypter = enc.Encrypter(
    enc.AES(enc.Key(encKeyBytes), mode: enc.AESMode.cbc),
  );
  final plain = jsonEncode(entries);
  final encrypted = encrypter.encrypt(plain, iv: iv);

  const version = 3;
  final saltB64 = base64Encode(salt);
  final aad = utf8.encode('pt:v=$version|iter=$iterations|salt=$saltB64');
  final mac = cr.Hmac(
    cr.sha256,
    macKey,
  ).convert([...aad, ...iv.bytes, ...encrypted.bytes]).bytes;

  return jsonEncode(<String, dynamic>{
    'version': version,
    'iterations': iterations,
    'salt': saltB64,
    'iv': base64Encode(iv.bytes),
    'mac': base64Encode(mac),
    'data': base64Encode(encrypted.bytes),
  });
}

List<Map<String, dynamic>> _decryptV3VaultFile({
  required String password,
  required String fileJson,
  required Uint8List externalSalt,
}) {
  final raw = jsonDecode(fileJson) as Map<String, dynamic>;
  final iv = base64Decode(raw['iv'] as String);
  final macStored = base64Decode(raw['mac'] as String);
  final ct = base64Decode(raw['data'] as String);
  final iter = raw['iterations'] as int;
  final saltB64 = raw['salt'] as String;

  final key = _pbkdf2(
    Uint8List.fromList(utf8.encode(password)),
    externalSalt,
    iter,
    64,
  );
  final encKey = key.sublist(0, 32);
  final macKey = key.sublist(32);

  final aad = utf8.encode('pt:v=3|iter=$iter|salt=$saltB64');
  final macComputed = cr.Hmac(
    cr.sha256,
    macKey,
  ).convert([...aad, ...iv, ...ct]).bytes;
  if (!_constEq(macComputed, macStored)) {
    throw StateError('v3 MAC mismatch');
  }
  final encrypter = enc.Encrypter(
    enc.AES(enc.Key(encKey), mode: enc.AESMode.cbc),
  );
  final plain = encrypter.decrypt(
    enc.Encrypted(Uint8List.fromList(ct)),
    iv: enc.IV(Uint8List.fromList(iv)),
  );
  return (jsonDecode(plain) as List).cast<Map<String, dynamic>>();
}

bool _constEq(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  var d = 0;
  for (var i = 0; i < a.length; i++) {
    d |= a[i] ^ b[i];
  }
  return d == 0;
}

// ── v4 pipeline ────────────────────────────────────────────────────────────

const int _argon2M = 19456;
const int _argon2T = 2;
const int _argon2P = 1;

Future<Uint8List> _argon2id(String pwd, Uint8List salt) async {
  // Smaller cost for tests (m=512 KiB) — algorithm correctness, not
  // production hardening, is what we're validating here.
  final algo = Argon2id(
    memory: 512,
    parallelism: 1,
    iterations: 2,
    hashLength: 32,
  );
  final sk = await algo.deriveKey(
    secretKey: SecretKey(Uint8List.fromList(utf8.encode(pwd))),
    nonce: salt,
  );
  return Uint8List.fromList(await sk.extractBytes());
}

Future<Uint8List> _hkdf(Uint8List salt, Uint8List ikm) async {
  final hkdf = Hkdf(hmac: Hmac.sha256(), outputLength: 32);
  final out = await hkdf.deriveKey(
    secretKey: SecretKey(ikm),
    nonce: salt,
    info: utf8.encode('pt:v4'),
  );
  return Uint8List.fromList(await out.extractBytes());
}

Uint8List _aadV4(String alias) => Uint8List.fromList(
  utf8.encode(
    'pt:v=4|alias=$alias|kdf=argon2id|m=$_argon2M|t=$_argon2T|p=$_argon2P',
  ),
);

Future<String> _writeV4({
  required String password,
  required Uint8List salt,
  required Uint8List wrappedDek,
  required Uint8List wrapNonce,
  required Uint8List hwSecret,
  required String alias,
  required List<Map<String, dynamic>> entries,
}) async {
  final pwHash = await _argon2id(password, salt);
  final ikm = Uint8List(pwHash.length + hwSecret.length);
  ikm.setRange(0, pwHash.length, pwHash);
  ikm.setRange(pwHash.length, ikm.length, hwSecret);
  final finalKey = await _hkdf(salt, ikm);

  final aad = _aadV4(alias);
  final aead = await AeadService.encryptGcm(
    key: finalKey,
    plaintext: Uint8List.fromList(utf8.encode(jsonEncode(entries))),
    aad: aad,
  );

  return jsonEncode(<String, dynamic>{
    'magic': 'PTVAULT',
    'version': 4,
    'kdf': {
      'algo': 'argon2id',
      'm': _argon2M,
      't': _argon2T,
      'p': _argon2P,
      'salt': base64Encode(salt),
    },
    'kek': {
      'algo': 'AES-GCM-256',
      'alias': alias,
      'wrappedDek': base64Encode(wrappedDek),
      'wrapNonce': base64Encode(wrapNonce),
    },
    'cipher': {
      'algo': 'AES-GCM-256',
      'nonce': base64Encode(aead.nonce),
      'data': base64Encode(aead.cipherAndTag),
      'aad': utf8.decode(aad),
    },
  });
}

Future<List<Map<String, dynamic>>?> _readV4({
  required String password,
  required String fileJson,
  required KeystoreBackend keystore,
}) async {
  final raw = jsonDecode(fileJson) as Map<String, dynamic>;
  if (raw['magic'] != 'PTVAULT' || raw['version'] != 4) return null;
  final kdf = raw['kdf'] as Map<String, dynamic>;
  final kek = raw['kek'] as Map<String, dynamic>;
  final cipher = raw['cipher'] as Map<String, dynamic>;
  final salt = base64Decode(kdf['salt'] as String);
  final alias = kek['alias'] as String;
  final wrappedDek = base64Decode(kek['wrappedDek'] as String);
  final wrapNonce = base64Decode(kek['wrapNonce'] as String);
  final dataBlob = base64Decode(cipher['data'] as String);
  final nonce = base64Decode(cipher['nonce'] as String);
  final aad = _aadV4(alias);

  final hwSecret = await keystore.unwrap(alias, wrappedDek, wrapNonce);
  final pwHash = await _argon2id(password, salt);
  final ikm = Uint8List(pwHash.length + hwSecret.length);
  ikm.setRange(0, pwHash.length, pwHash);
  ikm.setRange(pwHash.length, ikm.length, hwSecret);
  final finalKey = await _hkdf(salt, ikm);

  final split = AeadService.splitCipherAndTag(dataBlob);
  final pt = await AeadService.decryptGcm(
    key: finalKey,
    nonce: nonce,
    ciphertext: split.ciphertext,
    tag: split.tag,
    aad: aad,
  );
  if (pt == null) return null;
  return (jsonDecode(utf8.decode(pt)) as List).cast<Map<String, dynamic>>();
}

void main() {
  group('Migration v3 → v4 round-trip (in-memory)', () {
    test(
      'v3 vault → migrate → v4 vault → decrypt yields same entries',
      () async {
        // 1. Build a v3 vault on disk-equivalent (in-memory string).
        const password = 'correct horse battery staple';
        final v3Salt = Uint8List.fromList(List<int>.generate(32, (i) => i + 1));
        final originalEntries = <Map<String, dynamic>>[
          {
            'id': '1',
            'title': 'GitHub',
            'username': 'alice',
            'password': 'hunter2',
          },
          {
            'id': '2',
            'title': 'Email',
            'username': 'alice@x.com',
            'password': 'p@ss!',
          },
        ];
        // Use a low iteration count to keep the test fast. The algorithm path
        // is identical regardless of iter count.
        final v3File = _buildV3VaultFile(
          password: password,
          salt: v3Salt,
          iterations: 1000,
          entries: originalEntries,
        );

        // 2. Decrypt v3.
        final decrypted = _decryptV3VaultFile(
          password: password,
          fileJson: v3File,
          externalSalt: v3Salt,
        );
        expect(decrypted, equals(originalEntries));

        // 3. Migrate to v4.
        final keystore = KeystoreService(backend: InMemoryKeystoreBackend());
        await keystore.ensureBothKeksExist();
        expect(await keystore.hasKek(KeystoreAliases.primary), isTrue);
        expect(await keystore.hasKek(KeystoreAliases.decoy), isTrue);

        final newSalt = Uint8List.fromList(
          List<int>.generate(32, (i) => i + 100),
        );
        final hwSecret = Uint8List.fromList(
          List<int>.generate(32, (i) => i + 50),
        );
        final wrap = await keystore.wrap(KeystoreAliases.primary, hwSecret);

        final v4File = await _writeV4(
          password: password,
          salt: newSalt,
          wrappedDek: wrap.ciphertext,
          wrapNonce: wrap.nonce,
          hwSecret: hwSecret,
          alias: KeystoreAliases.primary,
          entries: decrypted,
        );

        // 4. Read v4 back, verify entries match.
        final reread = await _readV4(
          password: password,
          fileJson: v4File,
          keystore: keystore.backend,
        );
        expect(reread, isNotNull);
        expect(reread, equals(originalEntries));
      },
    );

    test(
      'v4 vault with wrong password returns null (no plaintext leak)',
      () async {
        final keystore = KeystoreService(backend: InMemoryKeystoreBackend());
        await keystore.ensureBothKeksExist();
        final salt = Uint8List.fromList(List<int>.generate(32, (i) => i));
        final hw = Uint8List.fromList(List<int>.generate(32, (i) => 0xAA));
        final wrap = await keystore.wrap(KeystoreAliases.primary, hw);
        final file = await _writeV4(
          password: 'right',
          salt: salt,
          wrappedDek: wrap.ciphertext,
          wrapNonce: wrap.nonce,
          hwSecret: hw,
          alias: KeystoreAliases.primary,
          entries: const [
            {'id': 'x', 'title': 't'},
          ],
        );
        final out = await _readV4(
          password: 'wrong',
          fileJson: file,
          keystore: keystore.backend,
        );
        expect(out, isNull);
      },
    );

    test('tampered v4 cipher.data is rejected by GCM tag', () async {
      final keystore = KeystoreService(backend: InMemoryKeystoreBackend());
      await keystore.ensureBothKeksExist();
      final salt = Uint8List.fromList(List<int>.generate(32, (i) => i));
      final hw = Uint8List.fromList(List<int>.generate(32, (i) => 0x33));
      final wrap = await keystore.wrap(KeystoreAliases.primary, hw);
      final file = await _writeV4(
        password: 'pwd',
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
        hwSecret: hw,
        alias: KeystoreAliases.primary,
        entries: const [
          {'id': 'x'},
        ],
      );

      // Flip a byte in cipher.data.
      final raw = jsonDecode(file) as Map<String, dynamic>;
      final cipher = raw['cipher'] as Map<String, dynamic>;
      final blob = base64Decode(cipher['data'] as String);
      blob[0] ^= 0xff;
      cipher['data'] = base64Encode(blob);
      final tampered = jsonEncode(raw);

      final out = await _readV4(
        password: 'pwd',
        fileJson: tampered,
        keystore: keystore.backend,
      );
      expect(out, isNull);
    });
  });

  group('InMemoryKeystoreBackend', () {
    test('createKek is idempotent', () async {
      final ks = InMemoryKeystoreBackend();
      expect(await ks.createKek('a'), isTrue);
      expect(await ks.createKek('a'), isFalse);
      expect(await ks.hasKek('a'), isTrue);
    });

    test('wrap then unwrap returns original plaintext', () async {
      final ks = InMemoryKeystoreBackend();
      await ks.createKek('alias');
      final pt = Uint8List.fromList(List<int>.generate(32, (i) => i));
      final wrap = await ks.wrap('alias', pt);
      final back = await ks.unwrap('alias', wrap.ciphertext, wrap.nonce);
      expect(back, equals(pt));
    });

    test('unwrap with tampered nonce throws', () async {
      final ks = InMemoryKeystoreBackend();
      await ks.createKek('alias');
      final pt = Uint8List.fromList([1, 2, 3, 4, 5]);
      final wrap = await ks.wrap('alias', pt);
      final badNonce = Uint8List.fromList(wrap.nonce);
      badNonce[0] ^= 0xff;
      expect(
        () => ks.unwrap('alias', wrap.ciphertext, badNonce),
        throwsA(isA<Object>()),
      );
    });

    test('deleteKek removes it', () async {
      final ks = InMemoryKeystoreBackend();
      await ks.createKek('a');
      await ks.deleteKek('a');
      expect(await ks.hasKek('a'), isFalse);
    });
  });
}
