// Tests de durcissement post-audit 2026-05-03 :
// - M-8 : cap par cellule CSV (DoS protection)
// - M-9 : borne de version pour les fichiers .ptbak

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/import_export_service.dart';

void main() {
  group('M-8 — CSV cell cap', () {
    test('CSV avec cellule géante (>64 KB) est rejeté proprement', () {
      // Header valide, puis 1 ligne avec une cellule "name" de 100 KB.
      final bigCell = 'A' * (100 * 1024);
      final csv = 'name,password\n"$bigCell",secret123\n';
      final result = ImportExportService.parse(csv);
      expect(result.entries, isEmpty);
      expect(result.error, isNotNull);
      expect(result.error, contains('CSV invalide'));
    });

    test('CSV normal n\'est pas affecté par le cap', () {
      const csv =
          'name,username,password\n'
          'Gmail,alice@example.com,p4ssw0rd\n'
          'GitHub,alice,t0ken123\n';
      final result = ImportExportService.parse(csv);
      expect(result.entries, hasLength(2));
      expect(result.error, isNull);
    });

    test('Cellule pile sous la limite (~64 KB) passe', () {
      final cellOk = 'B' * (60 * 1024);
      final csv = 'name,password\nx,$cellOk\n';
      final result = ImportExportService.parse(csv);
      expect(result.entries, hasLength(1));
    });
  });

  group('M-9 — version bornée à l\'import .ptbak', () {
    test('version 99999 inconnue est rejetée (retour null)', () async {
      final fake = jsonEncode({
        'magic': 'PTBAK',
        'version': 99999,
        'iterations': 600000,
        'salt': 'AAAA',
        'iv': 'AAAA',
        'mac': 'AAAA',
        'data': 'AAAA',
      });
      final result = await ImportExportService.importEncrypted(fake, 'pwd');
      expect(result, isNull);
    });

    test('version 0 est rejetée', () async {
      final fake = jsonEncode({
        'magic': 'PTBAK',
        'version': 0,
        'iterations': 600000,
        'salt': 'AAAA',
        'iv': 'AAAA',
        'mac': 'AAAA',
        'data': 'AAAA',
      });
      final result = await ImportExportService.importEncrypted(fake, 'pwd');
      expect(result, isNull);
    });
  });
}
