# Privacy Policy — Pass Tech

**Document version** : 28 April 2026
**App** : Pass Tech
**Website** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Source code** : https://github.com/gitubpatrice/pass_tech
**Code license** : Apache License 2.0

---

## 1. Purpose

This Privacy Policy explains how the **Pass Tech** application — a 100% local password manager — handles user data and permissions.

## 2. User-friendly summary

- ✅ **No advertising** in the application.
- ✅ **No tracker**, audience measurement, behavioural analytics or profiling.
- ✅ **No account** specific to the application.
- ✅ **No cloud sync** — your vault stays on your device, encrypted.
- ✅ **No telemetry** — no usage data, no error reports sent to the developer.

**Core principle** : Pass Tech is a 100% local password vault. All sensitive data (passwords, TOTP secrets, payment cards, secure notes) stays encrypted on the user's device. No remote server is operated by the developer.

## 3. Data controller / developer

- **Developer** : Files Tech / Patrice
- **Website** : https://www.files-tech.com
- **Privacy contact** : contact@files-tech.com
- **Source repository** : https://github.com/gitubpatrice/pass_tech
- **Source code license** : Apache License 2.0

## 4. Data accessed or stored

| Data type                            | Use                                                      | Processing location                              |
| ------------------------------------ | -------------------------------------------------------- | ------------------------------------------------ |
| Passwords, TOTP secrets, payment cards, secure notes | Vault entries created by the user                | Encrypted at rest on the device (`pt_vault.enc`) |
| Master password                       | Derives the vault encryption key (PBKDF2-SHA256 600 000 iterations) | Never persisted; wiped from RAM on lock      |
| Biometric-bound key                   | Optional unlock via fingerprint/face                      | Android Keystore (hardware-bound), `setUserAuthenticationRequired(true)` |
| Encrypted backups (`.ptbak`)          | Optional user-triggered export                            | User-chosen storage location                     |
| Local preferences                     | Theme, auto-lock duration, clipboard timeout              | Local storage on the device                      |

## 5. Encryption & key derivation

- **AES-256-CBC + HMAC-SHA256** (encrypt-then-MAC), constant-time MAC verification before decrypt.
- **PBKDF2-HMAC-SHA256, 600 000 iterations** for vault and `.ptbak` (OWASP 2023 recommendation).
- **HMAC over metadata** (version, iterations, salt) prevents downgrade attacks on the vault file.
- **Biometric key hardware-bound** to Android Keystore; cannot be extracted without biometric authentication.

## 6. Network

- The app uses the network for **two strictly local-impact functions** :
  1. **Update check** : queries `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, no auth, no cookie).
  2. **HIBP breach check** (Have I Been Pwned, opt-in) : sends only the **first 5 characters of the SHA-1** of a password (k-anonymity model). The actual password never leaves the device.
- Network Security Config rejects cleartext HTTP and user-installed CAs in release builds.
- No telemetry, crash reporting or analytics.

## 7. Sharing and data transmission

The application does not transmit user data to any server operated by the developer. Sharing the vault outside the device requires:

- explicit user-triggered `.ptbak` export (encrypted with a user-chosen passphrase);
- voluntary use of an Android share / email function chosen by the user.

## 8. Retention and deletion

- Vault data is stored locally and remains under user control.
- Uninstalling the app erases all data (the vault file is in the app's private directory, excluded from cloud backup via `dataExtractionRules`).
- The user can also delete the vault from within the app (`Settings → Delete vault`).

## 9. Security

- App sandbox isolation, `FLAG_SECURE` (blocks screenshots and recents preview).
- `allowBackup=false` and `dataExtractionRules` exclude the vault from any Android cloud or device-transfer backup.
- Progressive lockout after 5 failed attempts (30s → 30min).
- Auto-lock after configurable inactivity (default 5 min).
- Master password key wiped from RAM on lock.
- RASP detection (root, emulator, debugger) with explicit user disclaimer.
- Sensitive clipboard flag (Android 13+) and immediate clipboard wipe on app pause.

See [SECURITY.md](./SECURITY.md) for the vulnerability disclosure policy.

## 10. Android permissions

| Permission / access                  | Reason                                                                                           |
| ------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Optional biometric unlock via Android BiometricPrompt.                                            |
| `INTERNET`                           | Update check (GitHub Releases) and HIBP breach check (k-anonymity, opt-in).                       |
| `CAMERA`                             | Scan a 2FA QR code to add a TOTP secret. The camera feed is processed locally and never recorded. |

## 11. Children

The application is not specifically targeted at children and contains no behavioural advertising or profiling.

## 12. Changes

This policy may be updated as the application evolves.

## 13. Contact

📧 **contact@files-tech.com**
