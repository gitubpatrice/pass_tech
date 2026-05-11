# Terms of Use — Pass Tech

**Document version**: May 11, 2026 (Pass Tech v2.4.0)
**App**: Pass Tech
**Official website**: https://www.files-tech.com
**Contact**: contact@files-tech.com
**Source code**: https://github.com/gitubpatrice/pass_tech
**Code license**: Apache License 2.0

---

## 1. Purpose

These Terms of Use define the rules applicable to the use of the **Pass Tech** application — a 100% local password manager.

## 2. Acceptance

Using the application implies acceptance of these terms.

## 3. General operation

Pass Tech is a 100% local password vault. It stores passwords, TOTP 2FA secrets, bank cards and secure notes in an encrypted file on the device, protected by a master password and, optionally, an Android Keystore biometric key.

## 4. No advertising, trackers or telemetry

The developer declares that the application contains no advertising, no tracker, no analytics, no behavioral analysis, no profiling system, no telemetry and no remote crash reporting. The vault is never transmitted to any server operated by the developer.

## 5. Source code license

Source code published under **Apache License 2.0**.

- Repository: https://github.com/gitubpatrice/pass_tech
- Official website: https://www.files-tech.com
- Contact: contact@files-tech.com

## 6. User responsibilities

- **Choose a strong master password** (12+ characters recommended) and keep it safe. **It cannot be recovered** by the developer.
- Make your own encrypted backups (`.ptbak` export with a strong passphrase).
- Protect your device with a suitable lock, security updates and useful backups.
- Comply with copyright, privacy, professional secrecy obligations and applicable law.

## 7. App-specific points

- **The master password cannot be recovered.** If forgotten, the vault is permanently inaccessible.
- Biometric unlock is a convenience layer; the master password remains the root of trust.
- Pass Tech is best-effort against device compromise: on a rooted, debuggable or emulated device, the user is warned and uses the app at their own risk.
- HIBP check is opt-in and uses a k-anonymity protocol (only the first 5 characters of the password's SHA-1 are sent).

## 8. Warranty disclaimer

The application is provided as-is. The developer makes best efforts to deliver a secure tool but does not guarantee absolute security. The user remains responsible for the master password choice and device protection.

## 9. Limitation of liability

To the extent permitted by law, the developer cannot be held liable for data loss (in particular a forgotten master password making the vault unrecoverable), handling errors, third-party service issues or consequences of non-conforming use.

## 10. Third-party services

- **GitHub Releases API** for update checks (HTTPS, no authentication, no cookie).
- **Have I Been Pwned API** for breach checks (HTTPS, k-anonymity, opt-in).

## 11. Intellectual property

The name, content, texts, icons, visuals and resources specific to the project remain protected. The main source code is released under Apache License 2.0.

## 12. Security

The user must protect their master password, their device and avoid using Pass Tech on rooted/compromised devices. See [SECURITY.md](./SECURITY.md).

## 13. Modification of terms

These terms may be updated. The document date indicates the version in force.

## 14. Governing law

Save for mandatory legal provisions to the contrary, these terms are drafted within the framework of French and European law.
