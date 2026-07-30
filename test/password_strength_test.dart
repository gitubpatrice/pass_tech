import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/password_strength_service.dart';

void main() {
  group('SEC F9 — isWeak doit signaler au-dela de 10 caracteres', () {
    // Avant le correctif, `isWeak` se reduisait a `length < 10` : la clause
    // `score < 0.35` etait arithmetiquement inatteignable (le plus petit pool
    // non nul valant 10, 10 caracteres donnaient deja 33,2 bits = 0,415).
    // Tous les cas ci-dessous retournaient donc `false` — l'audit du coffre
    // affichait « 0 mot de passe faible ».
    const doitEtreFaible = [
      'aaaaaaaaaaaa', // repetition pure
      '1234567890', // suite croissante
      '0987654321', // suite decroissante
      'abcdefghijkl', // suite alphabetique
      'password', // trop court ET courant
      'motdepasse12', // racine francaise courante + chiffres
      'azerty123456', // marche clavier + suite
      'P@ssw0rd123', // l33t d'une racine courante
      'qwertyuiop', // marche clavier complete
      'iloveyou2024', // racine courante + annee
    ];

    for (final p in doitEtreFaible) {
      test('"$p" est signale faible', () {
        expect(
          PasswordStrengthService.isWeak(p),
          isTrue,
          reason:
              'score=${PasswordStrengthService.score(p).toStringAsFixed(3)} '
              'bits=${PasswordStrengthService.entropyBits(p).toStringAsFixed(1)}',
        );
      });
    }

    const doitPasserFort = [
      'Tr0ub4dour&3xplor', // long, varie, pas de motif trivial
      'correct-horse-battery-staple',
      'K9\$mZq2!vXnP4wLd',
      'chaise-nuage-turbine-42',
    ];

    for (final p in doitPasserFort) {
      test('"$p" n\'est PAS signale faible', () {
        expect(
          PasswordStrengthService.isWeak(p),
          isFalse,
          reason:
              'score=${PasswordStrengthService.score(p).toStringAsFixed(3)} '
              'bits=${PasswordStrengthService.entropyBits(p).toStringAsFixed(1)}',
        );
      });
    }
  });

  group('entropyBits — penalisation des motifs', () {
    test('une repetition vaut bien moins que des caracteres independants', () {
      final repete = PasswordStrengthService.entropyBits('aaaaaaaaaaaa');
      final varie = PasswordStrengthService.entropyBits('kfjqmxzbvwtr');
      expect(repete, lessThan(varie / 2));
    });

    test('une suite vaut nettement moins que des chiffres independants', () {
      final suite = PasswordStrengthService.entropyBits('123456789012');
      final alea = PasswordStrengthService.entropyBits('849250173846');
      // La suite se decompose en 2 segments (123456789 puis 012), donc la
      // penalite est moins forte que sur une repetition pure. Ce qui compte
      // pour la securite, c'est qu'elle passe SOUS le seuil de faiblesse —
      // verifie par le groupe isWeak ci-dessus.
      expect(suite, lessThan(alea * 0.75));
      expect(PasswordStrengthService.score('123456789012'), lessThan(0.35));
    });

    test('des chiffres independants ne sont PAS traites comme courants', () {
      expect(
        PasswordStrengthService.entropyBits('849250173846'),
        greaterThan(0),
      );
    });

    test('un mot de passe courant vaut 0 bit', () {
      expect(PasswordStrengthService.entropyBits('motdepasse'), 0);
    });

    test('chaine vide vaut 0 bit', () {
      expect(PasswordStrengthService.entropyBits(''), 0);
    });
  });

  group('isCommon — normalisation l33t et casse', () {
    test('reconnait la racine sous substitution', () {
      expect(PasswordStrengthService.isCommon('P@ssw0rd'), isTrue);
      expect(PasswordStrengthService.isCommon('M0tD3P@sse'), isTrue);
    });

    test('reconnait une racine habillee de chiffres', () {
      expect(PasswordStrengthService.isCommon('azerty2024'), isTrue);
    });

    test('ne signale pas une phrase de passe legitime', () {
      expect(PasswordStrengthService.isCommon('chaise-nuage-turbine'), isFalse);
    });
  });

  group('score — bornes', () {
    test('reste dans [0, 1]', () {
      for (final p in ['', 'a', 'aaaaaaaaaa', 'K9\$mZq2!vXnP4wLd' * 4]) {
        final s = PasswordStrengthService.score(p);
        expect(s, inInclusiveRange(0.0, 1.0));
      }
    });
  });
}
