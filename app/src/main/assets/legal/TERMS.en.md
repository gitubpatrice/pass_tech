# Terms of Use — Pass Tech

- **Applies to**: Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Last changed**: 23 September 2026
- **Publisher**: Files Tech — Patrice Haltaya
- **Contact**: contact@files-tech.com
- **Source code**: https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> This document covers the **Kotlin** version of Pass Tech, 3.0.0. It is not the one for the earlier
> Flutter versions (2.x, `com.passtech.pass_tech`).

---

## 1. What this is

Pass Tech keeps passwords, two-factor secrets, bank cards and notes on your phone, encrypted by a
master password you choose. There is no account and no server of ours. Using the app means accepting
what follows.

## 2. The one thing to understand before you start

**Your master password cannot be recovered.** Not by us, not by anyone, by any means. It is never
stored — the app keeps no copy, no hint and no reset. If you forget it, the vault is gone, and so is
everything in it.

That is not a limitation we could lift. It is the reason the vault cannot be opened by us, by a
court order served on us, or by whoever takes your phone.

So, before you put anything important in it:

- choose a master password of at least 12 characters, and one you will still know in a year;
- make a `.ptbak` backup, with a passphrase of its own, and keep it somewhere other than the phone;
- remember that the backup is only as safe as the place you put it.

## 3. What we promise

- **No advertising, no tracker, no analytics, no telemetry, no crash report.** Nothing about you or
  your use of the app reaches us, ever.
- **Your vault is never sent anywhere.** The two network calls the app makes are listed in the
  privacy policy, and neither carries anything you typed.
- **The source code is public**, under the Apache License 2.0, and every release publishes the
  SHA-256 of each file so you can check that what you installed is what was published.

## 4. What we do not promise

The app is provided as it is. We work at it carefully — the code is public, so you can judge for
yourself — but no one can promise that software is free of defects, and we do not.

In particular:

- **A phone that is compromised compromises the app.** On a rooted phone, one with a debugger
  attached, or an emulator, anything with that access can read the memory of any app, this one
  included. Pass Tech says so on its unlock screen when it notices; those checks are easy to hide
  from and are a reminder, never a guarantee.
- **The decoy vault and the panic mode raise the cost of a search; they do not make one impossible.**
  They are designed so that nothing on the phone says whether a second vault exists. Someone who
  knows this app exists knows that too.
- **The heir feature is a countdown on your phone**, not a service. If the phone is lost, wiped or
  broken, the snapshot goes with it.
- **The domain check reads only what the browser shows.** A browser it does not know, or a page
  inside another app, cannot be read — and the app then says the check could not be made rather than
  saying all is well.

To the extent the law allows, we cannot be held responsible for data lost through a forgotten master
password, a backup kept badly, a mishandled deletion, a third-party service failing, or use of the
app outside what it is meant for.

## 5. What is yours to do

- Keep your master password, and keep it to yourself.
- Make your own backups, and check from time to time that you can still open one.
- Keep the phone itself protected: a lock screen, its updates.
- Obey the law where you are — copyright, other people's privacy, professional confidentiality, and
  any rule about encryption that applies to you.

## 6. Third-party services

Two, both described in the privacy policy, both over HTTPS:

- **GitHub** — asked whether a newer version has been published.
- **Have I Been Pwned** — asked whether a password appears in a public breach, when you press the
  button, and never told the password itself.

Their own terms and privacy policies apply to them, not to us.

## 7. Licence and name

The source code is published under the **Apache License 2.0**. The name Pass Tech, the icons and the
visual identity are not covered by that licence and remain the publisher's.

## 8. Changes

These terms ship with the app and with its source code. A change ships in a version; the date at the
top says which.

## 9. Law

These terms are written under French and European law, save where a mandatory provision says
otherwise.

## 10. Contact

contact@files-tech.com
