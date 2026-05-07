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

> Vos secrets ne quittent jamais votre téléphone.

---

## Pourquoi Pass Tech

La majorité des gestionnaires de mots de passe synchronisent vos données via leur cloud — ce qui implique une confiance totale dans le fournisseur. Pass Tech prend le parti opposé : **aucun serveur**, aucun compte, aucune fuite possible côté backend, parce qu'il n'y a pas de backend.

- **100 % local** — coffre chiffré stocké uniquement dans la mémoire interne de l'app
- **Open source** — Apache License 2.0, code auditable
- **Crypto v4 hardened** — Argon2id + AES-GCM-256 + KEK liée au matériel (StrongBox/TEE)
- **Pack confidentialité radicale 5/5** — coffre leurre, mode panique, héritage, anti-phishing
- **Aucune permission inutile** — `INTERNET` uniquement pour l'update GitHub et le HIBP opt-in

## Fonctionnalités

- Mots de passe avec générateur configurable (caractères 8–64 ou phrases Diceware FR)
- TOTP 2FA (RFC 6238) avec scanner QR
- Cartes bancaires (numéro, CVV, expiration, PIN — affichage 3D)
- Notes sécurisées
- Recherche locale par titre, identifiant, URL ou contenu
- Audit de sécurité (faibles, doublons, anciens, sans 2FA)
- Vérification de fuites HIBP (k-anonymity, opt-in)
- Export / import du coffre chiffré (`.ptbak`)
- Mises à jour vérifiables via GitHub Releases (SHA-256 publié)

### Pack confidentialité radicale

- **Coffre leurre** — un 2ᵉ mot de passe ouvre un faux coffre crédible (déni plausible, timing aligné).
- **Mode panique** — verrouille tout, efface le presse-papiers et camoufle l'icône en « Calculatrice ».
- **Héritage post-inactivité** — un proche peut accéder au coffre après une période d'inactivité prolongée, sans cloud.
- **Anti-phishing par domaine** — vérifie le domaine du navigateur avant copie ; alerte sur typosquatting.
- **Biométrie hardware-bound** (optionnelle) — clé liée à Android Keystore, biométrie obligatoire pour lire.

## Sécurité

| Composant | Choix (vault v4) |
|---|---|
| Dérivation de clé | **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, L = 32 (OWASP 2024) |
| Chiffrement | **AES-256-GCM** (NIST SP 800-38D), nonce 96 bits, tag 128 bits |
| Anti-downgrade | AAD GCM lie `version | alias KEK | paramètres KDF` |
| Clé liée matériel | **KEK AES/GCM/NoPadding 256** dans Android Keystore (StrongBox si dispo, fallback TEE) |
| Dérivation finale | `HKDF-SHA256(salt, pwHash || hwSecret, "pt:v4", 32)` |
| Déni plausible | 2 alias KEK créés systématiquement à l'install (timing aligné, salt dummy 32 o) |
| Biométrie | Android Keystore + BiometricPrompt CryptoObject (`setUserAuthenticationRequired(true)`) |
| Anti-brute-force | Verrouillage progressif après 5 échecs (30 s → 30 min) |
| Captures écran | `FLAG_SECURE` actif |
| Clipboard | Effacement auto + flag `IS_SENSITIVE` (Android 13+) |
| RASP | Détection root + émulateur + debugger |
| Wipe RAM | Clé maîtresse effacée après usage et au verrouillage |
| Signature APK | v2+ uniquement (anti-CVE-2017-13156 / Janus) |
| Updates | SHA-256 publié dans chaque release GitHub |

Voir [SECURITY.md](SECURITY.md) pour le modèle de menace complet et le signalement de vulnérabilités.

## Captures d'écran

<!-- Placez les captures dans `docs/screenshots/` et décommentez ci-dessous -->
<!--
<p align="center">
  <img src="docs/screenshots/01-unlock.png"  width="220" alt="Écran de déverrouillage">
  <img src="docs/screenshots/02-vault.png"   width="220" alt="Coffre">
  <img src="docs/screenshots/03-entry.png"   width="220" alt="Détail d'une entrée">
</p>
-->

*Captures à venir.*

## Installation

### Option 1 — Obtainium (recommandé, mises à jour auto)

1. Installer [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest)
2. Ajouter cette URL : `https://github.com/gitubpatrice/pass_tech`

### Option 2 — APK direct

Télécharger `app-arm64-v8a-release.apk` depuis [la dernière release](https://github.com/gitubpatrice/pass_tech/releases/latest) (ABI **arm64-v8a**, Android 7.0+).

**Vérifier l'intégrité** :
```bash
sha256sum app-arm64-v8a-release.apk
```
Le hash doit correspondre à celui publié dans les notes de release.

> **Samsung One UI 6.1+** : si l'install est bloquée, désactivez temporairement *Réglages → Sécurité et confidentialité → Auto Blocker*.

## Permissions

| Permission | Pourquoi |
|---|---|
| `INTERNET` | Vérification de mise à jour (GitHub Releases) et HIBP (k-anonymity, opt-in). Aucune autre requête réseau. |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Déverrouillage biométrique optionnel via BiometricPrompt. |
| `CAMERA` | Scanner un QR code TOTP pour ajouter un 2FA. Flux caméra traité localement, jamais enregistré. |

Pas de localisation, pas d'accès aux contacts, pas d'accès aux médias, pas de stockage externe (hors export volontaire).

## Compiler depuis les sources

Pré-requis : Flutter 3.x, SDK Dart `^3.11.5`, JDK 17, Android SDK avec `minSdk = 24`.

```bash
flutter pub get
flutter build apk --release --split-per-abi
```

Build Android exigeant un keystore release configuré dans `android/key.properties` (non versionné) :

```properties
storePassword=...
keyPassword=...
keyAlias=...
storeFile=../keystore.jks
```

## Documentation

- [LICENSE](LICENSE) — Apache License 2.0
- [PRIVACY.md](PRIVACY.md) / [PRIVACY.fr.md](PRIVACY.fr.md) — politique de confidentialité
- [TERMS.md](TERMS.md) / [TERMS.fr.md](TERMS.fr.md) — conditions d'utilisation
- [SECURITY.md](SECURITY.md) — modèle de menace et politique de signalement
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dépendances tierces
- [NOTICE](NOTICE) — mentions Apache 2.0

## Liens

- [files-tech.com/pass-tech.php](https://www.files-tech.com/pass-tech.php) — page produit
- [Releases](https://github.com/gitubpatrice/pass_tech/releases) — APK signés
- [contact@files-tech.com](mailto:contact@files-tech.com) — support et signalement de vulnérabilités

## Licence

Copyright 2026 Files Tech / Patrice Haltaya

Distribué sous Apache License, Version 2.0. Voir [LICENSE](LICENSE) pour le texte complet.

Pass Tech est fourni « tel quel », sans garantie d'aucune sorte. Les données stockées sont chiffrées avec votre mot de passe maître et liées à la KEK matérielle de votre appareil — **si vous perdez le mot de passe maître ou si l'appareil est réinitialisé/wipe Keystore, le coffre est irrécupérable**. Pensez à exporter régulièrement votre coffre chiffré (`.ptbak`).
