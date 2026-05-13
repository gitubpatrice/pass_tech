// F1 v2.4.3 — Tests round-trip .ptbak v3 (Argon2id + AES-GCM)
// + acceptation lecture v1/v2 legacy + refus params Argon2 forgés.

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/models/entry.dart';
import 'package:pass_tech/services/import_export_service.dart';

void main() {
  group('.ptbak v3 — round-trip Argon2id + AES-GCM', () {
    test('export/import preserve entries (1 entry)', () async {
      final entries = [
        Entry(
          type: EntryType.password,
          title: 'Test',
          category: 'Web',
          username: 'alice',
          password: 'p@ss-w0rd',
          url: 'https://example.com',
          notes: 'note',
        ),
      ];
      final exported = await ImportExportService.exportEncrypted(
        entries,
        'my-strong-passphrase-12chars',
      );
      final restored = await ImportExportService.importEncrypted(
        exported,
        'my-strong-passphrase-12chars',
      );
      expect(restored, isNotNull);
      expect(restored!.length, 1);
      expect(restored.first.title, 'Test');
      expect(restored.first.password, 'p@ss-w0rd');
      expect(restored.first.url, 'https://example.com');
    });

    test('export/import preserve entries (multiple types)', () async {
      final entries = [
        Entry(type: EntryType.password, title: 'P', category: 'Web'),
        Entry(type: EntryType.note, title: 'N', category: 'Autres', notes: 'x'),
        Entry(
          type: EntryType.card,
          title: 'C',
          category: 'Banque',
          cardNumber: '4111111111111111',
        ),
      ];
      final exported = await ImportExportService.exportEncrypted(
        entries,
        'passphrase-1234-5678',
      );
      final restored = await ImportExportService.importEncrypted(
        exported,
        'passphrase-1234-5678',
      );
      expect(restored, isNotNull);
      expect(restored!.length, 3);
      expect(restored.map((e) => e.type).toList(), [
        EntryType.password,
        EntryType.note,
        EntryType.card,
      ]);
    });

    test('wrong passphrase returns null (AEAD tag check)', () async {
      final exported = await ImportExportService.exportEncrypted([
        Entry(type: EntryType.password, title: 'T', category: 'Web'),
      ], 'correct-horse-battery-staple');
      final restored = await ImportExportService.importEncrypted(
        exported,
        'wrong-passphrase',
      );
      expect(restored, isNull);
    });

    test('rejects empty content', () async {
      expect(await ImportExportService.importEncrypted('', 'pwd'), isNull);
    });

    test('rejects non-PTBAK JSON', () async {
      expect(
        await ImportExportService.importEncrypted('{"magic":"NOT_PTBAK"}', 'p'),
        isNull,
      );
    });

    test('rejects forged Argon2 params (memory too large)', () async {
      // Forge un .ptbak v3 avec m=2Go (au-dessus de la borne 1Go) — refus.
      const forged =
          '{'
          '"magic":"PTBAK","version":3,'
          '"kdf":{"algo":"argon2id","m":2097152,"t":2,"p":1,"salt":"AAAAAAAAAAAAAAAAAAAAAA=="},'
          '"cipher":{"nonce":"AAAAAAAAAAAAAAAA","data":"AAAAAAAAAAAAAAAAAAAAAA=="}'
          '}';
      expect(await ImportExportService.importEncrypted(forged, 'p'), isNull);
    });

    test('rejects forged Argon2 params (iterations too large)', () async {
      const forged =
          '{'
          '"magic":"PTBAK","version":3,'
          '"kdf":{"algo":"argon2id","m":19456,"t":99999,"p":1,"salt":"AAAAAAAAAAAAAAAAAAAAAA=="},'
          '"cipher":{"nonce":"AAAAAAAAAAAAAAAA","data":"AAAAAAAAAAAAAAAAAAAAAA=="}'
          '}';
      expect(await ImportExportService.importEncrypted(forged, 'p'), isNull);
    });

    test('rejects forged KDF algo (not argon2id)', () async {
      const forged =
          '{'
          '"magic":"PTBAK","version":3,'
          '"kdf":{"algo":"bcrypt","m":19456,"t":2,"p":1,"salt":"AAAAAAAAAAAAAAAAAAAAAA=="},'
          '"cipher":{"nonce":"AAAAAAAAAAAAAAAA","data":"AAAAAAAAAAAAAAAAAAAAAA=="}'
          '}';
      expect(await ImportExportService.importEncrypted(forged, 'p'), isNull);
    });

    test('rejects unknown future version', () async {
      const forged = '{"magic":"PTBAK","version":99}';
      expect(await ImportExportService.importEncrypted(forged, 'p'), isNull);
    });

    test('rejects salt too short (< 16 bytes)', () async {
      const forged =
          '{'
          '"magic":"PTBAK","version":3,'
          '"kdf":{"algo":"argon2id","m":19456,"t":2,"p":1,"salt":"AAAA"},'
          '"cipher":{"nonce":"AAAAAAAAAAAAAAAA","data":"AAAAAAAAAAAAAAAAAAAAAA=="}'
          '}';
      expect(await ImportExportService.importEncrypted(forged, 'p'), isNull);
    });
  });
}
