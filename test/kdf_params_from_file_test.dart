// AUDIT 2026-08-03 — les paramètres Argon2id doivent être RELUS dans le
// fichier, jamais repris des constantes de compilation.
//
// Défaut corrigé : `kdf.m` / `kdf.t` / `kdf.p` étaient écrits dans les trois
// formats (coffre v4, instantané héritier v2, sauvegarde `.ptbak` v3) et
// n'étaient jamais relus — sauf par `importEncrypted`, qui les lisait pour
// dériver la clé mais construisait l'AAD à partir des constantes. Le jour où
// `KdfParams.owaspMobile2024` serait relevé, tous les fichiers existants
// seraient devenus indéchiffrables, en silence, sous un message « mot de passe
// incorrect ».
//
// Ces tests échouent sur le code d'avant le correctif. C'est leur seule raison
// d'être : aucun test existant ne forgeait de paramètres VALIDES mais
// différents des constantes — ceux du `.ptbak` ne vérifiaient que le rejet de
// valeurs hors bornes.

import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/models/entry.dart';
import 'package:pass_tech/services/aead_service.dart';
import 'package:pass_tech/services/import_export_service.dart';
import 'package:pass_tech/services/kdf_service.dart';

/// Reconstruit l'AAD `.ptbak` v3 telle que le format la définit.
/// Volontairement recopiée ici plutôt qu'importée : ce test doit épingler le
/// format de fichier, pas suivre le code si quelqu'un le change.
List<int> _ptbakAad(String saltB64, KdfParams p) => utf8.encode(
  'ptbak:v=3|kdf=argon2id'
  '|m=${p.memoryKiB}|t=${p.iterations}|p=${p.parallelism}'
  '|salt=$saltB64',
);

