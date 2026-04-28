# Third-Party Notices — Pass Tech

This product includes the following open-source dependencies. Each component remains subject to its own license.

The main source code of the project is placed under [Apache License 2.0](./LICENSE).

## Direct Flutter / Dart dependencies

Versions are those declared in `pubspec.yaml` at the time of writing.

| # | Package                      | Version  | License (typical)        | Repository                                                          |
| - | ---------------------------- | -------- | ------------------------ | ------------------------------------------------------------------- |
| 1 | `cupertino_icons`            | ^1.0.8   | MIT                      | https://github.com/flutter/packages                                 |
| 2 | `flutter_secure_storage`     | ^9.2.2   | BSD-3-Clause             | https://github.com/mogol/flutter_secure_storage                     |
| 3 | `crypto`                     | ^3.0.3   | BSD-3-Clause             | https://github.com/dart-lang/crypto                                 |
| 4 | `encrypt`                    | ^5.0.3   | BSD-3-Clause             | https://github.com/leocavalcante/encrypt                            |
| 5 | `uuid`                       | ^4.5.1   | MIT                      | https://github.com/Daegalus/dart-uuid                               |
| 6 | `share_plus`                 | ^10.0.3  | BSD-3-Clause             | https://github.com/fluttercommunity/plus_plugins                    |
| 7 | `shared_preferences`         | ^2.3.2   | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 8 | `intl`                       | ^0.19.0  | BSD-3-Clause             | https://github.com/dart-lang/i18n                                   |
| 9 | `http`                       | ^1.2.0   | BSD-3-Clause             | https://github.com/dart-lang/http                                   |
| 10 | `path_provider`             | ^2.1.4   | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 11 | `mobile_scanner`            | ^5.2.3   | BSD-3-Clause             | https://github.com/juliansteenbakker/mobile_scanner                 |
| 12 | `file_picker`               | ^8.1.2   | MIT                      | https://github.com/miguelpruivo/flutter_file_picker                 |
| 13 | `flutter_slidable`          | ^3.1.1   | MIT                      | https://github.com/letsar/flutter_slidable                          |
| 14 | `biometric_storage`         | ^5.0.1   | MIT                      | https://github.com/authpass/biometric_storage                       |

## Dev dependencies

| # | Package                  | Version  | License        |
| - | ------------------------ | -------- | -------------- |
| 1 | `flutter_lints`          | ^6.0.0   | BSD-3-Clause   |
| 2 | `image`                  | ^4.1.3   | MIT            |

## Cryptographic primitives

Pass Tech uses standard, well-reviewed cryptographic primitives via the `crypto` and `encrypt` packages:

- **AES-256-CBC** for vault encryption
- **HMAC-SHA256** for authenticated encryption (encrypt-then-MAC pattern)
- **PBKDF2-HMAC-SHA256** with 600 000 iterations for key derivation
- **SHA-1 (5-char prefix)** only for the k-anonymity HIBP query

No custom cryptography is implemented in this application.

## External services queried by the user

- **GitHub Releases API** — `https://api.github.com/repos/gitubpatrice/pass_tech/releases/latest`. Public, anonymous, HTTPS.
- **Have I Been Pwned API** — `https://api.pwnedpasswords.com/range/<5-char-SHA1-prefix>`. K-anonymity, HTTPS, opt-in.

## Notices

A copy of the Apache License 2.0 is provided in the [`LICENSE`](./LICENSE) file. The [`NOTICE`](./NOTICE) file contains attribution notices for this project.
