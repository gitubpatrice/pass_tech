<p align="center">
  <img src="assets/icon.png" alt="Pass Tech" width="160" height="160">
</p>

<h1 align="center">Pass Tech</h1>

<p align="center">
  <strong>Gestionnaire de mots de passe Android 100 % local.</strong><br>
  Aucun cloud. Aucun tracker. Aucun compte.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/releases/latest"><img src="https://img.shields.io/github/v/release/gitubpatrice/pass_tech?color=green&label=release" alt="Latest release"></a>
  <a href="https://flutter.dev"><img src="https://img.shields.io/badge/built%20with-Flutter-02569B.svg" alt="Built with Flutter"></a>
</p>

> Vos secrets ne quittent jamais votre téléphone.

---

## Pourquoi Pass Tech

La majorité des gestionnaires de mots de passe synchronisent vos données via leur cloud — ce qui implique une confiance totale dans le fournisseur. Pass Tech prend le parti opposé : **aucun serveur**, aucun compte, aucune fuite possible côté backend, parce qu'il n'y a pas de backend.

- **100 % local** — base chiffrée stockée uniquement dans la mémoire interne de l'app
- **Open source** — Apache License 2.0, code auditable
- **Audit OWASP MASVS** — clôturé, score ~99/100
- **Aucune permission inutile** — pas d'Internet sauf vérif HIBP optionnelle (k-anonymity)

## Sécurité

| Composant | Choix |
|---|---|
| Dérivation clé | PBKDF2-HMAC-SHA256, **600 000 itérations** (OWASP 2023) |
| Chiffrement | AES-256-CBC |
| Intégrité | HMAC-SHA256 (encrypt-then-MAC, vérification constant-time) |
| Biométrie | Android Keystore + BiometricPrompt CryptoObject (clé hardware-bound) |
| Anti-brute-force | Verrouillage progressif 5 fails (30 s → 30 min) |
| Captures écran | `FLAG_SECURE` actif |
| Clipboard | Effacement auto + flag `IS_SENSITIVE` (Android 13+) |
| RASP | Détection root + émulateur |
| Wipe RAM | Clé maîtresse effacée après usage et au verrouillage |
| Updates | SHA-256 publié dans chaque release |

Voir [SECURITY.md](SECURITY.md) pour le signalement de vulnérabilités.

## Fonctionnalités

- Mots de passe avec générateur configurable
- TOTP 2FA (RFC 6238)
- Cartes bancaires
- Notes sécurisées
- Recherche locale
- Vérification de fuites HIBP (k-anonymity, optionnelle)
- Export / import du coffre chiffré
- Mises à jour vérifiables via GitHub Releases

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

Télécharger `app-arm64-v8a-release.apk` depuis [la dernière release](https://github.com/gitubpatrice/pass_tech/releases/latest).

**Vérifier l'intégrité** :
```bash
sha256sum app-arm64-v8a-release.apk
```
Le hash doit correspondre à celui publié dans les notes de release.

> ⚠️ **Samsung One UI 6.1+** : si l'install est bloquée, désactivez temporairement *Réglages → Sécurité et confidentialité → Auto Blocker*.

## Compiler depuis les sources

```bash
flutter pub get
flutter build apk --release --split-per-abi
```

Build Android exigeant `flutter ^3.x` et un keystore release configuré dans `android/key.properties` (non versionné).

## Documentation

- [LICENSE](LICENSE) — Apache License 2.0
- [PRIVACY.md](PRIVACY.md) / [PRIVACY.fr.md](PRIVACY.fr.md) — politique de confidentialité
- [TERMS.md](TERMS.md) / [TERMS.fr.md](TERMS.fr.md) — conditions d'utilisation
- [SECURITY.md](SECURITY.md) — politique de sécurité et signalement
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dépendances tierces
- [NOTICE](NOTICE) — mentions Apache 2.0

## Liens

- [files-tech.com/pass-tech.php](https://www.files-tech.com/pass-tech.php) — page produit
- [Releases](https://github.com/gitubpatrice/pass_tech/releases) — APK signés
- [contact@files-tech.com](mailto:contact@files-tech.com) — support

## License

Copyright 2026 Files Tech / Patrice

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.

Pass Tech est fourni « tel quel », sans garantie d'aucune sorte. Les mots de passe stockés sont chiffrés avec votre mot de passe maître — **si vous le perdez, les données sont irrécupérables**. Pensez à exporter régulièrement votre coffre chiffré.
