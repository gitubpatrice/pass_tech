<p align="center">
  <img src="assets/icon.png" alt="Pass Tech" width="160" height="160">
</p>

<h1 align="center">Pass Tech</h1>

<p align="center">
  <strong>Un gestionnaire de mots de passe qui reste sur votre téléphone.</strong><br>
  Pas de cloud. Pas de compte. Pas de traceur.
</p>

<p align="center">
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/promises.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/promises.yml/badge.svg" alt="Promises"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Licence : Apache 2.0"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/releases/latest"><img src="https://img.shields.io/github/v/release/gitubpatrice/pass_tech?color=brightgreen&label=release" alt="Dernière version"></a>
  <img src="https://img.shields.io/badge/built%20with-Kotlin%20%2B%20Compose-7F52FF.svg" alt="Écrit en Kotlin et Jetpack Compose">
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84.svg" alt="Android 8+">
</p>

<p align="center">
  <a href="README.md">English</a> · <strong>Français</strong>
</p>

> Vos secrets ne quittent jamais votre téléphone.

---

> **La 3.0.0 est une réécriture, et une application différente.** Les versions 2.x étaient écrites en
> Flutter et s'installaient sous `com.passtech.pass_tech` ; celle-ci est en Kotlin et s'installe sous
> `com.filestech.pass_tech`. Elles cohabitent. Pour passer de l'une à l'autre : exportez une
> sauvegarde `.ptbak` depuis la 2.x, importez-la ici, puis retirez l'ancienne quand vous serez
> satisfait.

## Pourquoi Pass Tech

La plupart des gestionnaires synchronisent par leur propre cloud, ce qui revient à faire une
confiance totale à l'éditeur. Pass Tech prend le parti inverse : **aucun serveur**, aucun compte,
aucune fuite de backend possible, puisqu'il n'y a pas de backend.

- **Local** — le coffre est un fichier du dossier privé de l'application et ne va nulle part ailleurs
- **Code ouvert** — licence Apache 2.0, et chaque version publie le SHA-256 de chacun de ses fichiers
- **Lié au téléphone** — une clé de la puce sécurisée participe à chaque essai de déverrouillage
- **Aucune bibliothèque Google** — ni Play Services, ni ML Kit, ni Firebase, ni télémétrie
- **Pour le jour où on vous force à l'ouvrir** — coffre leurre, mode panique, héritage
- **Deux appels réseau, tous deux nommés** — la vérification de mise à jour et le contrôle des fuites

## Fonctions

- Mots de passe, cartes bancaires et notes sécurisées, avec un générateur : 8 à 64 caractères, ou une
  passphrase tirée d'une liste de mots dans la langue de l'application
- Codes à deux facteurs TOTP (RFC 6238) — collez le lien `otpauth://` affiché sous le QR code d'un site
- Recherche par titre, identifiant, adresse ou contenu ; quatre tris
- Audit de sécurité : faibles, réutilisés, sans deuxième facteur ; contrôle des fuites via Have I Been Pwned
- Contrôle d'adresse avant toute copie de mot de passe, sur neuf navigateurs, avec plusieurs adresses par entrée
- Sauvegarde chiffrée `.ptbak`, export en clair, import depuis Chrome, Edge, Bitwarden JSON ou CSV
- La politique de confidentialité et les conditions se lisent **dans** l'application, sans réseau
- Cinq langues — anglais, français, allemand, italien, espagnol — choisies dans l'application, quelle
  que soit la langue du téléphone

### Si on vous force à l'ouvrir

- **Coffre leurre** — un deuxième mot de passe maître ouvre un deuxième coffre avec ses propres
  entrées. Trois emplacements existent dès le premier lancement, tenus à la même taille : rien dans
  les fichiers ne dit combien vous en utilisez.
- **Mode panique** — verrouille, vide le presse-papiers, désarme l'empreinte, retire le contrôle
  d'adresse et remplace le nom et l'icône sur l'écran d'accueil par une calculatrice qui fonctionne
  vraiment. Rien n'est supprimé.
- **Héritage** — une personne que vous choisissez ouvre un instantané en lecture seule du coffre après
  un silence assez long de votre côté. Sur le téléphone, sans cloud et sans tiers.

## Sécurité

| Élément | Choix |
|---|---|
| Dérivation de clé | **Argon2id** (RFC 9106) — m = 19 Mio, t = 2, p = 1, sortie de 32 octets (OWASP 2024 mobile) |
| Étape matérielle | **HMAC-SHA256 calculé DANS le Keystore d'Android**, sur `domaine \|\| sortie Argon2id`, avec une clé qui n'en sort jamais |
| Clé finale | `HKDF-SHA256(sel, argon \|\| hmac, info, 32)` — `domaine` et `info` séparent un fichier de coffre d'un instantané d'héritage |
| Chiffrement | **AES-256-GCM** (NIST SP 800-38D), nonce de 96 bits, tag de 128 bits |
| Déni plausible | Trois emplacements créés au premier lancement, tous de la même taille ; les inutilisés contiennent des données au hasard sous une clé qui n'existe nulle part |
| Biométrie | Keystore d'Android + BiometricPrompt CryptoObject, invalidée si les empreintes du téléphone changent |
| Anti-force-brute | Cinq essais libres, puis 30 s → 30 min, comptés dans le fichier d'état chiffré et survivant à un redémarrage |
| Captures d'écran | `FLAG_SECURE` sur tous les écrans tant que le réglage est actif, aperçu des applications récentes compris |
| Presse-papiers | Vidé après un délai choisi, même en arrière-plan, et marqué sensible sur Android 13+ |
| Contrôles de l'appareil | Détection du root, de l'émulateur et du débogueur, affichée sur l'écran de déverrouillage. Elle avertit, elle ne bloque jamais |
| Sauvegarde | Sauvegarde cloud d'Android et transfert d'un téléphone à l'autre exclus, pour tous les domaines |
| Signature de l'APK | v2, v3 et v4 ; **pas de v1**, le schéma que Janus (CVE-2017-13156) attaque |
| Mises à jour | SHA-256 publié pour chaque fichier de chaque version |

