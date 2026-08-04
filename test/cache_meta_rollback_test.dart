import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/vault_service.dart';

/// SEC 2026-08-04 (relecture Codex) — garde d'aliasing du cache meta.
///
/// `_createSlot` prend un instantane des quatre champs de cache
/// (`_cachedSalt` / `_cachedWrappedDek` / `_cachedWrapNonce` /
/// `_cachedKdfParams`) et le restaure si la creation echoue. Sans cette
/// restauration, une session revenue au coffre PRINCIPAL conservait les
/// metadonnees du LEURRE, et la premiere ecriture suivante reecrivait le
/// fichier principal en annoncant le sel et l'enveloppe du leurre : coffre
/// definitivement illisible.
///
/// La restauration efface les tampons sortants — mais `SecretBytes.wipe` ecrit
/// des zeros DANS le tampon. Quand l'echec survient AVANT `_saveVaultV4`, le
/// cache courant et l'instantane sont le MEME objet : un effacement naif
/// remettrait un sel nul en place, c'est-a-dire exactement la corruption que le
/// correctif existe pour empecher. D'ou `_wipeUnlessSame`, teste ici.
void main() {
  Uint8List sel(int graine) =>
      Uint8List.fromList(List<int>.generate(32, (i) => (graine + i) & 0xff));

  bool estNul(Uint8List b) => b.every((o) => o == 0);

  group('garde d\'aliasing du cache meta', () {
    test('n\'efface PAS le tampon que l\'on remet en place', () {
      // Cas de l'echec AVANT `_saveVaultV4` : meme objet des deux cotes.
      final courant = sel(1);
      VaultService.wipeUnlessSameForTest(courant, courant);
      expect(
        estNul(courant),
        isFalse,
        reason: 'le sel restaure ne doit jamais etre zeroise',
      );
    });

    test('efface bien un tampon reellement sortant', () {
      // Cas de l'echec APRES `_saveVaultV4` : le cache courant decrit le
      // leurre, l'instantane decrit le principal. Le leurre doit partir.
      final sortant = sel(1);
      final conserve = sel(200);
      VaultService.wipeUnlessSameForTest(sortant, conserve);
      expect(estNul(sortant), isTrue);
      expect(estNul(conserve), isFalse);
    });

    test('deux tampons de CONTENU identique restent distincts', () {
      // Piege : la garde doit porter sur l'IDENTITE, pas sur l'egalite de
      // contenu. Deux copies du meme sel sont deux objets ; effacer l'une ne
      // doit pas dependre de leur contenu.
      final sortant = sel(7);
      final conserve = sel(7);
      expect(sortant, equals(conserve));
      VaultService.wipeUnlessSameForTest(sortant, conserve);
      expect(estNul(sortant), isTrue);
      expect(
        estNul(conserve),
        isFalse,
        reason: 'l\'instantane doit survivre meme s\'il a le meme contenu',
      );
    });

    test('tolere un tampon sortant nul', () {
      // Cache vide (post-lock, ou creation du tout premier coffre).
      final conserve = sel(3);
      VaultService.wipeUnlessSameForTest(null, conserve);
      expect(estNul(conserve), isFalse);
    });

    test('tolere un instantane nul et efface quand meme le sortant', () {
      final sortant = sel(9);
      VaultService.wipeUnlessSameForTest(sortant, null);
      expect(estNul(sortant), isTrue);
    });

    test('null des deux cotes ne leve pas', () {
      expect(
        () => VaultService.wipeUnlessSameForTest(null, null),
        returnsNormally,
      );
    });

    test('un tampon vide ne leve pas', () {
      final vide = Uint8List(0);
      expect(
        () => VaultService.wipeUnlessSameForTest(vide, null),
        returnsNormally,
      );
    });
  });
}
