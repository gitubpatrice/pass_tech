import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/vault_audit_score.dart';

/// Le score de l'audit etait calcule en ligne dans l'ecran, par soustractions
/// absolues plafonnees. Il n'etait couvert par aucun test, et il etait FAUX :
/// un coffre de six entrees toutes faibles affichait « 70 / Bon ».
///
/// Ces tests fixent le contrat du nouveau modele — la sante MOYENNE des mots de
/// passe — et verrouillent en particulier les deux proprietes que l'ancien
/// calcul violait : la proportionnalite, et l'absence de double comptage.
void main() {
  int? scoreOf(List<int> points) => VaultAuditScore.average(points);

  int pts({
    bool compromised = false,
    bool weak = false,
    bool duplicate = false,
    bool no2fa = false,
  }) => VaultAuditScore.pointsFor(
    compromised: compromised,
    weak: weak,
    duplicate: duplicate,
    sensitiveWithout2fa: no2fa,
  );

  group('bareme par entree', () {
    test('une entree saine vaut le maximum', () {
      expect(pts(), 100);
    });

    test('la compromission ecrase tout le reste', () {
      // Un mot de passe publiquement fuite n'offre plus aucune protection,
      // quelle que soit sa complexite ou son unicite.
      expect(pts(compromised: true), 0);
      expect(
        pts(compromised: true, weak: true, duplicate: true, no2fa: true),
        0,
      );
    });

    test('une entree ne compte QUE pour son pire defaut', () {
      // L'ancien calcul soustrayait une penalite par categorie : un mot de
      // passe a la fois faible ET reutilise etait compte deux fois.
      expect(pts(weak: true, duplicate: true), pts(weak: true));
      expect(pts(duplicate: true, no2fa: true), pts(duplicate: true));
    });

    test('gravite strictement decroissante', () {
      expect(pts(compromised: true), lessThan(pts(weak: true)));
      expect(pts(weak: true), lessThan(pts(duplicate: true)));
      expect(pts(duplicate: true), lessThan(pts(no2fa: true)));
      expect(pts(no2fa: true), lessThan(pts()));
    });
  });

  group('LE defaut historique : le score ignorait la taille du coffre', () {
    test('six entrees sur six faibles ne peut pas etre « Bon »', () {
      // Cas exact du bug : l'ancienne formule plafonnait la penalite a -30 des
      // le sixieme mot de passe faible et affichait 70, soit « Bon », sur un
      // coffre dont AUCUN mot de passe n'etait correct.
      final score = scoreOf(List.filled(6, pts(weak: true)))!;
      expect(score, lessThan(50), reason: 'doit tomber dans « Faible »');
    });

    test('six faibles parmi deux cents ne bouge presque pas le score', () {
      final score = scoreOf([
        ...List.filled(6, pts(weak: true)),
        ...List.filled(194, pts()),
      ])!;
      expect(score, greaterThanOrEqualTo(90));
    });

    test('meme nombre de fautifs, tailles differentes, scores differents', () {
      // C'est exactement ce que l'ancienne formule rendait IDENTIQUE.
      final petit = scoreOf([
        ...List.filled(6, pts(weak: true)),
        ...List.filled(4, pts()),
      ])!;
      final grand = scoreOf([
        ...List.filled(6, pts(weak: true)),
        ...List.filled(194, pts()),
      ])!;
      expect(petit, lessThan(grand));
    });

    test('le score decroit quand la proportion de fautifs croit', () {
      var precedent = 101;
      for (final fautifs in [0, 25, 50, 75, 100]) {
        final s = scoreOf([
          ...List.filled(fautifs, pts(weak: true)),
          ...List.filled(100 - fautifs, pts()),
        ])!;
        expect(s, lessThan(precedent), reason: '$fautifs fautifs sur 100');
        precedent = s;
      }
    });
  });

  group('les fuites entrent dans le score', () {
    test('un coffre entierement compromis tombe a zero', () {
      expect(scoreOf(List.filled(10, pts(compromised: true))), 0);
    });

    test('une compromission pese plus qu une faiblesse', () {
      final avecFuite = scoreOf([
        pts(compromised: true),
        ...List.filled(9, pts()),
      ])!;
      final avecFaible = scoreOf([pts(weak: true), ...List.filled(9, pts())])!;
      expect(avecFuite, lessThan(avecFaible));
    });
  });

  group('bornes', () {
    test('coffre sans mot de passe : pas de score', () {
      // L'ancienne formule repondait 100 « Excellent » — feliciter un coffre
      // vide. `null` force l'ecran a afficher autre chose qu'un verdict.
      expect(scoreOf(const []), isNull);
    });

    test('coffre entierement sain : 100', () {
      expect(scoreOf(List.filled(50, pts())), 100);
    });

    test('le resultat reste dans 0..100', () {
      for (final n in [1, 2, 3, 7, 33, 100]) {
        for (final p in [0, 25, 55, 85, 100]) {
          final s = scoreOf(List.filled(n, p))!;
          expect(s, inInclusiveRange(0, 100));
        }
      }
    });

    test('une seule entree rend exactement son bareme', () {
      expect(scoreOf([pts(weak: true)]), VaultAuditScore.pointsWeak);
      expect(scoreOf([pts(duplicate: true)]), VaultAuditScore.pointsDuplicate);
      expect(scoreOf([pts(no2fa: true)]), VaultAuditScore.pointsNo2fa);
    });
  });

  group('categories sensibles', () {
    test('valeurs CANONIQUES du coffre, pas des libelles traduits', () {
      // `models/category.dart` conserve le francais en base pour ne pas casser
      // les coffres existants. Comparer a des libelles traduits ferait
      // silencieusement echouer le controle hors du francais.
      expect(VaultAuditScore.sensitiveCategories, contains('Banque'));
      expect(VaultAuditScore.sensitiveCategories, contains('Email'));
      expect(VaultAuditScore.sensitiveCategories, isNot(contains('Bank')));
    });

    test('les reseaux sociaux comptent comme sensibles', () {
      // Une prise de controle de compte social sert de rebond vers les autres
      // services (reinitialisations, authentification deleguee).
      expect(VaultAuditScore.sensitiveCategories, contains('Réseaux sociaux'));
    });
  });
}
