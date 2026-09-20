// v2.7.0 — les cinq langues, verifiees a l'EXECUTION.
//
// `tool/verifier_l10n.py` compare les fichiers .arb entre eux : il prouve la
// parite, pas que l'application sache s'en servir. Ces tests couvrent
// exactement ce qu'il ne voit pas :
//
//   - le selecteur propose-t-il une langue que `parseLocale` ignore, ou
//     l'inverse ? Les deux listes vivaient a deux endroits differents ;
//   - une langue acceptee par `parseLocale` est-elle reellement livree, ou
//     l'application retombe-t-elle en silence sur l'anglais ?
//   - `DateFormat.yMd(locale)` sait-il formater dans chacune ? Une locale
//     absente des donnees `intl` leve a l'affichage d'une fiche, pas au
//     demarrage — donc loin de la cause.

import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:pass_tech/l10n/app_localizations.dart';
import 'package:pass_tech/main.dart'
    show appLanguageCodes, parseLocale, localeToString;

void main() {
  group('Les cinq langues', () {
    test('appLanguageCodes et supportedLocales decrivent le meme ensemble', () {
      final declarees = appLanguageCodes.toSet();
      final livrees = AppLocalizations.supportedLocales
          .map((l) => l.languageCode)
          .toSet();
      expect(
        declarees,
        livrees,
        reason:
            'Le selecteur et les fichiers .arb ont diverge. Proposer une '
            'langue non livree la fait retomber en silence sur le gabarit ; '
            'livrer une langue non proposee la rend inatteignable.',
      );
    });

    test('parseLocale accepte chaque code propose, et rien d\'autre', () {
      for (final code in appLanguageCodes) {
        expect(
          parseLocale(code)?.languageCode,
          code,
          reason: 'parseLocale ignore « $code », pourtant propose.',
        );
      }
      // Le systeme, et seulement lui, rend null.
      expect(parseLocale('system'), isNull);
      expect(parseLocale(null), isNull);
      expect(parseLocale('xx'), isNull);
    });

    test('localeToString est l\'inverse de parseLocale', () {
      for (final code in appLanguageCodes) {
        expect(localeToString(parseLocale(code)), code);
      }
      expect(localeToString(null), 'system');
    });

    test('chaque langue rend des chaines non vides et distinctes', () async {
      final titres = <String, String>{};
      for (final locale in AppLocalizations.supportedLocales) {
        final t = await AppLocalizations.delegate.load(locale);
        expect(t.settingsTitle, isNotEmpty);
        expect(t.unlockCta, isNotEmpty);
        expect(t.biometricPromptSubtitle, isNotEmpty);
        // Chaine longue, celle qui porte le plus de risque de troncature.
        expect(t.panicDialogBody.length, greaterThan(200));
        titres[locale.languageCode] = t.settingsTitle;
      }
      expect(titres.length, 5);
      // « Impostazioni », « Ajustes », « Einstellungen »… : cinq libellés pour
      // un même écran. Deux langues qui rendraient le même prouveraient qu'une
      // d'elles n'est pas chargée.
      expect(
        titres.values.toSet().length,
        5,
        reason: 'Deux langues rendent le meme titre : $titres',
      );
    });

    test(
      'les pluriels ICU rendent la bonne branche dans chaque langue',
      () async {
        for (final locale in AppLocalizations.supportedLocales) {
          final t = await AppLocalizations.delegate.load(locale);
          final zero = t.homeEntryCount(0);
          final un = t.homeEntryCount(1);
          final plusieurs = t.homeEntryCount(7);
          expect(zero, isNotEmpty);
          expect(plusieurs, contains('7'));

          // On compare les FORMES, chiffres neutralises.
          //
          // La version precedente comparait les chaines telles quelles, et le
          // controle negatif l'a prise en defaut : en retirant la branche `=1`
          // de l'italien, `homeEntryCount` rendait « 1 voci » et « 7 voci » —
          // deux chaines bien differentes, par leur seul chiffre. Le test
          // passait sur un pluriel casse. Seul `tool/verifier_l10n.py` l'avait vu.
          String forme(String s) => s.replaceAll(RegExp(r'\d+'), '#');
          expect(
            forme(un),
            isNot(equals(forme(plusieurs))),
            reason:
                '[${locale.languageCode}] singulier et pluriel ont la meme '
                'forme (« $un » / « $plusieurs ») : la branche =1 est perdue.',
          );
          expect(
            forme(zero),
            isNot(equals(forme(plusieurs))),
            reason:
                '[${locale.languageCode}] zero et pluriel ont la meme forme '
                '(« $zero » / « $plusieurs ») : la branche =0 est perdue.',
          );
        }
      },
    );

    test('les placeholders sont substitues, pas affiches tels quels', () async {
      for (final locale in AppLocalizations.supportedLocales) {
        final t = await AppLocalizations.delegate.load(locale);
        final rendu = t.entryDetailCopiedSnack('Mot de passe', 30);
        expect(rendu, contains('30'));
        expect(rendu, isNot(contains('{seconds}')));
        expect(rendu, isNot(contains('{label}')));
      }
    });

    test('DateFormat sait formater dans chaque langue', () async {
      // `DateFormat.yMd(code)` leve `LocaleDataException` tant que les donnees
      // `intl` ne sont pas chargees — et il le ferait a l'ouverture d'une
      // fiche, loin de la cause. Ce qui les charge dans l'application, c'est
      // `GlobalMaterialLocalizations.delegate.load()`
      // (material_localizations.dart:735 appelle `loadDateIntlDataIfNotLoaded`).
      //
      // Le test doit donc emprunter le MEME chemin que l'application. Une
      // premiere version appelait `DateFormat` directement : elle echouait, et
      // son echec ne decrivait pas l'application mais le banc de test.
      for (final locale in AppLocalizations.supportedLocales) {
        await GlobalMaterialLocalizations.delegate.load(locale);
      }

      final date = DateTime(2026, 9, 20, 14, 30);
      final rendus = <String>{};
      for (final code in appLanguageCodes) {
        final texte = DateFormat.yMd(code).add_Hm().format(date);
        expect(texte, isNotEmpty);
        expect(texte, contains('2026'));
        rendus.add(texte);
      }
      // Au moins deux conventions distinctes parmi les cinq (en = M/d/y,
      // fr/de/it/es = d/M/y) : preuve que la locale est bien prise en compte.
      expect(rendus.length, greaterThan(1));
    });

    test('les cinq langues sont supportees par Flutter lui-meme', () {
      // `GlobalMaterialLocalizations.load` porte `assert(isSupported(locale))`.
      // Une langue absente de `kMaterialSupportedLanguages` ferait donc
      // planter l'application en mode debug des le changement de langue — et
      // en production, les boutons du framework resteraient en anglais.
      for (final locale in AppLocalizations.supportedLocales) {
        expect(
          GlobalMaterialLocalizations.delegate.isSupported(locale),
          isTrue,
          reason:
              '${locale.languageCode} n\'est pas fourni par '
              'flutter_localizations : les libelles du framework '
              '(« Annuler », selecteur de date…) resteraient en anglais.',
        );
      }
    });
  });
}
