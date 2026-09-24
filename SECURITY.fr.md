# Politique de sécurité — Pass Tech

[English](SECURITY.md) · **Français**

> Pass Tech est un gestionnaire de mots de passe. La sécurité est la priorité absolue de ce projet,
> et tout signalement responsable est traité en priorité maximale.

## Versions suivies

Seule la dernière version publiée sur GitHub Releases est activement maintenue côté sécurité.

| Version | Identifiant d'application | Suivie |
|---|---|---|
| 3.0.x | `com.filestech.pass_tech` | ✅ |
| 2.7.x | `com.passtech.pass_tech` | ❌ **fin de vie** — voir plus bas |
| < 2.7 | `com.passtech.pass_tech` | ❌ |

**La 3.0.0 est une réécriture, en Kotlin, et une application différente.** Elle s'installe à côté de
la 2.x, pas par-dessus. Pour passer de l'une à l'autre : exportez une sauvegarde `.ptbak` depuis la
2.x, importez-la dans la 3.0, puis retirez l'ancienne quand vous serez satisfait. L'historique de la
2.x vit dans les tags de ce dépôt ; son document de sécurité est celui livré avec elle.

**La 2.7.1 est la dernière version Flutter, et elle est finie.** Elle ne recevra plus aucun travail,
correctifs de sécurité compris. Son téléchargement reste en ligne sur GitHub Releases au lieu d'être
retiré, pour une seule raison : elle tourne sur Android 7, et la 3.0 demande Android 8. La retirer
enlèverait la seule version qui fonctionne aux téléphones qui ne peuvent pas installer la
remplaçante, sans rien leur donner en échange. Rien d'autre ne doit y renvoyer.

Aucune sauvegarde ne se retrouve orpheline, dans un sens comme dans l'autre : la 3.0 lit tous les
`.ptbak` jamais écrits — v1, v2 et v3 — et le fichier qu'elle écrit se réimporte dans la 2.7.1.

## Signaler une vulnérabilité

**N'ouvrez pas d'issue publique sur GitHub** — un gestionnaire de mots de passe exige une divulgation
strictement coordonnée.

📧 **Un courriel, chiffré si vous le pouvez, à : contact@files-tech.com**

Objet : `[SECURITY] Pass Tech — <description courte>`.

Merci d'y mettre :

- une description claire de la vulnérabilité ;
- les étapes pour la reproduire (une preuve de concept est bienvenue, pas obligatoire) ;
- l'impact que vous voyez — compromission du coffre, extraction de clé, contournement de la
  biométrie, un coffre leurre dont on peut prouver l'existence ;
- la version touchée, et le téléphone et la version d'Android sur lesquels vous l'avez vue ;
- un correctif proposé, si vous en avez un.

## Délais de réponse

- Accusé de réception : sous 48 heures.
- Première évaluation : sous 7 jours.
- Correctif, selon la gravité :
  - **Critique** — compromission du coffre, fuite de clé, coffre leurre prouvable → sous 7 jours ;
  - **Majeur** → sous 30 jours ;
  - **Mineur** → à la version suivante.

## Divulgation responsable

Merci de ne rien divulguer publiquement avant qu'un correctif soit publié et qu'une fenêtre de mise à
jour raisonnable — 90 jours au minimum — ait été laissée aux personnes qui utilisent l'application.

## Plateforme minimale

Pass Tech 3.0 demande **Android 8.0 (API 26) ou plus**.

- `minSdk = 26`, déclaré dans `app/build.gradle.kts`, ce que supposent le travail sur les clés
  matérielles et l'usage du Keystore.
- Signature de l'APK **v2, v3 et v4 seulement** (`enableV1Signing = false`), ce qui neutralise
  CVE-2017-13156 (Janus) : cette attaque injecte du DEX dans un APK signé en v1.
- Android 7 et antérieurs ne sont pas pris en charge par cette version. La 2.x descendait à
  Android 7 et n'est plus maintenue côté sécurité.

## Vérifier ce que vous avez installé

Chaque version publie le SHA-256 de **chacun de ses fichiers**. Avant d'installer :

```bash
sha256sum <le fichier que vous avez téléchargé>
```

Le résultat doit correspondre exactement à la valeur publiée pour ce même fichier. Sinon,
**ne l'installez pas**.

## Ce qui est protégé, et ce qui ne l'est pas

Le modèle de menace est un document à part, et c'est celui qu'il faut lire : il nomme les
adversaires, la portée exacte de chaque protection, et les risques résiduels assumés plutôt que
résolus — y compris les limites du coffre leurre et du mode panique.

→ [THREAT_MODEL.fr.md](THREAT_MODEL.fr.md)

## Périmètre

**Dans le périmètre** : le coffre et son chiffrement, la dérivation de clé et son étape matérielle,
la garde anti-force-brute, la liaison biométrique, le coffre leurre et le mode panique, l'instantané
d'héritage, le contrôle d'adresse et le service d'accessibilité qu'il utilise, le presse-papiers, les
chemins de sauvegarde et d'import, la vérification de mise à jour, et le jeu d'autorisations de l'APK
publié.

**Hors périmètre** : un téléphone rooté ou compromis, une attaque physique sur le matériel sécurisé,
Android lui-même, et tout navigateur ou site que le contrôle d'adresse lit. Ces points sont énoncés
au §5 de [THREAT_MODEL.fr.md](THREAT_MODEL.fr.md) plutôt que traités comme des défauts.

## Ce que la construction vérifie à chaque commit

- `CI` — construction, tests unitaires, lint Android et detekt, sans baseline et sans écart toléré.
- `Promises` — construit un APK **release** et compare son manifeste **fusionné** à
  [`config/expected-permissions.txt`](config/expected-permissions.txt), dans les deux sens. Une
  autorisation qui apparaît sans y figurer fait échouer la construction, et une qui y figure et
  disparaît aussi. L'app Flutter a livré `ACCESS_NETWORK_STATE` sans que ce soit déclaré nulle part
  dans le dépôt ; cela ne peut plus se reproduire en silence.
- `CodeQL` — analyse statique des sources Kotlin.
