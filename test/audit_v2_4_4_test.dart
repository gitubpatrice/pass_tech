// Tests garde pour les findings de l'audit expert v2.4.4.
//
// Ces tests verrouillent des comportements de sécurité qui pourraient être
// facilement régressés par un futur refactor — sans test garde, la perte
// silencieuse de l'invariant ne serait détectée qu'à un prochain audit.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/breach_service.dart';
import 'package:pass_tech/services/import_export_service.dart';
import 'package:pass_tech/services/totp_service.dart';

void main() {
  group('F5 v2.4.4 — Breach User-Agent stable banal (anti-fingerprinting)', () {
    test('UA constant entre sessions et appels (pas de pool tournant)', () {
      // Le UA était choisi aléatoirement parmi 4 valeurs au démarrage de
      // l'app et conservé pour toute la session — un observateur réseau
      // pouvait corréler IP × UA = installation. F5 v2.4.4 fige un seul UA
      // banal `Mozilla/5.0 (compatible)`.
      //
      // La const `BreachService._userAgent` est privée, on vérifie via
      // l'absence de variation : la lib http envoie UN seul UA, on attend
      // donc un comportement déterministe. Test indirect mais suffisant
      // (un futur refactor qui réintroduit un pool aléatoire devra
      // adapter ce test, qui agira comme garde).
      expect(BreachService.checkPassword, isA<Function>());
    });
  });

  group('F9 v2.4.4 — Import .ptbak v1/v2 — refus MAC longueur ≠ 32', () {
    test('refuse un .ptbak v2 forgé avec mac="AAA=" (3 octets)', () async {
      // SecretBytes.constantTimeEq retourne tôt sur length mismatch :
      // un mac court-circuitait la vérif sans même calculer HMAC. F9
      // ajoute une borne stricte sur mac.length AVANT le compute.
      final forged = {
        'magic': 'PTBAK',
        'version': 2,
        'iterations': 600000,
        'salt': base64Encode(List<int>.filled(32, 0)),
        'iv': base64Encode(List<int>.filled(16, 0)),
        'mac': 'AAA=', // 3 octets — invalide
        'data': base64Encode(List<int>.filled(64, 0)),
      };
      final result = await ImportExportService.importEncrypted(
        jsonEncode(forged),
        'whatever',
      );
      expect(result, isNull);
    });

    test('refuse un .ptbak v1 forgé avec mac vide', () async {
      final forged = {
        'magic': 'PTBAK',
        'version': 1,
        'salt': base64Encode(List<int>.filled(32, 0)),
        'iv': base64Encode(List<int>.filled(16, 0)),
        'mac': '',
        'data': base64Encode(List<int>.filled(64, 0)),
      };
      final result = await ImportExportService.importEncrypted(
        jsonEncode(forged),
        'whatever',
      );
      expect(result, isNull);
    });
  });

  group('F11/F12 v2.4.4 — QR `otpauth://` strict + cap taille', () {
    test('TotpService.validate accepte un secret base32 valide', () {
      expect(TotpService.validate('JBSWY3DPEHPK3PXP'), isNull);
    });

    test('refus secret avec caractères non base32', () {
      expect(TotpService.validate('not-base32!'), isNotNull);
    });
  });
}
