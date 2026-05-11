# Privacy Policy — Pass Tech

**Document version**: May 10, 2026 (Pass Tech v2.3.11)
**App**: Pass Tech
**Official website**: https://www.files-tech.com
**Contact**: contact@files-tech.com
**Source code**: https://github.com/gitubpatrice/pass_tech
**Code license**: Apache License 2.0

---

## 1. Purpose

This Privacy Policy explains how the **Pass Tech** application — a 100% local password manager — handles user data and permissions.

## 2. Summary for the user

- ✅ **No advertising** in the application.
- ✅ **No trackers**, analytics, behavioral analysis or profiling.
- ✅ **No app-specific account**.
- ✅ **No cloud sync** — your vault stays on your device, encrypted.
- ✅ **No telemetry** — no usage data, no crash reports sent to the developer.

**General principle**: Pass Tech is a 100% local password vault. All sensitive data (passwords, TOTP secrets, bank cards, secure notes) stays encrypted on the device. No remote server is operated by the developer.

## 3. Controller / developer

- **Developer**: Files Tech / Patrice
- **Website**: https://www.files-tech.com
- **Privacy contact**: contact@files-tech.com
- **Source repository**: https://github.com/gitubpatrice/pass_tech
- **Source code license**: Apache License 2.0

## 4. Data accessed or stored

| Data type                              | Use                                                          | Processing location                              |
| -------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------ |
| Passwords, TOTP secrets, bank cards, secure notes | Vault entries created by the user            | Encrypted at rest on device (`pt_vault.enc`)     |
| Master password                        | Derives the encryption key (Argon2id, OWASP 2024 baseline)   | Never persisted; wiped from RAM on lock          |
| Biometric key                          | Optional fingerprint / face unlock                           | Android Keystore (hardware-bound), `setUserAuthenticationRequired(true)` |
| Encrypted backups (`.ptbak`)           | Optional export triggered by the user                        | User-chosen location                             |
| Local preferences                      | Theme, auto-lock duration, clipboard timeout                 | Local device storage                             |

## 5. Encryption & key derivation

- **AES-256-GCM** (AEAD) with bound AAD for downgrade resistance.
- **Argon2id** (m = 19 MiB, t = 2, p = 1, OWASP 2024 baseline) for the vault master key derivation.
- **Hardware-bound KEK** in Android Keystore (StrongBox best-effort) wraps a per-vault hardware secret.
- **Biometric key tied to hardware** via Android Keystore; non-extractible without biometric authentication.

## 6. Network

- The app uses the network for **two strictly local-impact functions**:
  1. **Update check**: queries `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, no auth, no cookie).
  2. **HIBP check** (Have I Been Pwned, opt-in): sends only the **first 5 characters of the SHA-1** of a password (k-anonymity model). The password never leaves the device.
- Network Security Config rejects cleartext HTTP and user-installed authorities in release.
- No telemetry, crash reporting or analytics.

## 7. Sharing and transmission of data

The application does not transmit any data to a server operated by the developer. Sharing outside the device requires:

- a `.ptbak` export explicitly triggered by the user (encrypted with a user-chosen passphrase);
- voluntary use of an Android share / email function.

## 8. Retention and deletion

- Vault data is stored locally and remains under user control.
- Uninstalling the app deletes all data (the vault file is in the app's private directory, excluded from cloud backup via `dataExtractionRules`).
- The user can also delete the vault from within the app (`Settings → Delete vault`).

## 9. Security

- Sandbox isolation, `FLAG_SECURE` (blocks screenshots and Recents preview).
- `allowBackup=false` and `dataExtractionRules` exclude the vault from any Android cloud or device-transfer backup.
- Progressive lockout after 5 failed attempts (30 s → 30 min).
- Configurable auto-lock after inactivity (5 min default).
- Master-password key wiped from RAM on lock.
- RASP detection (root, emulator, debugger) with explicit user disclosure.
- Sensitive clipboard flag (Android 13+) and immediate clipboard clearing on pause.

See [SECURITY.md](./SECURITY.md).

## 10. Android permissions

| Permission / access                  | Reason                                                                                              |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Optional biometric unlock via Android BiometricPrompt.                                              |
| `INTERNET`                           | Update checks (GitHub Releases) and HIBP (k-anonymity, opt-in).                                     |
| `CAMERA`                             | Scan a 2FA QR code to add a TOTP secret. Camera frames processed locally, never recorded.           |

## 11. Children

The application is not specifically intended for children and contains no behavioral advertising or profiling mechanism.

## 12. Changes

This policy may be updated as the application evolves.

## 13. Contact

📧 **contact@files-tech.com**
