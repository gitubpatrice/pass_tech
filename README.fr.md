<p align="center">
  <img src="assets/icon.png" alt="Pass Tech" width="160" height="160">
</p>

<h1 align="center">Pass Tech</h1>

<p align="center">
  <strong>Gestionnaire de mots de passe Android 100 % local.</strong><br>
  Aucun cloud. Aucun tracker. Aucun compte.
</p>

<p align="center">
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/releases/latest"><img src="https://img.shields.io/github/v/release/gitubpatrice/pass_tech?color=brightgreen&label=release" alt="Latest release"></a>
  <a href="https://flutter.dev"><img src="https://img.shields.io/badge/built%20with-Flutter-02569B.svg" alt="Built with Flutter"></a>
  <img src="https://img.shields.io/badge/platform-Android%207%2B-3DDC84.svg" alt="Android 7+">
</p>

<p align="center">
  <a href="README.md">English</a> · <strong>Français</strong>
</p>

> Vos secrets ne quittent jamais votre téléphone.

---

## Pourquoi Pass Tech

La majorité des gestionnaires de mots de passe synchronisent vos données via leur cloud — ce qui implique une confiance totale dans le fournisseur. Pass Tech prend le parti opposé : **aucun serveur**, aucun compte, aucune fuite possible côté backend, parce qu'il n'y a pas de backend.

- **100 % local** — coffre chiffré stocké uniquement dans la mémoire interne de l'app
- **Open source** — Apache License 2.0, code auditable
- **Crypto v4 durcie** — Argon2id + AES-GCM-256 + KEK liée au matériel (StrongBox/TEE)
- **Aucune bibliothèque Google** — ni Play Services, ni ML Kit, ni Firebase, ni télémétrie
- **Pack confidentialité radicale** — coffre leurre, mode panique, héritage, anti-hameçonnage
- **Aucune permission inutile** — `INTERNET` uniquement pour la vérification de mise à jour et le contrôle HIBP optionnel

## Fonctionnalités

- Mots de passe avec générateur configurable (8 à 64 caractères, ou phrases de passe Diceware en français)
- TOTP 2FA (RFC 6238) — collez l'URI `otpauth://`, le secret en est extrait automatiquement
- Cartes bancaires (numéro, CVV, expiration, PIN — affichage 3D)
- Notes sécurisées
- Recherche locale par titre, identifiant, URL ou contenu
- Audit de sécurité (faibles, doublons, anciens, sans 2FA)
- Vérification de fuites HIBP (k-anonymat, optionnelle)
- Export / import du coffre chiffré (`.ptbak`)
- Mises à jour vérifiables via GitHub Releases (SHA-256 publié)

### Pack confidentialité radicale

- **Coffre leurre** — un 2ᵉ mot de passe ouvre un faux coffre crédible (déni plausible, temps de réponse aligné).
- **Mode panique** — verrouille tout, efface le presse-papiers et camoufle l'icône en calculatrice fonctionnelle.
- **Héritage après inactivité** — un proche peut accéder au coffre après une période d'inactivité prolongée, sans aucun cloud.
- **Anti-hameçonnage par domaine** — vérifie le domaine du navigateur avant copie ; alerte sur le typosquattage.
- **Biométrie liée au matériel** (optionnelle) — clé liée à l'Android Keystore, authentification biométrique exigée pour la lire.

## Sécurité

| Composant | Choix (coffre v4) |
|---|---|
| Dérivation de clé | **Argon2id** (RFC 9106) — m = 19 Mio, t = 2, p = 1, L = 32 (OWASP 2024) |
| Chiffrement | **AES-256-GCM** (NIST SP 800-38D), nonce 96 bits, étiquette 128 bits |
| Anti-downgrade | L'AAD du GCM lie `version \| alias KEK \| paramètres KDF` |
| Clé liée au matériel | **KEK AES/GCM/NoPadding 256** dans l'Android Keystore (StrongBox si disponible, repli TEE) |
| Dérivation finale | `HKDF-SHA256(sel, pwHash \|\| hwSecret, "pt:v4", 32)` |
| Déni plausible | Deux alias KEK créés systématiquement à l'installation ; les deux fichiers de coffre gardent la même taille |
| Biométrie | Android Keystore + BiometricPrompt CryptoObject (`setUserAuthenticationRequired(true)`) |
| Anti-force-brute | Verrouillage progressif après 5 échecs (30 s → 30 min), ancré sur `elapsedRealtime` |
| Captures d'écran | `FLAG_SECURE`, réarmé en natif avant que le système ne prenne sa vignette |
| Presse-papiers | Effacement automatique + drapeau `IS_SENSITIVE` (Android 13+) |
| RASP | Détection root, émulateur et débogueur |
| Effacement RAM | Clé maîtresse effacée après usage et au verrouillage |
| Signature APK | v2+ uniquement (parade CVE-2017-13156 / Janus) |
| Mises à jour | SHA-256 publié dans chaque release GitHub |

