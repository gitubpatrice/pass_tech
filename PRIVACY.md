# Privacy Policy — Pass Tech

**Document version** : 15 July 2026 (Pass Tech v2.5.1) · 🇫🇷 [Version française](PRIVACY.fr.md)
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
| Passwords, TOTP secrets, payment cards, secure notes | Vault entries created by the user                | Encrypted at rest on the device (`pt_vault_a.enc`) |
| Master password                       | Derives the vault encryption key (Argon2id m=19 MiB, t=2) | Never persisted; wiped from RAM on lock      |
| Biometric-bound key                   | Optional unlock via fingerprint/face                      | Android Keystore (hardware-bound), `setUserAuthenticationRequired(true)` |
| Hardware-bound KEK                    | AES-256-GCM key wrapping the vault secret                 | Android Keystore alias `pt_vault_kek_v1` (StrongBox/TEE) — never extractable |
| Second vault slot (`pt_vault_b.enc`)  | Plausible deniability — **always present**, whether or not you configured a decoy | Encrypted on device with its own KEK alias (`pt_vault_kek_decoy_v1`) |
| Encrypted backups (`.ptbak`)          | Optional user-triggered export                            | User-chosen storage location                     |
| Local preferences                     | Theme, auto-lock duration, clipboard timeout              | Local storage on the device                      |

## 5. Encryption & key derivation (vault v4)

- **AES-256-GCM** (NIST SP 800-38D) with 96-bit random nonce and 128-bit authentication tag. The GCM AAD binds `version | KEK alias | KDF parameters` to prevent silent downgrade.
- **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, L = 32 bytes (OWASP 2024 recommendation for mobile password managers). Replaces PBKDF2 used in legacy v3 vaults.
- **Hardware-bound KEK** — a 32-byte `hwSecret` is wrapped by an AES-256-GCM KEK held in the Android Keystore (alias `pt_vault_kek_v1`, StrongBox-backed when available, TEE software fallback). The KEK never leaves the secure element.
- **Final key derivation** — `finalKey = HKDF-SHA256(salt, pwHash || hwSecret, "pt:v4", 32)`. An attacker who exfiltrates the vault file alone cannot brute-force it without the device-bound KEK.
- **Plausible deniability** — the app is built so that nobody inspecting the device can tell whether you keep a second, hidden vault.
  - *At rest (since v2.5.1)* — the two vault slots carry **neutral, indistinguishable file names** (`pt_vault_a.enc` / `pt_vault_b.enc`), and **both always exist**: if you never configured a decoy, the app still writes a dummy one (an empty entry list, encrypted under a random password that is never stored anywhere, and therefore never openable — not by you, not by us). Someone who copies your device sees two vault files either way.
  - *In the Keystore* — two KEK aliases (`pt_vault_kek_v1` + `pt_vault_kek_decoy_v1`) are created at first install regardless of decoy use, and a 32-byte dummy salt is generated.
  - *In timing* — verification of the decoy path is aligned with the real one, so measuring how long an unlock attempt takes reveals nothing.
  - The code makes **no functional distinction** between the two slots: both have the same capabilities. The active slot is simply the one your master password decrypted.
- **`.ptbak` backups** — encrypted with a user-chosen passphrase using the same Argon2id + AES-GCM pack (no Keystore binding so the backup is portable across devices).
- **Biometric unlock** — optional; biometric-bound key in Android Keystore, not extractable without biometric authentication.

## 6. Network

- **Pass Tech itself** uses the network for **two strictly local-impact functions** :
  1. **Update check** : queries `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, no auth, no cookie). Suspended while panic mode is active.
  2. **HIBP breach check** (Have I Been Pwned, opt-in) : sends only the **first 5 characters of the SHA-1** of a password (k-anonymity model). The actual password never leaves the device.
- Network Security Config rejects cleartext HTTP and user-installed CAs in release builds.
- **We operate no server.** No data is sent to the developer, no in-house analytics, no crash reporting.

### No Google libraries

The app contains **no Google components**: no Play Services, no ML Kit, no
Firebase, no telemetry transport.

This was not the case until 2026-08-03. QR code scanning then relied on
`mobile_scanner`, built on **Google ML Kit**, which pulled in
`play-services-base`, `play-services-basement` and the
`com.google.android.datatransport` transport component. That dependency also
added an `ACCESS_NETWORK_STATE` permission **declared nowhere in the repository**.

Camera scanning was therefore removed, and with it the `CAMERA` and
`ACCESS_NETWORK_STATE` permissions. To add a two-factor secret, **paste the
`otpauth://…` URI** into the corresponding field: the app extracts the secret
automatically. Most services display that URI in plain text below the QR code,
behind a "can't scan?" link. The secret can also be typed by hand.

## 7. Sharing and data transmission

The application does not transmit user data to any server operated by the developer. Sharing the vault outside the device requires:

- explicit user-triggered `.ptbak` export (encrypted with a user-chosen passphrase);
- voluntary use of an Android share / email function chosen by the user.

## 8. Retention and deletion

- Vault data is stored locally and remains under user control.
- Uninstalling the app erases all data (the vault file is in the app's private directory, excluded from cloud backup via `dataExtractionRules`).
- The user can also delete the vault from within the app (`Settings → Delete vault`).
- **No leftover copy of an older vault.** Upgrading a legacy v3 vault to v4 used to leave a `.bak`
  copy behind until the next master-password change. That copy was encrypted with the older, weaker
  scheme (PBKDF2 + AES-CBC) derived from the master password **alone**, with no hardware binding —
  so it could be attacked offline, undoing the two protections v4 exists to provide. Since v2.5.1 it
  is deleted as soon as the upgrade succeeds.

## 9. Security

- App sandbox isolation, `FLAG_SECURE` (blocks screenshots and recents preview).
- `allowBackup=false` and `dataExtractionRules` exclude the vault from any Android cloud or device-transfer backup.
- Progressive lockout after 5 failed attempts (30s → 30min).
- Auto-lock after configurable inactivity (default 5 min).
- Master password key wiped from RAM on lock.
- RASP detection (root, emulator, debugger) with explicit user disclaimer.
- Sensitive clipboard flag (Android 13+) and immediate clipboard wipe on app pause.
- **Decoy vault** — a secondary master password opens a plausible fake vault (timing-aligned with the real path).
- **Panic mode** — instant lock, clipboard wipe, optional icon camouflage as "Calculator".
- **Inheritance / dead-man switch** — optional local-only flow that lets a trusted relative access the vault after a long inactivity window. No cloud, no third party.
- **Anti-phishing by domain** — the app verifies the foreground browser's domain before allowing copy of credentials, alerting on typosquatting.
- APK v2+ signature only (`enableV1Signing = false`) — neutralizes CVE-2017-13156 (Janus).

See [SECURITY.md](./SECURITY.md) for the vulnerability disclosure policy.

## 10. Android permissions

| Permission / access                  | Reason                                                                                           |
| ------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Optional biometric unlock via Android BiometricPrompt.                                            |
| `INTERNET`                           | Update check (GitHub Releases) and HIBP breach check (k-anonymity, opt-in).                       |

`CAMERA` and `ACCESS_NETWORK_STATE` were **removed on 2026-08-03** along with QR
code scanning — see §6.

## 11. Children

The application is not specifically targeted at children and contains no behavioural advertising or profiling.

## 12. Changes

This policy may be updated as the application evolves.

## 13. Contact

📧 **contact@files-tech.com**