/// Fabrique une sauvegarde `.ptbak` v3 chiffrée sous [params], sans passer par
/// `exportEncrypted` — qui, lui, emploie toujours la recommandation courante.
Future<String> _buildPtbak({
  required List<Entry> entries,
  required String passphrase,
  required KdfParams params,
  Map<String, dynamic>? kdfOverride,
}) async {
  final salt = Uint8List.fromList(List<int>.generate(32, (i) => i * 7 % 256));
  final saltB64 = base64Encode(salt);
  final key = await KdfService.argon2id(
    password: passphrase,
    salt: salt,
    params: params,
  );
  final plain = Uint8List.fromList(
    utf8.encode(jsonEncode(entries.map((e) => e.toJson()).toList())),
  );
  final res = await AeadService.encryptGcm(
    key: key,
    plaintext: plain,
    aad: Uint8List.fromList(_ptbakAad(saltB64, params)),
  );
  return jsonEncode({
    'magic': 'PTBAK',
    'version': 3,
    'kdf':
        kdfOverride ??
        <String, dynamic>{
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

Entry _entry(String title) =>
    Entry(title: title, category: 'Autres', password: 'motdepasse-$title');

void main() {
  group('KdfParams.fromFileOrNull', () {
    test('paramètres absents → repli sur la recommandation courante', () {
      final p = KdfParams.fromFileOrNull(<String, dynamic>{'salt': 'x'});
      expect(p, isNotNull);
      expect(p!.memoryKiB, KdfParams.owaspMobile2024.memoryKiB);
      expect(p.iterations, KdfParams.owaspMobile2024.iterations);
      expect(p.parallelism, KdfParams.owaspMobile2024.parallelism);
    });

    test('paramètres valides mais DIFFÉRENTS des constantes → honorés', () {
      // Le cœur du correctif : ce cas rendait auparavant les constantes.
      final p = KdfParams.fromFileOrNull(<String, dynamic>{
        'm': 8192,
        't': 3,
        'p': 2,
      });
      expect(p, isNotNull);
      expect(p!.memoryKiB, 8192);
      expect(p.iterations, 3);
      expect(p.parallelism, 2);
      expect(p.outLen, 32);
    });

    test('mémoire trop faible → refus', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{'m': 1024, 't': 2, 'p': 1}),
        isNull,
      );
    });

    test('mémoire trop grande (épuisement RAM) → refus', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{
          'm': 1024 * 1024 + 1,
          't': 2,
          'p': 1,
        }),
        isNull,
      );
    });

    test('itérations hors bornes → refus', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{'m': 19456, 't': 0, 'p': 1}),
        isNull,
      );
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{
          'm': 19456,
          't': 17,
          'p': 1,
        }),
        isNull,
      );
    });

    test('parallélisme hors bornes → refus', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{'m': 19456, 't': 2, 'p': 0}),
        isNull,
      );
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{'m': 19456, 't': 2, 'p': 5}),
        isNull,
      );
    });

    test('champ présent mais du mauvais type → refus', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{
          'm': '19456',
          't': 2,
          'p': 1,
        }),
        isNull,
      );
    });

    test('champs partiellement présents → refus (fichier incohérent)', () {
      expect(
        KdfParams.fromFileOrNull(<String, dynamic>{'m': 19456}),
        isNull,
        reason:
            'le repli ne vaut que si les TROIS sont absents ; un fichier qui '
            'en annonce un seul est malformé et doit être refusé',
      );
    });
  });

  group('.ptbak v3 — l\'AAD suit les paramètres du FICHIER', () {
    test(
      'une sauvegarde chiffrée sous des paramètres non standard se relit',
      () async {
        // Simule très exactement ce qui arrivera le jour où
        // `owaspMobile2024` sera relevé : un fichier écrit avec d'autres
        // paramètres que ceux compilés dans la version qui le relit.
        const params = KdfParams(
          memoryKiB: 8192,
          iterations: 1,
          parallelism: 1,
          outLen: 32,
        );
        expect(
          params.memoryKiB == KdfParams.owaspMobile2024.memoryKiB,
          isFalse,
          reason: 'le test perdrait tout son sens si les valeurs coïncidaient',
        );

        final content = await _buildPtbak(
          entries: [_entry('banque'), _entry('messagerie')],
          passphrase: 'phrase-secrete-de-test',
          params: params,
        );

        final restored = await ImportExportService.importEncrypted(
          content,
          'phrase-secrete-de-test',
        );

        // Avant le correctif : `null`. La clé était bien dérivée avec les
        // paramètres du fichier, mais l'AAD était bâtie sur les constantes,
        // donc l'étiquette AES-GCM ne se vérifiait pas — et l'utilisateur
        // lisait « phrase secrète incorrecte » sur une sauvegarde parfaitement
        // valide.
        expect(restored, isNotNull);
        expect(restored!.length, 2);
        expect(
          restored.map((e) => e.title),
          containsAll(['banque', 'messagerie']),
        );
        expect(restored.first.password, 'motdepasse-banque');
      },
    );

    test('mauvaise phrase secrète → refus (fail-closed inchangé)', () async {
      const params = KdfParams(
        memoryKiB: 8192,
        iterations: 1,
        parallelism: 1,
        outLen: 32,
      );
      final content = await _buildPtbak(
        entries: [_entry('banque')],
        passphrase: 'la-bonne-phrase',
        params: params,
      );
      expect(
        await ImportExportService.importEncrypted(content, 'la-mauvaise'),
        isNull,
      );
    });

    test(
      'paramètres falsifiés dans le fichier → refus, pas de déchiffrement',
      () async {
        const params = KdfParams(
          memoryKiB: 8192,
          iterations: 1,
          parallelism: 1,
          outLen: 32,
        );
        final salt = base64Encode(
          Uint8List.fromList(List<int>.generate(32, (i) => i * 7 % 256)),
        );
        // Le fichier est chiffré sous m=8192 mais en annonce 16384 : la clé
        // dérivée ET l'AAD divergent tous les deux, l'étiquette ne peut pas
        // se vérifier. C'est le comportement voulu — un attaquant ne gagne
        // rien à réécrire ces champs, il ne fait que casser son propre
        // fichier.
        final content = await _buildPtbak(
          entries: [_entry('banque')],
          passphrase: 'phrase-secrete-de-test',
          params: params,
          kdfOverride: <String, dynamic>{
            'algo': 'argon2id',
            'm': 16384,
            't': 1,
            'p': 1,
            'salt': salt,
          },
        );
        expect(
          await ImportExportService.importEncrypted(
            content,
            'phrase-secrete-de-test',
          ),
          isNull,
        );
      },
    );

    test('paramètres hors bornes → refus AVANT tout calcul Argon2id', () async {
      const params = KdfParams(
        memoryKiB: 8192,
        iterations: 1,
        parallelism: 1,
        outLen: 32,
      );
      final salt = base64Encode(
        Uint8List.fromList(List<int>.generate(32, (i) => i * 7 % 256)),
      );
      final content = await _buildPtbak(
        entries: [_entry('banque')],
        passphrase: 'phrase-secrete-de-test',
        params: params,
        kdfOverride: <String, dynamic>{
          'algo': 'argon2id',
          'm': 4 * 1024 * 1024, // 4 Gio : épuiserait la mémoire de l'appareil
          't': 1,
          'p': 1,
          'salt': salt,
        },
      );
      expect(
        await ImportExportService.importEncrypted(
          content,
          'phrase-secrete-de-test',
        ),
        isNull,
      );
    });
  });

  group('.ptbak v3 — métadonnées en clair', () {
    test('ni le nombre d\'entrées ni la date ne sont écrits', () async {
      final content = await ImportExportService.exportEncrypted([
        _entry('banque'),
        _entry('messagerie'),
        _entry('impots'),
      ], 'phrase-secrete-de-test');
      final json = jsonDecode(content) as Map<String, dynamic>;

      // AUDIT 2026-08-03 — `count` et `exportedAt` étaient écrits en clair,
      // hors chiffrement et hors AAD, dans le seul fichier de l'application
      // destiné à quitter l'appareil. Ils annonçaient à qui mettait la main
      // dessus combien d'identifiants la personne détient et quand elle a fait
      // sa sauvegarde, sans qu'aucun code ne les relise jamais.
      expect(json.containsKey('count'), isFalse);
      expect(json.containsKey('exportedAt'), isFalse);

      // Ce qui reste doit suffire à relire le fichier.
      expect(json['magic'], 'PTBAK');
      expect(json['version'], 3);
      final restored = await ImportExportService.importEncrypted(
        content,
        'phrase-secrete-de-test',
      );
      expect(restored, isNotNull);
      expect(restored!.length, 3);
    });
  });
}
