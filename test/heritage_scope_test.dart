// AUDIT 2026-09-21 — l'héritage est propre à l'emplacement.
//
// Ces tests gardent le correctif qui a fermé TROIS oracles de session leurre
// d'un seul geste (le refus qui nommait le coffre principal, le compteur
// d'inactivité, et « Mettre à jour » qui acceptait depuis le leurre ce qu'il
// refusait depuis le principal).
//
// Ils ne vérifient pas de la crypto : ils vérifient les deux invariants de
// NOMMAGE dont tout le reste dépend, et que rien d'autre ne protège. Un
// `flutter analyze` vert ne dirait rien si quelqu'un suffixait le principal ou
// laissait une clé commune aux deux emplacements — le premier cas efface
// silencieusement l'héritage du parc installé, le second rouvre l'oracle.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/heritage_service.dart';

void main() {
  final principal = HeritageService.nomsPourEmplacement(leurre: false);
  final leurre = HeritageService.nomsPourEmplacement(leurre: true);

  group('héritage — nommage par emplacement', () {
    test('le principal garde EXACTEMENT les noms historiques', () {
      // Ces six noms sont ceux qu'écrivent les versions ≤ 2.7.0. Les changer
      // rend l'héritage déjà configuré invisible à la mise à jour, sans erreur
      // ni signal : le dead-man cesse d'exister pour qui comptait dessus.
      expect(principal['salt'], 'pt_heir_salt');
      expect(principal['enabled'], 'pt_heir_enabled');
      expect(principal['lastActive'], 'pt_last_active_ts');
      expect(principal['threshold'], 'pt_heir_threshold_days');
      expect(principal['graceStart'], 'pt_heir_grace_start_ts');
      expect(principal['snapshot'], 'pt_heir.enc');
    });

    test('les deux emplacements ne partagent AUCUN nom', () {
      // C'est l'invariant de sécurité. Un seul nom commun suffit à rouvrir
      // l'oracle : l'interface, qui tourne aussi en session leurre, rendrait à
      // nouveau un état du coffre principal.
      expect(
        principal.keys.toSet(),
        leurre.keys.toSet(),
        reason: 'les deux emplacements doivent décrire les mêmes champs',
      );
      final communs = principal.entries
          .where((e) => leurre[e.key] == e.value)
          .map((e) => '${e.key} → ${e.value}')
          .toList();
      expect(
        communs,
        isEmpty,
        reason: 'nom(s) partagé(s) entre principal et leurre : $communs',
      );
    });

    test('aucun nom du leurre ne contient le mot « decoy » ou « leurre »', () {
      // Les fichiers de coffre portent des noms neutres (`pt_vault_a.enc` /
      // `pt_vault_b.enc`) précisément pour qu'une copie du dossier privé ne
      // désigne pas le leurre. L'instantané d'héritage suit la même règle.
      for (final v in leurre.values) {
        expect(v.toLowerCase(), isNot(contains('decoy')));
        expect(v.toLowerCase(), isNot(contains('leurre')));
        expect(v.toLowerCase(), isNot(contains('fake')));
      }
    });

    test('le suffixe du leurre est le même partout', () {
      // Un suffixe qui diverge d'un champ à l'autre est le genre de détail qui
      // survit à la relecture et casse la purge de `deleteVault`, laquelle
      // énumère des noms en dur.
      for (final champ in principal.keys) {
        final attendu = champ == 'snapshot'
            ? 'pt_heir_b.enc'
            : '${principal[champ]}_b';
        expect(leurre[champ], attendu, reason: 'champ « $champ »');
      }
    });
  });

  group('héritage — la purge ne diverge pas du nommage', () {
    // `vault_service.dart` ne peut pas importer `HeritageService` : le cycle,
    // puisque `heritage_service.dart` l'importe déjà. Ses listes de purge
    // répètent donc les noms en dur, et CE TEST est la seule chose qui
    // empêche les deux de s'écarter.
    //
    // Ce qu'il attrape : renommer une clé dans `nomsPourEmplacement` sans
    // toucher à la purge. Elle viserait alors un nom qui n'existe plus, sans
    // erreur ni avertissement — et l'état d'héritage survivrait à la
    // destruction de son coffre. C'est exactement le défaut que la relecture
    // du 2026-09-21 a trouvé sur les trois sorties qui retirent le leurre.
    final source = File('lib/services/vault_service.dart').readAsStringSync();

    test('la purge cite chaque nom du LEURRE', () {
      for (final nom in leurre.values) {
        expect(
          source,
          contains("'$nom'"),
          reason: 'nom du leurre absent de vault_service.dart : $nom',
        );
      }
    });

    test('la purge cite chaque nom du PRINCIPAL', () {
      for (final nom in principal.values) {
        expect(
          source,
          contains("'$nom'"),
          reason: 'nom du principal absent de vault_service.dart : $nom',
        );
      }
    });

    test('les trois sorties qui retirent le leurre purgent son héritage', () {
      // `deleteVault` depuis le leurre, et les deux sorties de
      // `deleteDecoyVault`. Chacune rendait AVANT d'atteindre la purge.
      expect(
        RegExp(r'_purgeDecoyHeritage\(\)').allMatches(source).length,
        greaterThanOrEqualTo(4),
        reason: 'attendu : la déclaration + les trois appels',
      );
    });
  });
}
