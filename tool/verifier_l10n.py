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
