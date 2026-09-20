#!/usr/bin/env python3
"""Controle de parite des fichiers .arb — lance en CI et en local.

Ce que ce controle verifie, et pourquoi chaque point existe :

  1. MEMES CLES partout. Une cle absente d'une traduction fait retomber
     silencieusement l'application sur l'anglais pour cette chaine : rien ne
     plante, personne ne le voit.

  2. MEMES PLACEHOLDERS. Un `{count}` perdu a la traduction ne casse pas la
     compilation de la meme facon selon les cas, et peut afficher un texte
     ampute. Un placeholder INVENTE, lui, fait echouer `gen-l10n`.

  3. MEME FORME ICU. Les branches `plural` (=0, =1, other) doivent exister des
     deux cotes : `other` est obligatoire, et une branche `=1` oubliee donne
     « 1 Eintraege » au lieu de « 1 Eintrag ».

  4. AUCUNE VALEUR RESTEE EN ANGLAIS par simple recopie du gabarit. Controle
     indicatif : certaines chaines sont legitimement identiques (« URL »,
     « CVV », « Pass Tech »), d'ou une liste d'exceptions explicite.

  5. PAS DE CLE ORPHELINE : toute cle du gabarit doit etre appelee quelque part
     dans `lib/`, sinon elle grossit l'APK et les fichiers de traduction sans
     que personne ne la voie jamais.

  6. LES QUATRE LISTES DE LANGUES CONCORDENT. La langue est declaree a quatre
     endroits qui s'ignorent : les fichiers .arb, `appLanguageCodes` dans
     `lib/main.dart`, les dossiers `values-*` des ressources Android, et
     `resourceConfigurations` dans `android/app/build.gradle.kts`. Ce dernier
     FILTRE l'APK : une locale absente de sa liste est retiree a la
     construction, sans erreur ni avertissement. En v2.7.0 il valait
     `("en", "fr")` alors que l'application en proposait cinq — les traductions
     Flutter vivant dans `libapp.so`, le defaut etait invisible partout
     ailleurs, et le libellé de camouflage du mode panique serait reste en
     francais sur un telephone allemand.

Sortie : code 0 si tout va bien, 1 sinon. `--negatif` execute le CONTROLE
NEGATIF : il fabrique des fichiers volontairement fautifs en memoire et exige
que chaque regle les rejette. Un controle qu'on n'a jamais vu echouer ne prouve
rien — c'est la lecon de SMS Tech v1.28.12.
"""

import io
import json
import os
import re
import sys

DOSSIER = os.path.join("lib", "l10n")
GABARIT = "en"
LANGUES = ["fr", "de", "it", "es"]

PLACEHOLDER = re.compile(r"\{([a-zA-Z][a-zA-Z0-9_]*)\}")
ICU = re.compile(r"\{([a-zA-Z][a-zA-Z0-9_]*),\s*(plural|select),")
BRANCHE_ICU = re.compile(r"(=\d+|other|zero|one|two|few|many)\s*\{")

# Chaines dont l'identite avec l'anglais est NORMALE : sigles, marques, noms de
# formats, endonymes du selecteur de langue, gabarits purement numeriques.
IDENTIQUES_ATTENDUES = {
    "appTitle", "homeTitle", "categoryWeb", "categoryEmail", "categoryApps",
    "entryDetailFieldUrl", "entryDetailFieldCvv", "entryEditFieldCvv",
    "heirViewFieldUrl", "entryEditHintUrl", "entryEditHintCvv",
    "entryEditHintPin", "entryEditHintCardNumber", "entryEditFieldTitleRequired",
    "actionOk", "generatorTitle", "homeTooltipGenerator",
    "aboutFeatureGeneratorLabel", "importFormatBitwarden", "importFormatPassTech",
    "settingsLanguageFrench", "settingsLanguageEnglish", "settingsLanguageGerman",
    "settingsLanguageItalian", "settingsLanguageSpanish",
    "homeSortAlpha", "homeSortAlphaDesc", "entryDetailFieldPinCopy",
    "generatorCharsUpper", "generatorCharsLower", "generatorCharsDigits",
    "generatorCharsSymbols", "settingsThemeSystem", "entryDetailFieldNumber",
    "aboutFeatureBackupLabel", "settingsBackupEncryptedTitle",
    "entryEditHintExpiry", "unitSecondsShort", "unitMinutesShort",
    "unitMinutesSecondsShort", "unitDaysShort",
}