La liste des permissions est figée dans [`android/expected-permissions.txt`](android/expected-permissions.txt) et vérifiée **sur l'APK construit à chaque commit**, avec un contrôle de traceurs Exodus Privacy.

Voir [THREAT_MODEL.md](THREAT_MODEL.md) pour ce qui est protégé, contre qui, **et ce qui ne l'est pas** — limites assumées comprises. Voir [SECURITY.md](SECURITY.md) pour signaler une vulnérabilité.

## Captures d'écran

*À venir.*

## Installation

### Option 1 — Obtainium (recommandé, mises à jour automatiques)

1. Installer [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest)
2. Ajouter cette URL : `https://github.com/gitubpatrice/pass_tech`

### Option 2 — APK direct

Télécharger `app-arm64-v8a-release.apk` depuis [la dernière release](https://github.com/gitubpatrice/pass_tech/releases/latest) (ABI **arm64-v8a**, Android 7.0+).

**Vérifier l'intégrité** :
```bash
sha256sum app-arm64-v8a-release.apk
```
Le hash doit correspondre à celui publié dans les notes de release.

> **Samsung One UI 6.1+** : si l'installation est bloquée, désactivez temporairement *Réglages → Sécurité et confidentialité → Auto Blocker*.

## Permissions

| Permission | Pourquoi |
|---|---|
| `INTERNET` | Vérification de mise à jour (GitHub Releases) et contrôle HIBP (k-anonymat, optionnel). Aucune autre requête réseau. |
| `USE_BIOMETRIC` | Déverrouillage biométrique optionnel via BiometricPrompt. |
| `USE_FINGERPRINT` | Non déclarée par Pass Tech : réajoutée par le plugin `biometric_storage`. Nécessaire à `androidx.biometric` sur les API 24 à 27. |

Pas de localisation, pas d'accès aux contacts, pas d'accès aux médias, pas de stockage externe (hors export volontaire), **pas de caméra**.

`CAMERA` et `ACCESS_NETWORK_STATE` ont été retirées le 2026-08-03 avec le scan de QR code, qui reposait sur Google ML Kit. Un secret 2FA s'ajoute désormais en collant l'URI `otpauth://` que les services affichent sous leur QR code.

## Compiler depuis les sources

Pré-requis : Flutter 3.x, SDK Dart `^3.11.5`, JDK 17, Android SDK avec `minSdk = 24`.

```bash
flutter pub get
flutter build apk --release --split-per-abi
```

La compilation Android en release exige un keystore de signature configuré dans `android/key.properties` (non versionné) :

```properties
storePassword=...
keyPassword=...
keyAlias=...
storeFile=../keystore.jks
```

## Documentation

- [LICENSE](LICENSE) — Apache License 2.0
- [THREAT_MODEL.md](THREAT_MODEL.md) — ce qui est protégé, contre qui, et ce qui ne l'est pas
- [PRIVACY.md](PRIVACY.md) / [PRIVACY.fr.md](PRIVACY.fr.md) — politique de confidentialité
- [TERMS.md](TERMS.md) / [TERMS.fr.md](TERMS.fr.md) — conditions d'utilisation
- [SECURITY.md](SECURITY.md) — politique de signalement de vulnérabilités
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dépendances tierces
- [NOTICE](NOTICE) — mentions Apache 2.0

## Liens

- [files-tech.com/pass-tech.php](https://www.files-tech.com/pass-tech.php) — page produit
- [Releases](https://github.com/gitubpatrice/pass_tech/releases) — APK signés
- [contact@files-tech.com](mailto:contact@files-tech.com) — support et signalement de vulnérabilités

## Licence

Copyright 2026 Files Tech / Patrice Haltaya

Distribué sous Apache License, Version 2.0. Voir [LICENSE](LICENSE) pour le texte complet.

Pass Tech est fourni « tel quel », sans garantie d'aucune sorte. Les données stockées sont chiffrées avec votre mot de passe maître et liées à la KEK matérielle de votre appareil — **si vous perdez le mot de passe maître, ou si l'appareil est réinitialisé ou son Keystore effacé, le coffre est irrécupérable**. Pensez à exporter régulièrement une sauvegarde chiffrée (`.ptbak`).
