# Pass Tech — threat model

**Applies to** Pass Tech 3.0.0 (`com.filestech.pass_tech`), the Kotlin rewrite. The 2.x Flutter app
had its own, and worked differently.

**English** · [Français](THREAT_MODEL.fr.md)

This document says what the app protects, against whom, and — at least as importantly — **what it
does not**. A security promise that is not bounded is not a promise.

---

## 1. What is protected

The vault: passwords, two-factor secrets, bank cards and secure notes. Its file, its heir snapshot,
and the counters the app needs before anything is open.

### The cryptographic chain

```
pwHash   = Argon2id(password, salt, m = 19 MiB, t = 2, p = 1)        32 bytes
hw       = HMAC-SHA256(slot key, domain || pwHash)                   computed INSIDE the Keystore
finalKey = HKDF-SHA256(salt, pwHash || hw, info, 32)
file     = AES-256-GCM(finalKey, nonce, plaintext, aad)
```

The slot key never leaves the secure hardware and **takes part in every attempt**. This is the
design's central choice, and it replaced a weaker one: the Flutter app wrapped a random secret that
did not depend on the password, so a single unwrap let an attacker carry the files elsewhere and
test passwords at GPU speed. Here, a guess can only be checked on this phone, through its Keystore.

`domain` and `info` separate a vault file from an heir snapshot: the same passphrase on the same
slot yields two unrelated keys, so one file put where the other belongs opens nothing.

## 2. Adversaries considered

| # | Adversary | Capability | Covered |
|---|---|---|---|
| A1 | Someone who picks up the phone, locked | Physical access, no credentials | Yes |
| A2 | Someone who makes you unlock it | Coercion — border, robbery, search | Partly — §4 |
| A3 | Another app on the phone | Its own sandbox, the clipboard, the screen | Yes |
| A4 | Whoever watches the network | Sees connections, not their contents | Yes |
| A5 | Someone who takes the files | A copy of `/data/data/...`, off the phone | Yes |
| A6 | Us, the publisher | Ships the code | Yes — there is nothing to hand over |
| A7 | Root, or a compromised phone | Reads any app's memory | **No** — §5 |

## 3. Protections, and their exact reach

### 3.1 Guessing the master password

Five free attempts, then 30 s, 1, 5, 15 and 30 minutes. The count is in the encrypted state file and
is measured on a clock that counts deep sleep, so a restart does not clear it. Unlocking cancels its
own attempt and no earlier one, so alternating between two vaults never shortens the wait.

Off the phone, the wait does not apply — but the hardware step does: the files cannot be attacked on
another machine at all.

### 3.2 Screenshots and the recent-apps preview

`FLAG_SECURE` on every window while the setting is on, which also removes the system thumbnail. The
owner can switch it off (Samsung Knox blocks the cross-app clipboard while it is on), and the app
asks for confirmation before it does.

### 3.3 Clipboard

Cleared after a chosen delay, from a receiver, so it happens even if the app has been closed. Marked
sensitive on Android 13 and above, which keeps it out of the clipboard preview.

**Not covered**: another app reading the clipboard during the delay. Android has no way to hand a
secret to one app only.

**Not covered either, and measured rather than reasoned**: some manufacturers' keyboards keep a
clipboard history of their own, and it is not the system clipboard. On a Galaxy S24 running Android
16, the value was still being offered in the Samsung keyboard's suggestion bar a minute after the
system clipboard had been emptied — verified on 2026-09-24 by long-pressing in a text field, where
nothing was left to paste. `EXTRA_IS_SENSITIVE` is set on every copy and is what should keep a value
out of such a history; that keyboard does not honour it. Nothing in an app can reach another app's
store. Clearing the keyboard's own clipboard history is the owner's to do.

### 3.4 The address check (optional, off by default)

Before a password is copied, the app compares the site open in the browser with the ones the entry
declares. It reads the address bar of nine browsers through an accessibility service that is
disabled in the manifest until the setting is switched on.

**Its reach is exactly what a browser displays.** A browser it does not know, or a page inside
another app, cannot be read — and the app then says the check could not be made, rather than saying
all is well. It is a warning, not a guarantee: a password can still be copied past it, on purpose.

### 3.5 Backups

`.ptbak` is encrypted under a passphrase of its own. Where it is kept afterwards is outside the
app's reach: a backup on a cloud drive is on that cloud drive. The plain export is not encrypted,
and the app says so before writing it.

Android's own cloud backup and phone-to-phone transfer are excluded for every domain.

## 4. Plausible deniability — reach and limits

### What is actually guaranteed