# Coincidences PROPRES A UNE LANGUE. Elles sont declarees ici, et non dans la
# liste ci-dessus, parce qu'une exception globale serait un trou : « Bank » est
# le mot allemand juste, mais en italien la meme valeur signalerait une vraie
# recopie. Ajouter une entree ici demande donc de savoir DANS QUELLE LANGUE le
# mot coincide.
IDENTIQUES_PAR_LANGUE = {
    "de": {
        "aboutHelpUpdateTitle",       # Updates
        "categoryBank",               # Bank
        "entryDetailFieldIssuer",     # Bank
        "entryEditFieldUrlOptional",  # URL (optional)
        "legalBugBodyVersion",        # Version
        "passphraseLabel",            # Passphrase
    },
    "fr": {
        "aboutFeatureBruteforceLabel",  # Anti-brute force
        "aboutFeaturePhishingLabel",    # Anti-phishing
        "auditScoreExcellent",          # Excellent
        "auditStatNotes",               # Notes
        "entryDetailFieldNotes",        # Notes
        "heirViewFieldNotes",           # Notes
        "homeFilterNotes",              # Notes
        "entryEditHintIssuer",          # noms de banques, identiques
        "exportShareSubject",           # Pass Tech — export
        "generatorModePhrase",          # Phrase
        "generatorOptionsHeader",       # Options
        "generatorStrengthSuffix",      # {label} · {bits} bits
        "legalBugBodyVersion",          # Version
        "passphraseLabel",              # Passphrase
        "passphraseLabelMin",           # Passphrase (min. 12)
        "settingsAutoLock1Min",         # 1 minute
        "settingsAutoLock5Min",         # 5 minutes
        "settingsAutoLock15Min",        # 15 minutes
        "settingsAutoLock30Min",        # 30 minutes
    },
    "it": {
        "aboutFeaturePhishingLabel",      # Anti-phishing
        "aboutSectionPrivacy",            # Privacy
        "entryDetailFieldPassword",       # Password — emprunt courant en IT
        "entryEditFieldPassword",         # Password
        "entryEditHintPassword",          # Password
        "entryTypePassword",              # Password
        "heirViewFieldPassword",          # Password
        "homeAddPassword",                # Password
        "passphraseLabel",                # Passphrase
        "passphraseLabelMin",             # Passphrase (min. 12)
        "settingsAntiPhishingDialogTitle",  # Anti-phishing
        "settingsSectionAntiPhishing",      # Anti-phishing
    },
    "es": {
        "generatorStrengthSuffix",  # {label} · {bits} bits
        "genericError",             # Error: {error}
    },
}


def charger(langue):
    chemin = os.path.join(DOSSIER, "app_%s.arb" % langue)
    with io.open(chemin, encoding="utf-8") as f:
        return json.load(f)


def cles(d):
    return {k for k in d if not k.startswith("@")}


def branches(valeur):
    """Ensemble des branches ICU d'une chaine, tous arguments confondus."""
    if not ICU.search(valeur):
        return frozenset()
    return frozenset(BRANCHE_ICU.findall(valeur))


