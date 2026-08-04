// SEC 2026-08-04 — la règle de mot de passe est désormais UNIQUE et partagée
// par les cinq points d'entrée (création du coffre, changement du mot de passe
// maître, phrase secrète `.ptbak`, coffre leurre, mot de passe héritier).
//
// Auparavant, seul l'écran de création vérifiait l'entropie. Les quatre autres
// ne regardaient que la longueur : `aaaaaaaaaaaa` y passait, y compris sur le
// chemin qui REMPLACE le mot de passe maître d'un coffre existant.
//
// Ces tests couvrent les deux directions, et la seconde compte autant que la
// première :
//   • le durcissement rejette bien ce qu'il doit rejeter ;
//   • ⚠️ il n'enferme PAS l'utilisateur légitime. Une règle trop stricte qui
//     refuse une phrase de passe honnête est un défaut, pas une protection —
//     elle pousse vers des mots de passe notés puis vers l'abandon.

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/password_policy.dart';
import 'package:pass_tech/services/password_strength_service.dart';

void main() {
  group('PasswordPolicy — ce qui DOIT être accepté', () {
    // Le vrai risque de ce correctif : bloquer quelqu'un de légitime.
    const acceptables = <String>[
      // Phrase de passe en minuscules seules : à 12 caractères, on atteint
      // déjà 56 bits. C'est tout l'intérêt de ne pas exiger de symboles.
      'chienbleumatin',
      'renardclochesoleil',
      // Ce que produit le générateur intégré (diceware français).
      'renard-cloche-violet-soleil-7',
      // Mélange classique.
      'MonCoffreFort2026',
      // Avec espaces — une vraie phrase.
      'le chat dort sur le toit',
      // Pile à la longueur minimale, minuscules seules.
      'abricotmiel12',
      'motdepassequejaichoisi',
    ];

    for (final pwd in acceptables) {
      test('accepté : "$pwd" (${pwd.length} car.)', () {
        expect(
          PasswordPolicy.check(pwd),
          isNull,
          reason:
              'entropie = ${PasswordStrengthService.entropyBits(pwd).toStringAsFixed(1)} bits ; '
              'un refus ici enfermerait un utilisateur légitime',
        );
      });
    }
  });

  group('PasswordPolicy — ce qui DOIT être refusé', () {
    test('trop court', () {
      expect(PasswordPolicy.check('court'), PasswordRejection.tooShort);
      expect(
        PasswordPolicy.check('a' * (PasswordPolicy.minLength - 1)),
        PasswordRejection.tooShort,
      );
    });

    test('assez long mais répétitif — le cas qui passait partout sauf à la '
        'création', () {
      expect(PasswordPolicy.check('aaaaaaaaaaaa'), PasswordRejection.tooWeak);
      expect(PasswordPolicy.check('abababababab'), PasswordRejection.tooWeak);
    });

    test('suite triviale', () {
      expect(PasswordPolicy.check('abcdefghijkl'), PasswordRejection.tooWeak);
      expect(
        PasswordPolicy.check('123456789012345'),
        PasswordRejection.tooWeak,
      );
    });

    test('mot de passe courant, même habillé', () {
      expect(PasswordPolicy.check('motdepasse123'), PasswordRejection.tooWeak);
      expect(PasswordPolicy.check('Password1234!'), PasswordRejection.tooWeak);
    });

    test('vide', () {
      expect(PasswordPolicy.check(''), PasswordRejection.tooShort);
    });
  });

  group('PasswordPolicy — invariants de la règle', () {
    test('aucune classe de caractères n\'est exigée', () {
      // Le NIST déconseille les règles de composition depuis SP 800-63B.
      // Ce test échouerait si quelqu'un réintroduisait « il faut un chiffre ».
      expect(
        PasswordPolicy.check('chienbleumatin'),
        isNull,
        reason: 'minuscules seules doit suffire dès lors que la longueur y est',
      );
      expect(
        PasswordPolicy.check('CHIENBLEUMATIN'),
        isNull,
        reason: 'majuscules seules aussi',
      );
    });

    test('la longueur minimale seule ne suffit pas à faire passer n\'importe '
        'quoi', () {
      final repetitif = 'z' * PasswordPolicy.minLength;
      expect(
        repetitif.length,
        PasswordPolicy.minLength,
        reason: 'le test perdrait son sens si la longueur ne collait pas',
      );
      expect(PasswordPolicy.check(repetitif), PasswordRejection.tooWeak);
    });
  });
}
