<p align="center">
  <img src="assets/icon.png" alt="Pass Tech" width="160" height="160">
</p>

<h1 align="center">Pass Tech</h1>

<p align="center">
  <strong>100% offline Android password manager.</strong><br>
  No cloud. No tracker. No account.
</p>

<p align="center">
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/releases/latest"><img src="https://img.shields.io/github/v/release/gitubpatrice/pass_tech?color=brightgreen&label=release" alt="Latest release"></a>
  <a href="https://flutter.dev"><img src="https://img.shields.io/badge/built%20with-Flutter-02569B.svg" alt="Built with Flutter"></a>
  <img src="https://img.shields.io/badge/platform-Android%207%2B-3DDC84.svg" alt="Android 7+">
</p>

<p align="center">
  <strong>English</strong> · <a href="README.fr.md">Français</a>
</p>

> Your secrets never leave your phone.

---

## Why Pass Tech

Most password managers sync your data through their cloud — which means trusting the provider completely. Pass Tech takes the opposite stance: **no server**, no account, no possible backend breach, because there is no backend.

- **100% local** — encrypted vault stored only in the app's internal storage
- **Open source** — Apache License 2.0, auditable code
- **Hardened v4 crypto** — Argon2id + AES-GCM-256 + hardware-bound KEK (StrongBox/TEE)
- **No Google libraries** — no Play Services, no ML Kit, no Firebase, no telemetry
- **Radical privacy pack** — decoy vault, panic mode, inheritance, anti-phishing
- **No pointless permissions** — `INTERNET` only for the GitHub update check and the opt-in HIBP lookup

## Features

- Passwords with a configurable generator (8–64 characters, or French Diceware passphrases)
- TOTP 2FA (RFC 6238) — paste the `otpauth://` URI, the secret is extracted automatically
- Bank cards (number, CVV, expiry, PIN — 3D display)
- Secure notes
- Local search by title, username, URL or content
- Security audit (weak, duplicate, old, missing 2FA)
- HIBP breach check (k-anonymity, opt-in)
- Encrypted vault export / import (`.ptbak`)
- Verifiable updates via GitHub Releases (published SHA-256)

### Radical privacy pack

- **Decoy vault** — a second master password opens a credible fake vault (plausible deniability, timing-aligned).
- **Panic mode** — locks everything, wipes the clipboard and disguises the icon as a working calculator.
- **Inheritance after inactivity** — a relative can reach the vault after a prolonged period of inactivity, without any cloud.
- **Domain anti-phishing** — checks the browser's domain before copying; warns on typosquatting.
- **Hardware-bound biometrics** (optional) — key tied to the Android Keystore, biometric authentication required to read it.

## Security

| Component | Choice (vault v4) |
|---|---|
| Key derivation | **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, L = 32 (OWASP 2024) |
| Encryption | **AES-256-GCM** (NIST SP 800-38D), 96-bit nonce, 128-bit tag |
| Anti-downgrade | GCM AAD binds `version \| KEK alias \| KDF parameters` |
| Hardware-bound key | **AES/GCM/NoPadding 256 KEK** in the Android Keystore (StrongBox when available, TEE fallback) |
| Final derivation | `HKDF-SHA256(salt, pwHash \|\| hwSecret, "pt:v4", 32)` |
| Plausible deniability | Two KEK aliases always created at install; both vault files kept the same size |
| Biometrics | Android Keystore + BiometricPrompt CryptoObject (`setUserAuthenticationRequired(true)`) |
| Anti-brute-force | Progressive lockout after 5 failures (30 s → 30 min), anchored on `elapsedRealtime` |
| Screenshots | `FLAG_SECURE`, re-armed natively before the system takes its recents thumbnail |
| Clipboard | Auto-wipe + `IS_SENSITIVE` flag (Android 13+) |
| RASP | Root, emulator and debugger detection |
| RAM wipe | Master key wiped after use and on lock |
| APK signature | v2+ only (mitigates CVE-2017-13156 / Janus) |
| Updates | SHA-256 published in every GitHub release |

The permission set is pinned in [`android/expected-permissions.txt`](android/expected-permissions.txt) and verified **against the built APK on every commit**, together with an Exodus Privacy tracker check.

See [THREAT_MODEL.md](THREAT_MODEL.md) for what is protected, against whom, **and what is not** — including the acknowledged limits. See [SECURITY.md](SECURITY.md) to report a vulnerability.

## Screenshots

*Coming soon.*

## Install

### Option 1 — Obtainium (recommended, automatic updates)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest)
2. Add this URL: `https://github.com/gitubpatrice/pass_tech`

### Option 2 — Direct APK

Download `app-arm64-v8a-release.apk` from [the latest release](https://github.com/gitubpatrice/pass_tech/releases/latest) (**arm64-v8a** ABI, Android 7.0+).

**Verify integrity**:
```bash
sha256sum app-arm64-v8a-release.apk
```
The hash must match the one published in the release notes.

> **Samsung One UI 6.1+**: if the install is blocked, temporarily disable *Settings → Security and privacy → Auto Blocker*.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Update check (GitHub Releases) and HIBP breach check (k-anonymity, opt-in). No other network request. |
| `USE_BIOMETRIC` | Optional biometric unlock via BiometricPrompt. |
| `USE_FINGERPRINT` | Not declared by Pass Tech: re-added by the `biometric_storage` plugin. Required by `androidx.biometric` on API 24-27. |

No location, no contacts, no media access, no external storage (beyond a deliberate export), **no camera**.

`CAMERA` and `ACCESS_NETWORK_STATE` were removed on 2026-08-03 along with QR code scanning, which relied on Google ML Kit. A 2FA secret is now added by pasting the `otpauth://` URI that services display below their QR code.

## Build from source

Requirements: Flutter 3.x, Dart SDK `^3.11.5`, JDK 17, Android SDK with `minSdk = 24`.

```bash
flutter pub get
flutter build apk --release --split-per-abi
```

The Android release build requires a signing keystore configured in `android/key.properties` (not versioned):

```properties
storePassword=...
keyPassword=...
keyAlias=...
storeFile=../keystore.jks
```

## Documentation

- [LICENSE](LICENSE) — Apache License 2.0
- [THREAT_MODEL.md](THREAT_MODEL.md) — what is protected, against whom, and what is not
- [PRIVACY.md](PRIVACY.md) / [PRIVACY.fr.md](PRIVACY.fr.md) — privacy policy
- [TERMS.md](TERMS.md) / [TERMS.fr.md](TERMS.fr.md) — terms of use
- [SECURITY.md](SECURITY.md) — vulnerability reporting policy
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — third-party dependencies
- [NOTICE](NOTICE) — Apache 2.0 attribution notices

## Links

- [files-tech.com/pass-tech.php](https://www.files-tech.com/pass-tech.php) — product page
- [Releases](https://github.com/gitubpatrice/pass_tech/releases) — signed APKs
- [contact@files-tech.com](mailto:contact@files-tech.com) — support and vulnerability reports

## License

Copyright 2026 Files Tech / Patrice Haltaya

Distributed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.

Pass Tech is provided "as is", without warranty of any kind. Stored data is encrypted with your master password and bound to your device's hardware KEK — **if you lose the master password, or if the device is reset or its Keystore wiped, the vault is unrecoverable**. Export an encrypted backup (`.ptbak`) regularly.
