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
  final rung = VaultService.paddingBaseRungForTest;

  Uint8List pad(String s, {int minPlainBytes = 0}) =>
      VaultService.padToLadderForTest(
        Uint8List.fromList(utf8.encode(s)),
        minPlainBytes: minPlainBytes,
      );

  String coffre(int nEntrees) => jsonEncode([
    for (var i = 0; i < nEntrees; i++)
      {
        'id': 'id-$i',
        'name': 'Compte numero $i',
        'username': 'utilisateur$i@exemple.fr',
        'password': 'MotDePasseAssezLong-$i',
        'notes': 'quelques notes de contexte pour l\'entree $i',
      },
  ]);

  group('echelle de rembourrage', () {
    test('le premier barreau fait 64 Kio', () {
      expect(rung, 65536);
    });

    test('progression x4 entre barreaux', () {
      expect(pad('x' * (rung - 1)).length, rung);
      expect(pad('x' * rung).length, rung);
      expect(pad('x' * (rung + 1)).length, rung * 4);
      expect(pad('x' * (rung * 4 + 1)).length, rung * 16);
    });

    test('la longueur est toujours exactement un barreau', () {
      for (final n in [0, 1, 2, 999, 65535, 65536, 65537, 300000]) {
        final len = pad('x' * n).length;
        var r = rung;
        while (r < len) {
          r *= 4;
        }
        expect(len, r, reason: 'longueur $n');
      }
    });
  });

  group('EQUIVALENCE STRICTE des deux emplacements', () {
    test('leurre vide et coffre reel garni sont de MEME taille', () {
      final leurre = pad('[]');
      final reel = pad(coffre(100));
      expect(reel.length, leurre.length);
      expect(leurre.length, rung);
    });

    test(
      'un coffre de plusieurs centaines d\'entrees tient sur le 1er barreau',
      () {
        // C'est ce qui rend l'equivalence effective en pratique : tant que les
        // deux coffres tiennent sous 64 Kio, ils sont rigoureusement
        // indistinguables par la taille.
        final src = coffre(300);
        expect(src.length, lessThan(rung));
        expect(pad(src).length, rung);
      },
    );

    test('l\'appariement aligne un petit coffre sur un gros voisin', () {
      // Simule : l'autre emplacement contient un clair de 200 000 octets.
      // Ce coffre-ci, minuscule, doit neanmoins atterrir sur le meme barreau.
      final gros = pad('x' * 200000);
      final petitAppariE = pad('[]', minPlainBytes: 200000);
      expect(petitAppariE.length, gros.length);
    });

    test('l\'appariement ne retrecit jamais le contenu reel', () {
      final grosContenu = pad('x' * 200000, minPlainBytes: 10);
      expect(grosContenu.length, greaterThanOrEqualTo(200000));
    });

    test('appariement sur un voisin absent (0) = comportement nominal', () {
      expect(pad('[]', minPlainBytes: 0).length, pad('[]').length);
    });
  });

  group('longueur base64 sans decodage', () {
    // `_otherSlotPlainLength` est appelee a CHAQUE ecriture du coffre. Decoder
    // pour ne lire qu'une longueur allouerait puis jetterait 64 Kio a chaque
    // ajout / edition / suppression d'entree. Le calcul doit donc etre exact
    // sur toutes les longueurs, y compris les trois cas de bourrage.
    test('coincide avec base64Decode sur toutes les longueurs', () {
      for (var n = 0; n <= 200; n++) {
        final src = Uint8List.fromList(List<int>.generate(n, (i) => i % 256));
        final b64 = base64Encode(src);
        expect(
          VaultService.decodedLenFromBase64ForTest(b64),
          n < 4 ? (n == 0 ? 0 : base64Decode(b64).length) : n,
          reason: 'longueur $n (base64 "$b64")',
        );
      }
    });

    test('coincide sur une taille realiste de coffre rembourre', () {
      final src = Uint8List(65536 + 16); // clair rembourre + tag GCM
      final b64 = base64Encode(src);
      expect(VaultService.decodedLenFromBase64ForTest(b64), src.length);
    });

    test('entree malformee rend 0 au lieu de lever', () {
      for (final bad in ['', 'a', 'ab', 'abc', 'abcde']) {
        expect(VaultService.decodedLenFromBase64ForTest(bad), 0, reason: bad);
      }
    });
  });

  group('retro-compatibilite de lecture', () {
    // Point CRITIQUE : le rembourrage utilise l'espace (0x20), que `jsonDecode`
    // ignore en fin de chaine. Le clair rembourre se relit donc avec le
    // decodeur EXISTANT — aucun bump de version, aucune migration, et les
    // coffres deja ecrits SANS rembourrage continuent de s'ouvrir.
    test('un coffre rembourre se decode avec le decodeur existant', () {
      final relu = jsonDecode(utf8.decode(pad(coffre(3)))) as List;
      expect(relu.length, 3);
      expect((relu[0] as Map)['name'], 'Compte numero 0');
    });

    test('un leurre vide rembourre se decode en liste vide', () {
      expect(jsonDecode(utf8.decode(pad('[]'))) as List, isEmpty);
    });

    test('un coffre LEGACY non rembourre se decode toujours', () {
      final legacy = utf8.encode(coffre(2));
      expect((jsonDecode(utf8.decode(legacy)) as List).length, 2);
    });

    test('le contenu utile precede exactement le rembourrage', () {
      final src = coffre(1);
      final padded = pad(src);
      expect(utf8.decode(padded.sublist(0, src.length)), src);
      // Uniquement des espaces ensuite — un octet nul casserait `jsonDecode`.
      expect(padded.sublist(src.length), everyElement(0x20));
    });

    test('les accents survivent au round-trip utf8', () {
      final src = jsonEncode([
        {'name': 'Crédit Agricole', 'notes': 'clé de récupération : àéîôù'},
      ]);
      final relu = jsonDecode(utf8.decode(pad(src))) as List;
      expect((relu.single as Map)['notes'], 'clé de récupération : àéîôù');
    });
  });
}
