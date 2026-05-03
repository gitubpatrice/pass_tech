// Tests crypto contre vecteurs publics connus. Garde-fou contre une régression
// silencieuse de pointycastle / encrypt qui rendrait les vaults illisibles ou
// faiblement chiffrés sans qu'on s'en aperçoive.

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/vault_service.dart' show pbkdf2Worker;

String _hex(List<int> bytes) =>
    bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();

List<int> _fromHex(String hex) {
  final out = <int>[];
  for (int i = 0; i < hex.length; i += 2) {
    out.add(int.parse(hex.substring(i, i + 2), radix: 16));
  }
  return out;
}

void main() {
  group('PBKDF2-HMAC-SHA256 (RFC 7914 §11)', () {
    test('passwd "passwd" / salt "salt" / 1 iter / 64B', () {
      final dk = pbkdf2Worker([
        utf8.encode('passwd'),
        utf8.encode('salt'),
        1,
        64,
      ]);
      const expected =
          '55ac046e56e3089fec1691c22544b605'
          'f94185216dde0465e68b9d57c20dacbc'
          '49ca9cccf179b645991664b39d77ef31'
          '7c71b845b1e30bd509112041d3a19783';
      expect(_hex(dk), expected);
    });

    test('passwd "Password" / salt "NaCl" / 80 000 iter / 64B', () {
      final dk = pbkdf2Worker([
        utf8.encode('Password'),
        utf8.encode('NaCl'),
        80000,
        64,
      ]);
      const expected =
          '4ddcd8f60b98be21830cee5ef22701f9'
          '641a4418d04c0414aeff08876b34ab56'
          'a1d425a1225833549adb841b51c9b317'
          '6a272bdebba1d078478f62b397f33c8d';
      expect(_hex(dk), expected);
    });

    test('PBKDF2 deterministic — same input → same output', () {
      final a = pbkdf2Worker([
        utf8.encode('mypass'),
        utf8.encode('s'),
        1000,
        32,
      ]);
      final b = pbkdf2Worker([
        utf8.encode('mypass'),
        utf8.encode('s'),
        1000,
        32,
      ]);
      expect(_hex(a), _hex(b));
    });

    test('PBKDF2 changes with salt', () {
      final a = pbkdf2Worker([
        utf8.encode('pass'),
        utf8.encode('salt1'),
        100,
        32,
      ]);
      final b = pbkdf2Worker([
        utf8.encode('pass'),
        utf8.encode('salt2'),
        100,
        32,
      ]);
      expect(_hex(a), isNot(equals(_hex(b))));
    });

    test('PBKDF2 changes with iterations', () {
      final a = pbkdf2Worker([
        utf8.encode('pass'),
        utf8.encode('salt'),
        100,
        32,
      ]);
      final b = pbkdf2Worker([
        utf8.encode('pass'),
        utf8.encode('salt'),
        200,
        32,
      ]);
      expect(_hex(a), isNot(equals(_hex(b))));
    });
  });

  group('HMAC-SHA256 (RFC 4231)', () {
    test('test case 1 — key 20×0x0b / data "Hi There"', () {
      final key = List<int>.filled(20, 0x0b);
      final data = utf8.encode('Hi There');
      final mac = Hmac(sha256, key).convert(data).bytes;
      expect(
        _hex(mac),
        'b0344c61d8db38535ca8afceaf0bf12b'
        '881dc200c9833da726e9376c2e32cff7',
      );
    });

    test('test case 2 — key "Jefe" / data "what do ya want for nothing?"', () {
      final key = utf8.encode('Jefe');
      final data = utf8.encode('what do ya want for nothing?');
      final mac = Hmac(sha256, key).convert(data).bytes;
      expect(
        _hex(mac),
        '5bdcc146bf60754e6a042426089575c7'
        '5a003f089d2739839dec58b964ec3843',
      );
    });

    test('test case 4 — key 0x01..0x19 / data 50×0xcd', () {
      final key = List<int>.generate(25, (i) => i + 1);
      final data = List<int>.filled(50, 0xcd);
      final mac = Hmac(sha256, key).convert(data).bytes;
      expect(
        _hex(mac),
        '82558a389a443c0ea4cc819899f2083a'
        '85f0faa3e578f8077a2e3ff46729665b',
      );
    });
  });

  group('AES-256-CBC round-trip', () {
    test('encrypt then decrypt yields plaintext', () {
      final key = enc.Key(Uint8List.fromList(List<int>.generate(32, (i) => i)));
      final iv = enc.IV(Uint8List.fromList(List<int>.generate(16, (i) => i)));
      final encrypter = enc.Encrypter(enc.AES(key, mode: enc.AESMode.cbc));
      const plain = 'Pass Tech vault payload — éàü ©';
      final ct = encrypter.encrypt(plain, iv: iv);
      expect(ct.bytes.length, greaterThan(0));
      final dec = encrypter.decrypt(ct, iv: iv);
      expect(dec, plain);
    });

    test(
      'different IVs produce different ciphertext for same key+plaintext',
      () {
        final key = enc.Key(
          Uint8List.fromList(List<int>.generate(32, (i) => i)),
        );
        final iv1 = enc.IV.fromSecureRandom(16);
        final iv2 = enc.IV.fromSecureRandom(16);
        final encrypter = enc.Encrypter(enc.AES(key, mode: enc.AESMode.cbc));
        final ct1 = encrypter.encrypt('hello world', iv: iv1);
        final ct2 = encrypter.encrypt('hello world', iv: iv2);
        expect(ct1.base64, isNot(equals(ct2.base64)));
      },
    );

    test('wrong key fails to decrypt cleanly', () {
      final keyOk = enc.Key(
        Uint8List.fromList(List<int>.generate(32, (i) => i)),
      );
      final keyBad = enc.Key(
        Uint8List.fromList(List<int>.generate(32, (i) => i + 1)),
      );
      final iv = enc.IV(Uint8List.fromList(List<int>.generate(16, (i) => i)));
      final ct = enc.Encrypter(
        enc.AES(keyOk, mode: enc.AESMode.cbc),
      ).encrypt('secret', iv: iv);
      expect(
        () => enc.Encrypter(
          enc.AES(keyBad, mode: enc.AESMode.cbc),
        ).decrypt(ct, iv: iv),
        throwsA(anything),
      );
    });
  });

  group('Encrypt-then-MAC pattern (used by vault_service)', () {
    test('PBKDF2 → AES-CBC encrypt → HMAC verify → AES-CBC decrypt', () {
      final salt = List<int>.generate(32, (i) => i);
      final key = pbkdf2Worker([utf8.encode('master'), salt, 1000, 64]);
      final encKey = enc.Key(Uint8List.fromList(key.sublist(0, 32)));
      final macKey = key.sublist(32);
      final iv = enc.IV.fromSecureRandom(16);
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));

      const plain = '[{"id":"1","title":"GitHub","password":"hunter2"}]';
      final ct = encrypter.encrypt(plain, iv: iv);
      final mac = Hmac(
        sha256,
        macKey,
      ).convert([...iv.bytes, ...ct.bytes]).bytes;

      // Vérification HMAC AVANT déchiffrement.
      final computed = Hmac(
        sha256,
        macKey,
      ).convert([...iv.bytes, ...ct.bytes]).bytes;
      expect(_hex(computed), _hex(mac));

      final dec = encrypter.decrypt(ct, iv: iv);
      expect(dec, plain);
    });

    test('tampered ciphertext is detected by HMAC', () {
      final salt = List<int>.generate(32, (i) => i);
      final key = pbkdf2Worker([utf8.encode('master'), salt, 1000, 64]);
      final macKey = key.sublist(32);
      final iv = enc.IV.fromSecureRandom(16);
      final encKey = enc.Key(Uint8List.fromList(key.sublist(0, 32)));
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));

      final ct = encrypter.encrypt('secret data', iv: iv);
      final mac = Hmac(
        sha256,
        macKey,
      ).convert([...iv.bytes, ...ct.bytes]).bytes;

      final tampered = Uint8List.fromList(ct.bytes);
      tampered[0] ^= 0xff;
      final computed = Hmac(
        sha256,
        macKey,
      ).convert([...iv.bytes, ...tampered]).bytes;
      expect(_hex(computed), isNot(equals(_hex(mac))));
    });
  });

  group('Hex helpers (sanity)', () {
    test('round trip _hex / _fromHex', () {
      const sample = 'deadbeef0123456789abcdef';
      expect(_hex(_fromHex(sample)), sample);
    });
  });
}
