import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/vault_service.dart';

/// SEC F6 — le leurre factice chiffrait `[]` (2 octets → 24 caracteres base64)
/// alors qu'un vrai coffre produit un chiffre proportionnel au nombre
/// d'entrees. AES-GCM preservant la longueur et aucun rembourrage n'etant
/// applique, la taille du fichier etait un discriminant DETERMINISTE entre un
/// leurre et un vrai second coffre : le deni plausible tombait sur un `ls -l`.
void main() {
  final bucket = VaultService.paddingBucketBytesForTest;

  Uint8List pad(String s, {int minBuckets = 1}) =>
      VaultService.padToBucketForTest(
        Uint8List.fromList(utf8.encode(s)),
        minBuckets: minBuckets,
      );

  group('padToBucket — alignement sur les paliers', () {
    test('le palier fait 4 Kio', () {
      expect(bucket, 4096);
    });

    test('un coffre vide et un coffre garni tombent au meme palier', () {
      final leurre = pad('[]');
      final petitCoffreReel = pad(
        jsonEncode([
          {'id': '1', 'name': 'Banque', 'password': 'hunter2'},
          {'id': '2', 'name': 'Mail', 'password': 'correct-horse'},
        ]),
      );
      expect(leurre.length, petitCoffreReel.length);
      expect(leurre.length, bucket);
    });

    test('un clair depassant un palier passe au palier suivant', () {
      expect(pad('x' * (bucket - 1)).length, bucket);
      expect(pad('x' * bucket).length, bucket);
      expect(pad('x' * (bucket + 1)).length, bucket * 2);
    });

    test('minBuckets impose un plancher', () {
      expect(pad('[]', minBuckets: 3).length, bucket * 3);
      // Un contenu deja plus grand que le plancher n'est PAS retreci.
      expect(pad('x' * (bucket * 5), minBuckets: 2).length, bucket * 5);
    });

    test('la longueur est toujours un multiple exact du palier', () {
      for (final n in [0, 1, 2, 999, 4095, 4096, 4097, 20000]) {
        expect(pad('x' * n).length % bucket, 0, reason: 'longueur $n');
      }
    });
  });

  group('padToBucket — retro-compatibilite de lecture', () {
    // Point CRITIQUE : le rembourrage utilise l'espace (0x20), que `jsonDecode`
    // ignore en fin de chaine. Le clair rembourre se relit donc avec le
    // decodeur EXISTANT — aucun bump de version, aucune migration, et les
    // coffres deja ecrits SANS rembourrage continuent de s'ouvrir.
    test('un coffre rembourre se decode avec le decodeur existant', () {
      final entrees = [
        {'id': 'a', 'name': 'Compte', 'password': 'p@ss'},
        {'id': 'b', 'name': 'Autre', 'notes': 'ligne1\nligne2'},
      ];
      final padded = pad(jsonEncode(entrees));
      final relu = jsonDecode(utf8.decode(padded)) as List;
      expect(relu.length, 2);
      expect((relu[0] as Map)['name'], 'Compte');
      expect((relu[1] as Map)['notes'], 'ligne1\nligne2');
    });

    test('un leurre vide rembourre se decode en liste vide', () {
      expect((jsonDecode(utf8.decode(pad('[]'))) as List), isEmpty);
    });

    test('un coffre LEGACY non rembourre se decode toujours', () {
      // Simule un fichier ecrit par une version anterieure au correctif.
      final legacy = utf8.encode(
        jsonEncode([
          {'id': 'x', 'name': 'Ancien'},
        ]),
      );
      final relu = jsonDecode(utf8.decode(legacy)) as List;
      expect((relu.single as Map)['name'], 'Ancien');
    });

    test('le contenu utile precede exactement le rembourrage', () {
      final src = jsonEncode([
        {'id': '1'},
      ]);
      final padded = pad(src);
      expect(utf8.decode(padded.sublist(0, src.length)), src);
      // Tout le reste est de l'espace, jamais un octet nul (qui casserait
      // `jsonDecode`).
      expect(
        padded.sublist(src.length),
        everyElement(0x20),
        reason: 'le rembourrage doit etre uniquement des espaces',
      );
    });
  });
}