def controler(gabarit, traduction, langue, orphelines_aussi=True,
              sources=None):
    """Rend la liste des anomalies. Vide = conforme."""
    erreurs = []
    kg, kt = cles(gabarit), cles(traduction)

    for k in sorted(kg - kt):
        erreurs.append("[%s] cle MANQUANTE : %s" % (langue, k))
    for k in sorted(kt - kg):
        erreurs.append("[%s] cle EN TROP (absente du gabarit) : %s"
                       % (langue, k))

    tolerees = IDENTIQUES_ATTENDUES | IDENTIQUES_PAR_LANGUE.get(langue, set())
    identiques = []
    for k in sorted(kg & kt):
        vg, vt = gabarit[k], traduction[k]

        pg = set(PLACEHOLDER.findall(vg)) | {m[0] for m in ICU.findall(vg)}
        pt = set(PLACEHOLDER.findall(vt)) | {m[0] for m in ICU.findall(vt)}
        if pg != pt:
            erreurs.append(
                "[%s] %s : placeholders %s attendus, %s trouves"
                % (langue, k, sorted(pg), sorted(pt))
            )

        bg, bt = branches(vg), branches(vt)
        if bg != bt:
            erreurs.append(
                "[%s] %s : branches ICU %s attendues, %s trouvees"
                % (langue, k, sorted(bg), sorted(bt))
            )

        if vg == vt and k not in tolerees and len(vg) > 3:
            identiques.append(k)

    if identiques:
        erreurs.append(
            "[%s] %d valeurs IDENTIQUES a l'anglais (recopie ?) : %s"
            % (langue, len(identiques), ", ".join(identiques[:12]))
        )

    if orphelines_aussi and sources is not None:
        for k in sorted(kg):
            if not re.search(r"\b%s\b" % re.escape(k), sources):
                erreurs.append("[gabarit] cle ORPHELINE, jamais appelee : %s"
                               % k)

    return erreurs


RES = os.path.join("android", "app", "src", "main", "res")
GRADLE = os.path.join("android", "app", "build.gradle.kts")
MAIN_DART = os.path.join("lib", "main.dart")

RE_RESCONFIG = re.compile(
    r"resourceConfigurations\.addAll\(listOf\(([^)]*)\)\)")
RE_CODES_DART = re.compile(
    r"appLanguageCodes\s*=\s*\[([^\]]*)\]")
RE_STRING_NAME = re.compile(r'<string\s+name="([^"]+)"')


def _codes_entre_guillemets(texte):
    return [m for m in re.findall(r'"([a-z]{2})"', texte)]


def controler_listes_de_langues():
    """Les quatre declarations de langues doivent decrire le meme ensemble."""
    erreurs = []
    attendu = set([GABARIT] + LANGUES)

    # 1. Les fichiers .arb presents sur le disque.
    arb = {f[4:-4] for f in os.listdir(DOSSIER)
           if f.startswith("app_") and f.endswith(".arb")}
    if arb != attendu:
        erreurs.append(
            "[langues] fichiers .arb %s, attendu %s"
            % (sorted(arb), sorted(attendu)))

    # 2. `appLanguageCodes` dans lib/main.dart — la liste que le SELECTEUR
    #    propose. L'anglais du gabarit en fait partie.
    with io.open(MAIN_DART, encoding="utf-8") as f:
        m = RE_CODES_DART.search(f.read())
    if not m:
        erreurs.append("[langues] appLanguageCodes introuvable dans %s"
                       % MAIN_DART)
    else:
        codes = set(re.findall(r"'([a-z]{2})'", m.group(1)))
        if codes != attendu:
            erreurs.append(
                "[langues] appLanguageCodes %s, attendu %s"
                % (sorted(codes), sorted(attendu)))

    # 3. `resourceConfigurations` dans le Gradle — le FILTRE de l'APK.
    with io.open(GRADLE, encoding="utf-8") as f:
        m = RE_RESCONFIG.search(f.read())
    if not m:
        erreurs.append("[langues] resourceConfigurations introuvable dans %s"
                       % GRADLE)
    else:
        codes = set(_codes_entre_guillemets(m.group(1)))
        if codes != attendu:
            erreurs.append(
                "[langues] resourceConfigurations %s, attendu %s — les locales "
                "absentes de cette liste sont RETIREES de l'APK a la "
                "construction" % (sorted(codes), sorted(attendu)))

    # 4. Les ressources Android : memes cles dans chaque `values-*`.
    defaut = os.path.join(RES, "values", "strings.xml")
    if not os.path.exists(defaut):
        return erreurs
    with io.open(defaut, encoding="utf-8") as f:
        cles_defaut = set(RE_STRING_NAME.findall(f.read()))
    for langue in LANGUES:
        chemin = os.path.join(RES, "values-%s" % langue, "strings.xml")
        if not os.path.exists(chemin):
            erreurs.append(
                "[langues] %s manquant : Android affichera le texte par defaut "
                "— en anglais — dans les reglages d'accessibilite et sous "
                "l'icone du lanceur" % chemin.replace(os.sep, "/"))
            continue
        with io.open(chemin, encoding="utf-8") as f:
            cles = set(RE_STRING_NAME.findall(f.read()))
        if cles != cles_defaut:
            erreurs.append(
                "[langues] %s : cles %s, attendu %s"
                % (chemin.replace(os.sep, "/"), sorted(cles),
                   sorted(cles_defaut)))

    erreurs.extend(controler_nom_du_service())
    erreurs.extend(controler_documents_juridiques())
    return erreurs


