<p align="center">
  <img src="assets/icon.png" alt="Pass Tech" width="160" height="160">
</p>

<h1 align="center">Pass Tech</h1>

<p align="center">
  <strong>A password manager that stays on your phone.</strong><br>
  No cloud. No account. No tracker.
</p>

<p align="center">
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/actions/workflows/promises.yml"><img src="https://github.com/gitubpatrice/pass_tech/actions/workflows/promises.yml/badge.svg" alt="Promises"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/gitubpatrice/pass_tech/releases/latest"><img src="https://img.shields.io/github/v/release/gitubpatrice/pass_tech?color=brightgreen&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/built%20with-Kotlin%20%2B%20Compose-7F52FF.svg" alt="Built with Kotlin and Jetpack Compose">
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84.svg" alt="Android 8+">
</p>

<p align="center">
  <strong>English</strong> · <a href="README.fr.md">Français</a>
</p>

> Your secrets never leave your phone.

---

> **3.0.0 is a rewrite, and a different app.** Versions 2.x were written in Flutter and installed as
> `com.passtech.pass_tech`; this one is Kotlin and installs as `com.filestech.pass_tech`. They live
> side by side. To move across, export a `.ptbak` backup from 2.x and import it here — then remove
> the old one once you are satisfied.

## Why Pass Tech

Most password managers sync through their own cloud, which means trusting the provider completely.
Pass Tech takes the opposite stance: **no server**, no account, no possible backend breach, because
there is no backend.

- **Local** — the vault is a file in the app's private directory and goes nowhere else
- **Open source** — Apache License 2.0, and every release publishes the SHA-256 of each file
- **Bound to the phone** — a key in the secure chip takes part in every unlock attempt
- **No Google libraries** — no Play Services, no ML Kit, no Firebase, no telemetry
- **For when you are made to open it** — decoy vault, panic mode, heir access
- **Two network calls, both named** — the GitHub update check, and the opt-in breach check

## Features

- Passwords, bank cards and secure notes, with a generator: 8 to 64 characters, or a passphrase
  drawn from a word list in the language the app is showing
- TOTP two-factor codes (RFC 6238) — paste the `otpauth://` URI a site shows under its QR code
- Search by title, username, address or content; sort four ways
- Security audit: weak, reused, no second factor; breach check against Have I Been Pwned
- Address check before a password is copied, across nine browsers, with several addresses per entry
- Encrypted `.ptbak` backup, plain export, and import from Chrome, Edge, Bitwarden JSON or CSV
- The privacy policy and the terms are read inside the app, with no network
- Five languages — English, French, German, Italian, Spanish — picked in the app, whatever the phone
  is set to

### If someone makes you open it

- **Decoy vault** — a second master password opens a second vault with its own entries. Three slots
  exist from the first launch, all kept the same size, so nothing in the files says how many you use.
- **Panic mode** — locks, clears the clipboard, disarms the fingerprint, withdraws the address check
  and replaces the name and icon on the home screen with a calculator that really works. Nothing is
  deleted.
- **Heir access** — someone you choose opens a read-only snapshot of the vault after a long enough
  silence on your side. On the phone, with no cloud and no third party.

## Security

| Component | Choice |
|---|---|
| Key derivation | **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, 32-byte output (OWASP 2024 mobile) |
| Hardware step | **HMAC-SHA256 computed inside the Android Keystore**, over `domain \|\| Argon2id output`, with a key that never leaves it |
| Final key | `HKDF-SHA256(salt, argon \|\| hmac, info, 32)` — `domain` and `info` separate a vault file from an heir snapshot |
| Encryption | **AES-256-GCM** (NIST SP 800-38D), 96-bit nonce, 128-bit tag |
| Plausible deniability | Three slots created at first launch, all the same size; unused ones hold random data under a key that exists nowhere |
| Biometrics | Android Keystore + BiometricPrompt CryptoObject, invalidated when the phone's fingerprints change |
| Anti-brute-force | Five free attempts, then 30 s → 30 min, counted in the encrypted state file and surviving a restart |
| Screenshots | `FLAG_SECURE` on every screen while the setting is on, including the recent-apps thumbnail |
| Clipboard | Cleared after a chosen delay, even in the background, and flagged sensitive on Android 13+ |
| Device checks | Root, emulator and debugger detection, shown on the unlock screen. It warns; it never blocks |
| Backup | Android cloud backup and phone-to-phone transfer both excluded, for every domain |
| APK signature | v2, v3 and v4; **no v1**, the scheme Janus (CVE-2017-13156) attacks |
| Updates | SHA-256 published for every file of every release |

The permission set is pinned in [`config/expected-permissions.txt`](config/expected-permissions.txt)
and checked **on the built APK's merged manifest** by the `Promises` workflow, both ways: a
permission that appears unlisted fails the build, and so does one listed that disappears.

See [THREAT_MODEL.md](THREAT_MODEL.md) for what is protected, against whom, **and what is not**. See
[SECURITY.md](SECURITY.md) to report a vulnerability.

## Screenshots

[fastlane/metadata/android/en-US/images/phoneScreenshots](fastlane/metadata/android/en-US/images/phoneScreenshots)

## Install

### Option 1 — Obtainium (recommended, automatic updates)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest)
2. Add this URL: `https://github.com/gitubpatrice/pass_tech`

### Option 2 — Direct APK

Download the APK from [the latest release](https://github.com/gitubpatrice/pass_tech/releases/latest)
(Android 8.0 and above).

**Verify integrity**:
```bash
sha256sum <the file you downloaded>
```
The hash must match the one published for that same file on the release page. If the two differ, do
not install it.

> **Samsung One UI 6.1+**: if the install is blocked, temporarily disable
> *Settings → Security and privacy → Auto Blocker*.

## Permissions

Measured on the built APK, not read from the source manifest:

| Permission | Why |
|---|---|
| `INTERNET` | The update check (GitHub Releases) and the opt-in breach check (HIBP, k-anonymity). No other request. |
| `USE_BIOMETRIC` | Optional fingerprint unlock, through `androidx.biometric`. |
| `USE_FINGERPRINT` | The same, on Android 8 (API < 28), where `androidx.biometric` still needs it. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by an AndroidX library; it lets the app send a broadcast to itself and nothing else. |

No camera, no contacts, no location, no storage: the app asks the system for a file when you export
or import one, and the system hands it that file.

The accessibility service used by the address check is **not** in this list. Android grants it
separately, it is disabled in the manifest until the setting is switched on, and switching the
setting off disables the component again — which is what withdraws the grant.

## Build from source

Requirements: JDK 17, Android SDK with compileSdk 37. Everything else comes from Gradle.

```bash
./gradlew assembleRelease
```

The release build needs a signing keystore, declared in `keystore.properties` at the root (not
versioned):

```properties
storeFile=../keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release build still compiles, unsigned. The local gate the project is held to:

```bash
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug detekt
```

## Documentation

- [LICENSE](LICENSE) — Apache License 2.0
- [THREAT_MODEL.md](THREAT_MODEL.md) — what is protected, against whom, and what is not
- [PRIVACY.md](PRIVACY.md) — privacy policy, in the five languages the app ships
- [TERMS.md](TERMS.md) — terms of use, likewise
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

Pass Tech is provided "as is", without warranty of any kind. The vault is encrypted with your master
password **and** with a key held in this phone's secure chip: if you forget the master password, or
if the phone is reset, the vault is unrecoverable — by you, by us, by anyone. Export an encrypted
`.ptbak` backup regularly and keep it somewhere other than the phone.