Le jeu d'autorisations est figé dans
[`config/expected-permissions.txt`](config/expected-permissions.txt) et vérifié **sur le manifeste
fusionné de l'APK construit** par le workflow `Promises`, dans les deux sens : une autorisation qui
apparaît sans y figurer fait échouer la construction, et une qui y figure et disparaît aussi.

Voir [THREAT_MODEL.md](THREAT_MODEL.md) pour ce qui est protégé, contre qui, **et ce qui ne l'est
pas**. Voir [SECURITY.md](SECURITY.md) pour signaler une vulnérabilité.

## Captures d'écran

[fastlane/metadata/android/en-US/images/phoneScreenshots](fastlane/metadata/android/en-US/images/phoneScreenshots)

## Installation

### Option 1 — Obtainium (recommandé, mises à jour automatiques)

1. Installez [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest)
2. Ajoutez cette URL : `https://github.com/gitubpatrice/pass_tech`

### Option 2 — APK direct

Téléchargez l'APK depuis
[la dernière version](https://github.com/gitubpatrice/pass_tech/releases/latest) (Android 8.0 et plus).

**Vérifier l'intégrité** :
```bash
sha256sum <le fichier que vous avez téléchargé>
```
L'empreinte doit correspondre à celle publiée pour ce même fichier sur la page de la version. Si les
deux diffèrent, ne l'installez pas.

> **Samsung One UI 6.1+** : si l'installation est bloquée, désactivez temporairement
> *Paramètres → Sécurité et confidentialité → Blocage auto*.

## Autorisations

Mesurées sur l'APK construit, pas lues dans le manifeste source :

| Autorisation | Pourquoi |
|---|---|
| `INTERNET` | La vérification de mise à jour (GitHub Releases) et le contrôle des fuites (HIBP, k-anonymat, sur demande). Aucune autre requête. |
| `USE_BIOMETRIC` | Déverrouillage par empreinte optionnel, via `androidx.biometric`. |
| `USE_FINGERPRINT` | Le même, sur Android 8 (API < 28), où `androidx.biometric` en a encore besoin. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Ajoutée par une bibliothèque AndroidX ; elle permet à l'application de s'envoyer un message à elle-même, et rien d'autre. |

Ni caméra, ni contacts, ni position, ni stockage : l'application demande un fichier au système quand
vous exportez ou importez, et le système lui donne ce fichier-là.

Le service d'accessibilité du contrôle d'adresse **ne** figure **pas** dans cette liste. Android
l'accorde à part, il est désactivé au manifeste tant que le réglage n'est pas activé, et éteindre le
réglage redésactive le composant — ce qui reprend l'autorisation.

## Construire depuis les sources

Prérequis : JDK 17, SDK Android avec compileSdk 37. Tout le reste vient de Gradle.

```bash
./gradlew assembleRelease
```

La construction release a besoin d'un keystore de signature, déclaré dans `keystore.properties` à la
racine (non versionné) :

```properties
storeFile=../keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Sans lui, la construction release compile quand même, non signée. Le contrôle local auquel le projet
est tenu :

```bash
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug detekt
```

## Documentation

- [LICENSE](LICENSE) — licence Apache 2.0
- [THREAT_MODEL.md](THREAT_MODEL.md) — ce qui est protégé, contre qui, et ce qui ne l'est pas
- [PRIVACY.md](PRIVACY.md) — politique de confidentialité, dans les cinq langues livrées
- [TERMS.md](TERMS.md) — conditions d'utilisation, de même
- [SECURITY.md](SECURITY.md) — politique de signalement de vulnérabilité
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dépendances tierces
- [NOTICE](NOTICE) — mentions d'attribution Apache 2.0

## Liens

- [files-tech.com/pass-tech.php](https://www.files-tech.com/pass-tech.php) — page produit
- [Releases](https://github.com/gitubpatrice/pass_tech/releases) — APK signés
- [contact@files-tech.com](mailto:contact@files-tech.com) — support et signalements

## Licence

Copyright 2026 Files Tech / Patrice Haltaya

Distribué sous licence Apache, version 2.0. Voir [LICENSE](LICENSE) pour le texte complet.

Pass Tech est fourni « en l'état », sans garantie d'aucune sorte. Le coffre est chiffré par votre mot
de passe maître **et** par une clé gardée dans la puce sécurisée de ce téléphone : si vous oubliez le
mot de passe maître, ou si le téléphone est réinitialisé, le coffre est irrécupérable — par vous, par
nous, par quiconque. Faites régulièrement une sauvegarde chiffrée `.ptbak` et gardez-la ailleurs que
sur le téléphone.
