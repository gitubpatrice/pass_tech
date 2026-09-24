# Privacy Policy — Pass Tech

- **Applies to**: Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Last changed**: 23 September 2026
- **Publisher**: Files Tech — Patrice Haltaya
- **Contact**: contact@files-tech.com
- **Source code**: https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> This document describes the **Kotlin** version of Pass Tech, 3.0.0. It is not the policy of the
> earlier Flutter versions (2.x, `com.passtech.pass_tech`), which worked differently.

---

## 1. In short

Pass Tech keeps passwords, bank cards and notes on your phone, encrypted. There is no account, no
server of ours, and no copy of your data anywhere else.

- **We collect nothing.** No advertising, no tracker, no analytics, no crash report, no identifier.
- **We receive nothing.** Not a name, not an address, not a password, not a statistic.
- **There is nothing to delete on our side**, because there is nothing on our side.

The app uses the network for **two things only**, both described in §5. Neither sends anything you
typed into it.

## 2. What is on your phone, and where

Everything lives in the app's private directory, which no other app can read.

| File | What it holds |
| --- | --- |
| `pt_vault_a.enc`, `pt_vault_b.enc`, `pt_vault_c.enc` | Three vault slots. **All three always exist and are the same size**, whether you use one, two or three. |
| `pt_heir_a.enc`, `pt_heir_b.enc`, `pt_heir_c.enc` | Three heir snapshots, if inheritance is set up. **All three always exist and are the same size**, for the same reason. |
| `pt_state.enc` | Counters the app needs before any vault is open: failed attempts, the last time it ran, the date of the last update check. |
| Android preferences | Theme, auto-lock delay, clipboard delay, whether screenshots are blocked, whether the domain check is on. No secret. |

**Why three of everything.** A second password opens a second vault, and nothing on the phone says
whether you use it. That only works if a slot in use and a slot unused look exactly alike — same
name, same size, same content to anyone reading the bytes. The slots you do not use hold random
data encrypted under a key that exists nowhere: neither you nor we can ever open them.

## 3. How it is encrypted

- **AES-256-GCM** for the vault, the heir snapshots and the state file.
- The key is derived from your master password with **Argon2id** (19 MiB of memory, 2 passes, 1
  thread — the OWASP 2024 baseline for mobile) **and** with a key held in the phone's secure chip
  that takes part in every single attempt. A copy of your vault taken to another machine cannot be
  attacked there: that chip is not in it.
- **Your master password is never stored**, in any form, anywhere. It cannot be recovered — not by
  us, not by anyone. Forgetting it means losing the vault.
- Biometric unlock, if you turn it on, keeps its key in the Android Keystore, unreadable without
  your fingerprint and destroyed if the fingerprints on the phone change.
- After **5 wrong attempts**, the app waits: 30 seconds, then 1, 5, 15 and 30 minutes. The wait is
  counted in the state file and survives a restart.

## 4. What you can export

- **`.ptbak` backup**: you choose when, and a passphrase of your own encrypts it. We never see it.
  Where you put it afterwards is your decision — a backup on a cloud drive is on that cloud drive.
- **Plain export**: offered for moving to another manager. It is **not encrypted**, and the app says
  so before writing it.

Nothing is exported on its own. There is no automatic backup: Android's cloud backup and
phone-to-phone transfer are both switched off for this app.

## 5. The two times the app uses the network

1. **Update check.** Once your vault is open, the app asks GitHub whether a newer version has been
   published — at most **twice a day**. It asks `api.github.com` for the latest release of this
   project. No account, no cookie, nothing of yours is sent. It downloads and installs nothing; it
   shows you what it found and a link.
2. **Breach check**, on the audit screen, **which you start yourself**. The password is never sent.
   The app computes its SHA-1 fingerprint and sends **the first five characters only** to Have I
   Been Pwned, which answers with every fingerprint beginning that way — tens of thousands of them.
   The comparison happens on your phone. This is the k-anonymity model that service publishes.

**What these two do reveal**, and it is worth saying plainly: whoever can see your connection —
your provider, GitHub, Have I Been Pwned — learns that someone at your address uses this app. They
learn nothing about your vault. If that matters to you, both stop entirely when the app is
disguised as a calculator (§7), and the breach check simply never runs unless you press it.

The app refuses unencrypted HTTP and refuses certificate authorities added to the phone by someone
else.

## 6. The domain check, and the Android permission it needs

If you turn on **"Check the domain before copying"**, the app uses an Android accessibility
service. This is the most intrusive thing it ever asks for, so here is exactly what it does.

- It is **off when you install the app**, and it does not even appear in Android's accessibility
  list until you ask for it.
- It receives events **only from the browsers it knows** — Chrome, Firefox and its beta builds,
  Brave, Edge, Opera, Vivaldi, Samsung Internet, DuckDuckGo. Android sends it nothing from any other
  app: not your bank app, not your messages, not your keyboard.
- From those browsers it reads **the address bar and nothing else** — the host, not the path, not
  the query, not a word of the page.
- That host is kept **in memory only**, one at a time, replaced at each reading and forgotten after
  fifteen seconds. It is never written to disk and never sent anywhere.
- Turning the setting off **withdraws the permission**: the service leaves Android's list, and
  turning it back on asks you for it again.

## 7. Panic mode

If you use it, the app locks, empties the clipboard, disarms the fingerprint, withdraws the
accessibility service and replaces its own name and icon on your home screen with a working
calculator. **Nothing is deleted**: your master password still opens the vault. While disguised, the
app makes no network call at all.

## 8. Inheritance

If you set it up, someone you choose can open a **read-only snapshot** of your vault after a long
enough silence on your side — 90 days by default, plus 7 days of grace, and any unlock starts the
count again. The snapshot is encrypted with a passphrase of its own that you hand over yourself. It
never leaves the phone, there is no cloud and no third party, and we are not involved.

## 9. Android permissions

Measured on the published APK, not on the source:

| Permission | Why |
| --- | --- |
| `INTERNET` | The two calls of §5. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Fingerprint unlock, if you turn it on. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by an Android library; it lets the app talk to itself and nothing else. |

The accessibility service of §6 is **not** a permission in this list: Android grants it separately,
from its own settings, and you can take it back there at any time.

There is no camera permission, no contacts, no location, no storage: Pass Tech asks the system for
a file when you export or import one, and the system hands it that one file.

## 10. Children

The app is not aimed at children and contains no advertising, no profiling and no behavioural
mechanism of any kind.

## 11. Changes to this document

It is published with the app and with its source code. A change ships in a version; the date at the
top says which.

## 12. Contact

contact@files-tech.com
