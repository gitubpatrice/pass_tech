// Tests garde H1 (v2.5.x) — déni plausible AU REPOS.
//
// H1 (audit sécurité v2.5.0) : le coffre leurre était un fichier séparé
// `pt_vault_decoy.enc` n'existant QUE si un decoy était configuré, dont le nom
// révélait au forensic quel fichier était le vrai coffre. Fix :
//   1. Noms de fichiers NEUTRES indistinguables (`pt_vault_a.enc` /
//      `pt_vault_b.enc`) — migration crash-safe par `ensureVaultLayout`.
//   2. Leurre FACTICE toujours présent (chiffré sous un mot de passe aléatoire
//      jamais stocké → jamais déverrouillable) → profil de fichiers CONSTANT.
//   3. Flag `pt_decoy_configured` (secure storage, chiffré TEE, toujours
//      présent) au lieu de « le fichier decoy existe » pour l'UI.
//
// Le harnais complet (`ensureVaultLayout` : path_provider + secure_storage +
// Keystore) est validé sur DEVICE (philosophie de test du projet pour le code
// plateforme). Ici on verrouille en pure logique la propriété centrale du
// leurre factice : une liste d'entrées VIDE chiffrée en AES-GCM est un coffre
// v4 VALIDE (donc indistinguable d'un vrai coffre au repos) mais son plaintext
// n'est récupérable qu'avec la clé exacte (mot de passe aléatoire → personne).

import 'dart:convert';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/aead_service.dart';

Future<Uint8List> _hkdf(Uint8List salt, Uint8List ikm) async {
  final hkdf = Hkdf(hmac: Hmac.sha256(), outputLength: 32);
  final out = await hkdf.deriveKey(
    secretKey: SecretKey(ikm),
    nonce: salt,
    info: utf8.encode('pt:v4'),
  );
  return Uint8List.fromList(await out.extractBytes());
}

void main() {
  group('H1 — leurre factice (payload vide) est un coffre v4 valide', () {
    test('liste d\'entrées vide chiffrée AES-GCM round-trip → []', () async {
      final key = Uint8List.fromList(
        List<int>.generate(32, (i) => i * 7 & 0xFF),
      );
      final aad = Uint8List.fromList(utf8.encode('pt:v=4|alias=test'));

      // Le leurre factice chiffre exactement `jsonEncode(const [])`.
      final ptIn = Uint8List.fromList(
        utf8.encode(jsonEncode(const <dynamic>[])),
      );
      final aead = await AeadService.encryptGcm(
        key: key,
        plaintext: ptIn,
        aad: aad,
      );

      final split = AeadService.splitCipherAndTag(aead.cipherAndTag);
      final ptOut = await AeadService.decryptGcm(
        key: key,
        nonce: aead.nonce,
        ciphertext: split.ciphertext,
        tag: split.tag,
        aad: aad,
      );
      expect(ptOut, isNotNull);
      final entries = jsonDecode(utf8.decode(ptOut!)) as List;
      expect(entries, isEmpty, reason: 'leurre factice = 0 entrée');
    });

    test(
      'leurre non-déverrouillable : clé différente → null (fail-closed)',
      () async {
        // Simule un leurre : clé dérivée d'un « mot de passe aléatoire » jamais
        // stocké. Une tentative avec une AUTRE clé (n'importe quel mot de passe
        // utilisateur) doit échouer sans fuite de plaintext.
        final salt = Uint8List.fromList(List<int>.generate(32, (i) => i));
        final randomIkm = Uint8List.fromList(
          List<int>.generate(64, (i) => (i * 31 + 5) & 0xFF),
        );
        final dummyKey = await _hkdf(salt, randomIkm);
        final aad = Uint8List.fromList(utf8.encode('pt:v=4|alias=test'));

        final aead = await AeadService.encryptGcm(
          key: dummyKey,
          plaintext: Uint8List.fromList(
            utf8.encode(jsonEncode(const <dynamic>[])),
          ),
          aad: aad,
        );

        // Clé « utilisateur » quelconque, différente de la clé aléatoire du leurre.
        final otherKey = Uint8List.fromList(List<int>.filled(32, 0xAB));
        final split = AeadService.splitCipherAndTag(aead.cipherAndTag);
        final out = await AeadService.decryptGcm(
          key: otherKey,
          nonce: aead.nonce,
          ciphertext: split.ciphertext,
          tag: split.tag,
          aad: aad,
        );
        expect(out, isNull, reason: 'leurre inaccessible sans sa clé exacte');
      },
    );
  });
}
