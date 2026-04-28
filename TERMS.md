# Terms of Use — Pass Tech

**Document version** : 28 April 2026
**App** : Pass Tech
**Website** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Source code** : https://github.com/gitubpatrice/pass_tech
**Code license** : Apache License 2.0

---

## 1. Subject

These Terms of Use define the rules applicable to the use of the **Pass Tech** application — a 100 % local password manager.

## 2. Acceptance

Using the application implies acceptance of these terms. If the user does not accept these terms, they must stop using the application.

## 3. General operation

Pass Tech is a 100 % local password vault. It stores passwords, TOTP 2FA secrets, payment cards and secure notes in an encrypted file on the device, protected by a master password and optionally an Android Keystore biometric key.

## 4. No advertising, no tracking, no telemetry

The developer declares that the application contains no advertising, tracker, audience measurement, behavioural analytics, profiling system, telemetry or remote crash reporting. The vault is never transmitted to any server operated by the developer.

## 5. Source code license

Source code published under **Apache License 2.0**.

- Repository: https://github.com/gitubpatrice/pass_tech
- Website: https://www.files-tech.com
- Contact: contact@files-tech.com

## 6. User responsibilities

- **Choose a strong master password** (12+ characters recommended) and keep it safe. **It cannot be recovered** by the developer.
- Make own encrypted backups (`.ptbak` export with a strong passphrase).
- Protect the device with a suitable lock screen, security updates and useful backups.
- Respect copyright, confidentiality, professional secrecy, personal data and applicable laws when storing data in the vault.

## 7. App-specific points

- **The master password cannot be recovered.** If forgotten, the vault is permanently inaccessible.
- The biometric unlock is a convenience layer ; the master password remains the root of trust.
- Pass Tech is best-effort against device compromise: on rooted, debuggable or emulated devices, the user is warned and uses the app at their own risk.
- The HIBP breach check is opt-in and uses a k-anonymity protocol (only the first 5 characters of the SHA-1 of the password are sent).

## 8. Warranty disclaimer

The application is provided "as is". The developer makes best efforts to provide a secure tool but does not guarantee absolute security. The user remains responsible for the choice of master password and for the protection of their device.

## 9. Limitation of liability

To the extent permitted by law, the developer cannot be held liable for data loss (including a forgotten master password leading to an unrecoverable vault), handling errors, third-party service issues or consequences of non-compliant use.

## 10. Third-party services

- **GitHub Releases API** for update checks (HTTPS, no authentication, no cookie).
- **Have I Been Pwned API** for breach checks (HTTPS, k-anonymity, opt-in).

## 11. Intellectual property

The name, content, texts, icons, visuals and resources of the project remain protected. The main source code is placed under Apache License 2.0.

## 12. Security

The user must protect their master password, their device and avoid using Pass Tech on rooted/compromised devices. See [SECURITY.md](./SECURITY.md).

## 13. Modification of terms

These terms may be updated as the application evolves. The date on the document indicates the current version.

## 14. Applicable law

Unless otherwise required by law, these terms are drafted in line with French and European law. When used in another country, the user remains responsible for complying with applicable local laws.
