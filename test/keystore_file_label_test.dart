import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/keystore_service.dart';

/// SEC F18 — l'enveloppe du coffre portait l'alias d'ADRESSAGE du Keystore.
/// `pt_vault_b.enc` contenait donc litteralement `pt_vault_kek_decoy_v1` :
/// un `grep decoy` identifiait le leurre sans le moindre calcul, et les
/// 6 caracteres d'ecart avec `pt_vault_kek_v1` rendaient les deux fichiers
/// distinguables par un simple `ls -l` — ce que le rembourrage SEC F6 etait
/// pourtant cense supprimer.
///
/// Ces invariants sont silencieux : un refactor peut les casser sans qu'aucun
/// test fonctionnel ne bronche. D'ou ce fichier dedie.
void main() {
  group('etiquettes de fichier — indistinguabilite', () {
    test('LONGUEURS EGALES (sinon les tailles de fichier divergent)', () {
      expect(
        KeystoreAliases.fileLabelPrimary.length,
        KeystoreAliases.fileLabelDecoy.length,
        reason:
            'toute difference de longueur se retrouve octet pour octet '
            'dans la taille du fichier, et trahit le leurre',
      );
    });

    test('aucune etiquette ne contient de terme revelateur', () {
      for (final label in [
        KeystoreAliases.fileLabelPrimary,
        KeystoreAliases.fileLabelDecoy,
      ]) {
        final l = label.toLowerCase();
        for (final mot in [
          'decoy',
          'leurre',
          'fake',
          'dummy',
          'real',
          'primary',
          'principal',
          'main',
          'true',
        ]) {
          expect(
            l.contains(mot),
            isFalse,
            reason: '"$label" contient "$mot" — lisible en clair sur disque',
          );
        }
      }
    });

    test('les deux etiquettes RESTENT distinctes', () {
      // Volontaire : le nom de fichier (_a / _b) revele deja l'emplacement,
      // donc l'etiquette n'apprend rien de plus a un examinateur. En revanche
      // une etiquette COMMUNE detruirait la defense anti-copie inter-slots
      // de `_v4Unlock` (A3 v2.3.8), qui refuse un blob dont l'etiquette ne
      // correspond pas au slot tente.
      expect(
        KeystoreAliases.fileLabelPrimary,
        isNot(KeystoreAliases.fileLabelDecoy),
      );
    });

    test('etiquettes de fichier != alias d\'adressage Keystore', () {
      // Le decouplage est tout l'objet du correctif : si les deux
      // redevenaient egaux, l'alias d'adressage repartirait sur le disque.
      expect(
        {
          KeystoreAliases.fileLabelPrimary,
          KeystoreAliases.fileLabelDecoy,
        }.intersection({KeystoreAliases.primary, KeystoreAliases.decoy}),
        isEmpty,
      );
    });
  });

  group('isKnownFileLabel — retro-compatibilite', () {
    test('accepte les nouvelles etiquettes', () {
      expect(
        KeystoreAliases.isKnownFileLabel(KeystoreAliases.fileLabelPrimary),
        isTrue,
      );
      expect(
        KeystoreAliases.isKnownFileLabel(KeystoreAliases.fileLabelDecoy),
        isTrue,
      );
    });

    test('accepte encore les alias HERITES', () {
      // Indispensable : l'etiquette sert d'AAD. Refuser les anciennes
      // valeurs rendrait indechiffrables tous les coffres anterieurs au
      // correctif — le tag AES-GCM ne verifierait plus.
      expect(KeystoreAliases.isKnownFileLabel(KeystoreAliases.primary), isTrue);
      expect(KeystoreAliases.isKnownFileLabel(KeystoreAliases.decoy), isTrue);
    });

    test('refuse tout le reste', () {
      for (final bad in [
        '',
        'pt_vault_kek_c_v2',
        'pt_vault_kek_a_v3',
        'PT_VAULT_KEK_A_V2',
        'pt_vault_kek_a_v2 ',
        'autre',
      ]) {
        expect(
          KeystoreAliases.isKnownFileLabel(bad),
          isFalse,
          reason: 'accepte a tort "$bad"',
        );
      }
    });
  });
}