Three slots exist from the first launch. All three are the same size, all three are written the same
way, and the ones not in use hold random data encrypted under a key that exists nowhere — neither
the owner nor the publisher can ever open them. Nothing in the file names, the sizes or the bytes
says how many are in use.

The decoy never writes to the real vault. Opening the decoy, filling it, deleting entries from it
leaves the other slots untouched.

### What is NOT guaranteed

- **Someone who knows this app exists knows a second vault may exist.** That is public, by design:
  the feature is in this file, on the store page and in the app. Deniability is against a search,
  not against knowledge.
- **How many slots are free can be read from inside the app**, by someone holding the phone with a
  vault open. Setting up a decoy takes a free slot, and the app says so when there is none left: two
  decoys can be set up in a row on a phone with one vault, one on a phone with two. The count is not
  free — each attempt is charged against the same schedule as a wrong password, five and then a
  growing wait — but it is not closed either, and with three slots it cannot be: every way of hiding
  the refusal tells the same thing sooner rather than later. Accepted, as R6.
- **A capacity probe.** The amount of free space on the phone changes as a vault grows. Someone
  measuring it over time, with the phone in hand between measurements, could infer that something
  grew. This is a known residual (R5 below), accepted rather than solved.
- **The disguise is the launcher's business only.** Panic mode swaps the launcher aliases; the
  `<application android:label>` cannot be changed at run time, so Settings › Apps, the share sheet
  and the permission manager keep saying Pass Tech. This hides the app from a glance at a home
  screen, not from someone who opens the settings.
- **Biometric unlock and the decoy are mutually exclusive**, and the app enforces it: a fingerprint
  opens the real vault directly, which would undo the decoy in the very situation it exists for.

## 5. Outside the threat model

### 5.1 A rooted or compromised phone

Anything with that access reads any app's memory, including this one. The app notices what it can —
root, a debugger, an emulator — and says so on the unlock screen before a password is typed. Those
checks are easy to defeat; they are a reminder, never a guarantee.

### 5.2 Third-party libraries

No Google Play Services, no ML Kit, no Firebase, no analytics. The dependencies are AndroidX,
Jetpack Compose, Hilt, kotlinx and Bouncy Castle, listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The permission set that results is checked on the
built APK by the `Promises` workflow, not read from the source manifest.

### 5.3 TOTP codes and the clock

TOTP depends on the phone's clock. A phone set to the wrong time produces codes a server rejects.
The app does not correct the clock and does not ask the network what time it is.

### 5.4 Heir access

A countdown on the phone, not a service. If the phone is lost, wiped or broken, the snapshot goes
with it. The heir's passphrase is handed over by the owner, by whatever means they choose — the app
has no part in that, and no way to help if it is lost.

## 6. Known residual risks

| # | Risk | Severity | State |
|---|---|---|---|
| R1 | The vault key is in memory while the vault is open | Structural | Accepted — wiped on lock and after use |
| R2 | Biometric enrolment does not invalidate the key on some manufacturers | Medium | Warned about in Settings; the master password is still required to re-arm |
| R3 | The disguise is partial (§4) | Medium | Documented; a second APK would be needed to go further |
| R4 | Overwriting a file is not guaranteed on flash storage | Low | Best effort — copy-on-write and wear levelling are outside our reach |
| R5 | A capacity probe can suggest that a vault grew (§4) | Low | Accepted; the alternative would be to pad every slot to a size nobody would accept |
| R6 | The number of free slots is countable from inside the app (§4) | Medium | Accepted, and rate-limited: every attempt is charged to the brute-force schedule |
| R7 | The heir countdown can be brought forward by restarting the phone with the date moved | Low | Needs the phone's own screen lock. Inside one session the countdown advances no faster than the boot clock, which nothing in Settings can move |
| R8 | A manufacturer's keyboard may keep a copied value in a clipboard history of its own (§3.3) | Medium | Outside any app's reach. The system clipboard is cleared on time; that history is the owner's to clear |

## 7. Reporting a vulnerability

**Do not open a public issue.** A password manager needs coordinated disclosure. The procedure is in
[SECURITY.md](SECURITY.md).

---

## Log

| Date | Revision |
|---|---|
| 2026-09-24 | After the security audit of that date. Two residuals that were real and unstated are now stated (R6, R7), and three things this document claimed are now true of the code rather than of the intention: the slot files are evened out even when the session holds no key for them, the clipboard alarm wakes the phone, and a password that opens nothing is counted on every screen that checks one. |
| 2026-09-23 | Rewritten for the Kotlin app. The hardware step now takes part in every attempt, where the Flutter app wrapped a password-independent secret; three slots replace two; the ML Kit risk is gone with the library; and this document is now in English, with a French version beside it. |
| 2026-08-03 | Created for the Flutter app, after the audit of that date. |
