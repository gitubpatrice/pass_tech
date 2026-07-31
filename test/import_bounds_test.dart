import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/models/entry.dart';
import 'package:pass_tech/services/import_export_service.dart';

/// Candidats 2 et 3 du rapport claude-security, ecartes en triage donc NI
/// confirmes NI infirmes. Instruits le 2026-07-31.
void main() {
  group('candidat 2 — l\'id d\'entree n\'est PAS pilotable par le fichier', () {
    test('deux entrees de meme titre recoivent des id DISTINCTS', () {
      // Le constructeur genere `id ?? _uuid.v4()`, et AUCUN des 4 sites
      // d'import ne passe `id:`. Un fichier hostile ne peut donc pas fixer
      // l'id d'une entree, ni provoquer une collision avec une entree
      // existante. Candidat REFUTE — pour une raison plus forte que celle
      // avancee par le refuteur d'origine (« les UUID sont indevinables ») :
      // le champ n'est simplement jamais lu du fichier.
      final a = Entry(title: 'Compte', category: 'Web');
      final b = Entry(title: 'Compte', category: 'Web');
      expect(a.id, isNot(b.id));
      expect(a.id, isNotEmpty);
    });

    test('un id explicite reste possible en interne (restauration .ptbak)', () {
      final e = Entry(id: 'fixe-123', title: 'X', category: 'Web');
      expect(e.id, 'fixe-123');
    });
  });

  group('candidat 3 — le secret TOTP importe est borne', () {
    test('la borne est exposee et raisonnable', () {
      // Un secret TOTP reel fait quelques dizaines de caracteres (RFC 6238 :
      // 160 bits en base32 = 32 caracteres).
      expect(ImportExportService.maxTotpSecretCharsForTest, 512);
    });

    test('un secret normal passe INTACT', () {
      const s = 'JBSWY3DPEHPK3PXP';
      expect(ImportExportService.boundTotpForTest(s), s);
    });

    test('un secret aberrant est TRONQUE, pas rejete', () {
      // On tronque plutot que d'echouer : un import ne doit pas casser en
      // bloc a cause d'un seul champ. Sans cette borne, seul le cap global de
      // 50 Mo s'appliquait cote JSON (le cap de 64 Ko ne couvre que le CSV),
      // et le decodage base32 se faisait SUR L'ISOLAT D'INTERFACE.
      final enorme = 'A' * (2 * 1024 * 1024);
      final borne = ImportExportService.boundTotpForTest(enorme);
      expect(borne.length, 512);
      expect(enorme.length, greaterThan(borne.length));
    });

    test('chaine vide et cas limites', () {
      expect(ImportExportService.boundTotpForTest(''), '');
      expect(ImportExportService.boundTotpForTest('A' * 512).length, 512);
      expect(ImportExportService.boundTotpForTest('A' * 513).length, 512);
    });
  });
}