LEGAL = os.path.join("assets", "legal")


def controler_documents_juridiques():
    """Chaque langue proposée doit avoir SA politique et SES conditions.

    L'écran « À propos » choisissait le fichier par un test binaire
    `languageCode == 'en'` : l'anglais d'un côté, **tout le reste** renvoyé au
    français. Tant que l'application ne parlait que deux langues, le défaut
    était invisible. En ajoutant trois langues, ce même test s'est mis à servir
    la politique de confidentialité en français à un lecteur allemand.

    Le repli reste l'anglais si un fichier manque — mais alors autant le savoir
    ici plutôt que de le découvrir sur le téléphone de quelqu'un.
    """
    erreurs = []
    for langue in [GABARIT] + LANGUES:
        for document in ("PRIVACY", "TERMS"):
            chemin = os.path.join(LEGAL, "%s.%s.md" % (document, langue))
            if not os.path.exists(chemin):
                erreurs.append(
                    "[juridique] %s manquant : les lecteurs en « %s » "
                    "recevront ce document dans une autre langue"
                    % (chemin.replace(os.sep, "/"), langue))
    return erreurs


RE_LABEL_SERVICE = re.compile(r'phishing_service_label">([^<]*)')
RE_NOM_CITE = re.compile(u'[«"„](.+?)[»"“]')


def controler_nom_du_service():
    """Le nom que l'application dit de chercher doit être celui qu'Android
    affiche.

    `settingsAntiPhishingNeedsAS` envoie l'utilisateur activer une ligne
    NOMMÉE dans les réglages d'accessibilité. Ce nom-là vient des ressources
    Android, pas des .arb : les deux vivent dans des fichiers différents et
    rien ne les reliait. Deux divergences ont été trouvées le 2026-09-20 —
    « anti-hameçonnage » côté Android contre « anti-phishing » côté
    application en français, et un cadratin contre un demi-cadratin en
    allemand. Dans les deux cas l'utilisateur cherche une ligne qui n'existe
    pas sous ce nom, et la comparaison se fait sur les POINTS DE CODE : à
    l'écran, les deux tirets se ressemblent.
    """
    erreurs = []
    for langue in [GABARIT] + LANGUES:
        dossier = "values" if langue == GABARIT else "values-%s" % langue
        chemin = os.path.join(RES, dossier, "strings.xml")
        if not os.path.exists(chemin):
            continue
        with io.open(chemin, encoding="utf-8") as f:
            m = RE_LABEL_SERVICE.search(f.read())
        if not m:
            continue
        affiche = m.group(1).strip()
        cite = RE_NOM_CITE.search(charger(langue)["settingsAntiPhishingNeedsAS"])
        if not cite:
            continue
        if cite.group(1).strip() != affiche:
            erreurs.append(
                "[langues] %s : l'application dit de chercher %r, Android "
                "affiche %r" % (langue, cite.group(1).strip(), affiche))
    return erreurs


