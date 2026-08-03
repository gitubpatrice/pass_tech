# Third-Party Notices — Pass Tech

This product includes the following open-source dependencies. Each component remains subject to its own license.

The main source code of the project is placed under [Apache License 2.0](./LICENSE).

## Direct Flutter / Dart dependencies

Versions are those declared in `pubspec.yaml` for Pass Tech v2.0.2.

| #  | Package                  | Version    | License (typical) | Repository                                                          |
| -- | ------------------------ | ---------- | ----------------- | ------------------------------------------------------------------- |
| 1  | `files_tech_core`        | path       | Apache-2.0        | https://github.com/gitubpatrice/files_tech_core                     |
| 2  | `cupertino_icons`        | ^1.0.8     | MIT               | https://github.com/flutter/packages                                 |
| 3  | `flutter_secure_storage` | ^10.0.0    | BSD-3-Clause      | https://github.com/mogol/flutter_secure_storage                     |
| 4  | `crypto`                 | ^3.0.3     | BSD-3-Clause      | https://github.com/dart-lang/crypto                                 |
| 5  | `encrypt`                | ^5.0.3     | BSD-3-Clause      | https://github.com/leocavalcante/encrypt                            |
| 6  | `cryptography`           | ^2.7.0     | Apache-2.0        | https://github.com/dint-dev/cryptography                            |
| 7  | `cryptography_flutter`   | ^2.3.2     | Apache-2.0        | https://github.com/dint-dev/cryptography                            |
| 8  | `uuid`                   | ^4.5.1     | MIT               | https://github.com/Daegalus/dart-uuid                               |
| 9  | `share_plus`             | ^10.0.3    | BSD-3-Clause      | https://github.com/fluttercommunity/plus_plugins                    |
| 10 | `shared_preferences`     | ^2.3.2     | BSD-3-Clause      | https://github.com/flutter/packages                                 |
| 11 | `intl`                   | ^0.20.2    | BSD-3-Clause      | https://github.com/dart-lang/i18n                                   |
| 12 | `http`                   | ^1.2.0     | BSD-3-Clause      | https://github.com/dart-lang/http                                   |
| 13 | `path_provider`          | ^2.1.4     | BSD-3-Clause      | https://github.com/flutter/packages                                 |
| 15 | `file_picker`            | ^11.0.0    | MIT               | https://github.com/miguelpruivo/flutter_file_picker                 |
| 16 | `flutter_slidable`       | ^4.0.3     | MIT               | https://github.com/letsar/flutter_slidable                          |
| 17 | `biometric_storage`      | ^5.0.1     | MIT               | https://github.com/authpass/biometric_storage                       |
| 18 | `url_launcher`           | ^6.3.1     | BSD-3-Clause      | https://github.com/flutter/packages                                 |
| 19 | `flutter_markdown`       | ^0.7.4     | BSD-3-Clause      | https://github.com/flutter/packages                                 |

## Dev dependencies

| # | Package         | Version  | License      |
| - | --------------- | -------- | ------------ |
| 1 | `flutter_lints` | ^6.0.0   | BSD-3-Clause |
| 2 | `image`         | ^4.1.3   | MIT          |

## Cryptographic primitives (vault v4)

Pass Tech uses standard, well-reviewed cryptographic primitives via the `cryptography` / `cryptography_flutter` packages (with native FFI acceleration on Android) and the Android Keystore:

- **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, L = 32 — vault and `.ptbak` key derivation (OWASP 2024).
- **AES-256-GCM** (NIST SP 800-38D) — vault and `.ptbak` authenticated encryption (96-bit nonce, 128-bit tag, AAD bound to version + KEK alias + KDF parameters).
- **HKDF-SHA256** — derives the final encryption key from `pwHash || hwSecret`.
- **Android Keystore AES/GCM/NoPadding 256** — KEK alias `pt_vault_kek_v1` (and `pt_vault_kek_decoy_v1` for plausible deniability), StrongBox-backed when available, TEE software fallback. The KEK never leaves the secure element.
- **HMAC-SHA256** (`crypto` package) — used for legacy v3 vault read path during one-shot v3 → v4 migration only.
- **PBKDF2-HMAC-SHA256** (`crypto` package) — used for legacy v3 vault read path during one-shot v3 → v4 migration only. Not used for any new encryption.
- **SHA-1 (5-char prefix)** — only for the k-anonymity HIBP query.

No custom cryptography is implemented in this application.

## Transitive Android dependencies (not declared in `pubspec.yaml`)

Audited 2026-08-03 against the **merged release manifest**. Some Flutter plugins
pull in native Android artifacts that never appear in `pubspec.yaml`.

**As of 2026-08-03 the app contains no Google artifacts at all.** The previous
entry documented Google ML Kit, Play Services and `datatransport`, all pulled in
by `mobile_scanner` for QR code scanning; that dependency was removed. See
[`THREAT_MODEL.md`](./THREAT_MODEL.md) §5.2.

The only remaining transitive manifest contribution:

| Pulled in by | Contribution | Note |
| ------------ | ------------ | ---- |
| `biometric_storage` | re-declares `android.permission.USE_FINGERPRINT` (no `maxSdkVersion`) | Required by `androidx.biometric` on API 24-27 |

The permission set is pinned in `android/expected-permissions.txt` and verified
against the built APK on every commit by `.github/workflows/promesses.yml`.

To re-check after any dependency bump:

```bash
grep -E "gms|mlkit|datatransport|firebase"   build/app/intermediates/manifest_merge_blame_file/release/processReleaseMainManifest/manifest-merger-blame-release-report.txt
```

## External services queried by the user

- **GitHub Releases API** — `https://api.github.com/repos/gitubpatrice/pass_tech/releases/latest`. Public, anonymous, HTTPS, no cookie.
- **Have I Been Pwned API** — `https://api.pwnedpasswords.com/range/<5-char-SHA1-prefix>`. K-anonymity, HTTPS, opt-in.

## Notices

A copy of the Apache License 2.0 is provided in the [`LICENSE`](./LICENSE) file. The [`NOTICE`](./NOTICE) file contains attribution notices for this project.