def lire_sources():
    morceaux = []
    for racine, _d, fichiers in os.walk("lib"):
        if os.path.join("lib", "l10n") in racine:
            continue
        for f in fichiers:
            if f.endswith(".dart"):
                with io.open(os.path.join(racine, f), encoding="utf-8") as fh:
                    morceaux.append(fh.read())
    return "\n".join(morceaux)


def controle_negatif():
    """Prouve que chaque regle sait echouer.

    Sans cela, ce fichier pourrait ne RIEN verifier et rendre 0 pour toujours.
    """
    gabarit = {
        "@@locale": "en",
        "salut": "Hello",
        "compte": "{count, plural, =1{1 item} other{{count} items}}",
        "titre": "Title",
    }
    cas = [
        ("cle manquante", {"@@locale": "xx", "salut": "Hallo",
                           "compte": "{count, plural, =1{1 Eintrag} "
                                     "other{{count} Eintraege}}"}),
        ("cle en trop", {"@@locale": "xx", "salut": "Hallo", "titre": "Titel",
                         "compte": "{count, plural, =1{1 Eintrag} "
                                   "other{{count} Eintraege}}",
                         "intrus": "?"}),
        ("placeholder perdu", {"@@locale": "xx", "salut": "Hallo",
                               "titre": "Titel",
                               "compte": "plusieurs elements"}),
        ("branche ICU perdue", {"@@locale": "xx", "salut": "Hallo",
                                "titre": "Titel",
                                "compte": "{count, plural, "
                                          "other{{count} Eintraege}}"}),
        ("valeur restee en anglais", {"@@locale": "xx", "salut": "Hallo",
                                      "titre": "Title",
                                      "compte": "{count, plural, "
                                                "=1{1 Eintrag} "
                                                "other{{count} Eintraege}}"}),
    ]
    echecs = []
    for nom, traduction in cas:
        erreurs = controler(gabarit, traduction, "xx", orphelines_aussi=False)
        if not erreurs:
            echecs.append("NON DETECTE : %s" % nom)
        else:
            print("  detecte — %-26s : %s" % (nom, erreurs[0]))

    # Controle positif : une traduction correcte ne doit rien declencher.
    bonne = {
        "@@locale": "xx", "salut": "Hallo", "titre": "Titel",
        "compte": "{count, plural, =1{1 Eintrag} other{{count} Eintraege}}",
    }
    faux_positifs = controler(gabarit, bonne, "xx", orphelines_aussi=False)
    if faux_positifs:
        echecs.append("FAUX POSITIF sur une traduction correcte : %s"
                      % faux_positifs)
    else:
        print("  silencieux — traduction correcte           : aucun signalement")

    return echecs


def main():
    if "--negatif" in sys.argv:
        print("Controle negatif du verificateur l10n :")
        echecs = controle_negatif()
        if echecs:
            for e in echecs:
                print("  %s" % e)
            print("\nLe verificateur NE DETECTE PAS tout ce qu'il pretend.")
            return 1
        print("\nLes 5 regles echouent quand elles doivent, et se taisent sinon.")
        return 0

    gabarit = charger(GABARIT)
    sources = lire_sources()
    total = 0

    erreurs_listes = controler_listes_de_langues()
    if erreurs_listes:
        total += len(erreurs_listes)
        for e in erreurs_listes:
            print(e)
    else:
        print("[langues] .arb, appLanguageCodes, resourceConfigurations et "
              "values-* concordent")

    for langue in LANGUES:
        erreurs = controler(gabarit, charger(langue), langue,
                            orphelines_aussi=(langue == LANGUES[0]),
                            sources=sources)
        if erreurs:
            total += len(erreurs)
            for e in erreurs:
                print(e)
        else:
            print("[%s] conforme (%d cles)" % (langue, len(cles(gabarit))))

    if total:
        print("\n%d anomalie(s). Voir tool/verifier_l10n.py pour le detail des"
              " regles." % total)
        return 1
    print("\n%d cles x %d langues : parite complete."
          % (len(cles(gabarit)), len(LANGUES) + 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
