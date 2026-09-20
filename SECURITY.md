# Security policy — Pass Tech

<strong>English</strong> · [Français](SECURITY.fr.md)

> Pass Tech is a password manager. Security is this project's absolute
> priority. Every responsible report is handled with the highest priority.

## Supported versions

Only the latest version published on GitHub Releases is actively maintained on
the security side.

| Version       | Supported  |
| ------------- | ---------- |
| 2.7.x         | ✅          |
| 2.6.x         | ⚠️ legacy (please update) |
| 2.0.x – 2.5.x | ⚠️ migration only |
| < 2.0.0       | ❌          |

### Recent fix history

- **v2.7.0** (2026-09-20) — Decoy vault: two defects undid the protection it
  promises.
  - **Biometrics ↔ decoy, mutual exclusion.** Biometric unlock opens
    `_Slot.primary` in hard code, and the prompt fires on its own when the
    screen opens. Under coercion, whoever made you place your finger therefore
    obtained the MAIN vault without ever asking for a password — the decoy was
    bypassed without anyone needing to know it existed. The two features now
    exclude each other **both ways**: refusing only one direction left the hole
    open, since biometrics could simply be re-enabled afterwards. The refusal
    depends only on whether a decoy exists, never on the active slot, so it is
    not an oracle. Since this release the guarantee lives in the service, not
    in the screen: a guarantee entrusted to a narrower layer than the one that
    promises it is a guarantee that will fall.
  - **Lingering plaintext export.** The unencrypted export file left in cache
    survived "Delete all my data" **and** panic mode.
    `VaultService.shredCachedExports()` is now called by `deleteVault()` and by
    `panic()`.
  - Translation: 24 French strings hardcoded outside the l10n system were
    lifted. No security effect in themselves, except that the system biometric
    prompt was one of them, and so was the panic-mode disguise label — a French
    "Calculatrice" among German app names points at the app instead of hiding
    it.

- **v2.6.1** (2026-08-11) — The update check, the only code in the app that
  reaches the network. A Wi-Fi captive portal answering "200" with an HTML page
  made the check believe it had run, and suspended it for 12 hours; the
  response was bounded by no size limit; redirects were not re-checked; the
  timeout did not cover reading the response. Vault and encryption unchanged.

- **v2.6.0** (2026-08-04) — Complete removal of Google libraries and services.
  Security audit: the score was misleading on small vaults and ignored
  detected breaches. Master password raised to 12 characters without requiring
  a symbol or a digit. Warning before panic mode (biometrics are disabled by
  it). Cancelling the fingerprint prompt no longer traps the user on the unlock
  screen.

- **v2.5.1** (2026-07-08) — Four-axis expert audit after v2.5.0
  (security / performance-quality / wiring / i18n consistency). The crypto
  foundation was judged exceptional (Argon2id, AES-256-GCM+AAD, a TEE/StrongBox
  sealed non-exportable KEK, a fresh nonce per save). Fixes:
  - **Data loss (v3→v4 migration)** — `_migrateV3ToV4` wrote the new salt to
    storage BEFORE `_saveVaultV4`. A failed save left the file in v3 but the
    stored salt in v4 → the v3 path read a diverging salt at the next unlock →
    permanent `wrongPassword` plus a useless `.bak` (its salt overwritten too).
    Fixed: the salt is written AFTER the save (aligned with `_createSlot` /
    `changeMasterPassword` v2.4.0; the v4 path reads its salt from the FILE,
    not from storage — safe against a crash in between).
  - **Brute-forceable v3 `.bak` (H2)** — the `*_v3.enc.bak` (a complete copy of
    the vault in PBKDF2+AES-CBC, derived from the master password alone, with
    no TEE binding) persisted after migration until the next
    `changeMasterPassword`. It is now purged **as soon as the migration
    succeeds** — no more offline brute-force window undoing the v4 hardening.
  - **Plausible deniability at rest (H1)** — the decoy vault was only created
    at configuration time, under a name (`pt_vault_decoy.enc`) that told a
    forensic examiner which file was the real vault. Reworked: (1) **neutral,
    indistinguishable** names `pt_vault_a.enc` / `pt_vault_b.enc`; (2) a
    **dummy decoy always present** — an empty entry list encrypted with AES-GCM
    under a random password **never stored** (a valid v4 vault that can never be
    unlocked) → a **constant** file profile whether or not a decoy is
    configured; (3) a `pt_decoy_configured` flag in secure storage
    (TEE-encrypted) instead of "the decoy file exists" for the UI. The
    `ensureVaultLayout` migration is **crash-safe** (rename old→neutral plus
    dummy-decoy backfill, with a fallback that reads the old names). Validated
    on device (fingerprint unlock plus decoy creation and deletion).

    > **Correction in v2.5.2 (SEC F6).** "Constant file profile" was FALSE up
    > to v2.5.1: the names and the JSON envelopes were indeed identical field
    > for field, but AES-GCM **preserves length** and no padding was applied.
    > The dummy decoy encrypted `[]`, that is 24 base64 characters in
    > `cipher.data`, against several kilobytes for a real vault: SIZE was a
    > deterministic discriminator, and since the code is public, the exact size
    > of the decoy could be computed in advance. An `ls -l` was enough.
    >
    > Since v2.5.2 the plaintext is padded with spaces on a ladder of rungs
    > (64 KiB, then ×4) **before** encryption, on both write paths, and each
    > slot aligns on the rung that also covers the other's plaintext length —
    > readable without its key, since
    > `plaintextLength = base64Decode(cipher.data).length - 16`. A vault of
    > several hundred entries fits under the first rung: in practice both files
    > are exactly 64 KiB.
    >
    > **Remaining limitation**: two vaults that landed on different rungs (one
    > above 64 KiB, the other not) would become distinguishable again until the
    > next write of the smaller one, which realigns it.
  - **Heir password minimum 12** (was 8, in the service) — aligned with the
    master password and the `.ptbak` export. The heir snapshot has no TEE
    binding.
  - **UI freeze** — `_changePassword` wrapped in try/catch (the
    `barrierDismissible:false` spinner stayed on screen indefinitely if the
    operation threw; risk of a "password changed but UI shows failure" desync).
  - `mounted` guard on `_tryBiometric`; the last 6 raw `SnackBar` call sites
    migrated to `SnackUtils` (contrast/floating); the `entry_detail` date
    localised; 8 orphan l10n keys purged; tolerant JSON import (optional
    id/timestamps); dead code removed. `flutter analyze` 0 issues, 74 tests
    green.

  **Open points (documented, not regressions)**:
  - **Biometrics not invalidated by a new fingerprint enrolment** (TODO M-6) —
    requires a native MethodChannel
    (`setInvalidatedByBiometricEnrollment`).

- **v2.4.4** (2026-05-14) — Expert audit after v2.4.3 (3 parallel agents:
  security / performance / UX): 22 fixes, F2-F12+F15 security /
  P1+P3+P5+P6 performance / U2-U11 UX. Goal: zero vulnerabilities, zero flaws.
  `flutter analyze` 0 issues, 48+7 tests green.

  **Security (high priority)**:
  - **F2** — `_unlockWithBiometricInternal`: the length of the decoded
    biometric key is VALIDATED before it is placed in `_key`. Before: `_key =
    base64Decode(keyB64)` was assigned, then a `length != 32` check came later.
    If storage had been corrupted (downgrade, disk error, targeted attack), a
    non-32-byte key lived briefly in RAM. Now: checked through a local
    `candidate` variable, with wipe + `deleteBiometricKey` + a
    `biometricInvalidated` return if invalid.
  - **F3** — Refactor against decoy RAM exposure. `_v4Unlock` made **pure**
    (it returns `(finalKey, entries, salt, wrappedDek, wrapNonce)` instead of
    mutating `_entries`/`_isOpen`/`_cachedSalt`). `_unlockInternal` rewritten
    to iterate over both slots (plausible deniability against timing) without
    touching global state: it applies the winner ONCE after the loop. Before:
    if the user reused the same password for primary and decoy (a
    misconfiguration), the 2nd iteration briefly exposed the decoy entries in
    RAM (~10 ms) before they were overwritten.
  - **F4** — `passwordMatchesPrimary` shares the `_unlockGate` mutex with
    `unlock()` and `unlockWithBiometric()`. Before: a decoy or heritage setup
    triggered during an unlock in progress (deep links, a fast Back) could
    corrupt `_key`/`_entries` during the v3 path's snapshot/restore.
  - **F5** — `BreachService._userAgent` fixed to a constant
    (`Mozilla/5.0 (compatible)`) instead of a rotating pool of 4 user agents
    drawn at app start through a `static final`. Before: a network observer
    (HIBP logs, MITM, ISP) that always saw the same UA during a session could
    correlate a Pass Tech installation with an HIBP account or IP. Now: the UA
    is strictly identical across every installation and every session, as
    unremarkable as a generic crawler.
  - **F6** — `_onUnlockFail`: the failure counter is `clamp(0, 1000)`. Before:
    an attacker spamming `unlock()` pushed the counter into the tens of
    thousands, wearing NAND and polluting storage (the lockout step stays
    capped at 30 min by the table). No crypto impact, hygiene only.
  - **F7** — `_TotpCard`: the TOTP code is masked by default (`••• •••`), with
    an eye button to reveal it. Before: the 6-digit code was permanently
    visible in the card → trivial shoulder-surfing. Aligned with the
    `_PasswordField` pattern, which requires `show=true`.
  - **F8** — `_HeirPasswordDialog` (heir passphrase entry): a raw
    `TextField(obscureText: true)` replaced by `PasswordTextField`
    (autofillHints=[], enableInteractiveSelection=show,
    keyboardType=visiblePassword, autocorrect=false). A regression of v2.4.3
    U1, which had fixed the master password but forgotten this dialog. The heir
    now types their passphrase into a field protected against third-party
    autofill and long-press copy.
  - **F9** — Legacy `.ptbak` v1/v2: `mac.length != 32` is refused BEFORE the
    `compute pbkdf2Worker`. Before:
    `SecretBytes.constantTimeEq(computed, mac)` returned `false` on a length
    mismatch (comment M-2), but only after burning 600,000 PBKDF2 iterations
    for nothing. A `.ptbak` v1/v2 forged with `mac="AAA="` (3 bytes)
    short-circuited silently.
  - **F10** — `MonotonicClock.nowMs()` serialised through a cached Future, to
    avoid non-atomic `read → max → write` races when two concurrent callers
    (auto-lock timer + unlock + heritage markActive) interleaved around the
    `await _storage.read/write`.
  - **F11** — `_extractTotpSecret`: strict refusal of `otpauth://` URIs whose
    scheme is not `otpauth` or whose host is not `totp`. Before, a QR code
    `otpauth://malicious-host/whatever?secret=ABCD&issuer=<huge string>` was
    accepted as long as `secret` was valid base32.
  - **F12** — `_extractTotpSecret` caps the QR rawValue at 2048 bytes before
    `Uri.parse`. A marginal anti-DoS measure, but it bounds the parser's attack
    surface against a malicious 3-4 KB QR code.
  - **F15** — `PanicService.panic()` now resets `pt_fail_count` AND
    `pt_lockout_until` in flutter_secure_storage. Before: after panic and
    disguise, an attacker landing on the decoy could trigger an "abnormal"
    30-minute lockout with 5 failed attempts — an indirect signal that an
    emergency had just taken place (the legitimate user's earlier counter
    persisted). Now the post-panic state is indistinguishable from a fresh
    boot.

  **Performance**:
  - **P1** — `HomeScreen._filtered` memoised through `_cachedFiltered` /
    `_cachedEntriesLength`, invalidated on mutation (`_filter`, `_sort`,
    `_search`, entry mutations via `_refresh`). Before: 4 `where().toList()`
    passes plus a sort recomputed on EVERY build over 500 entries (4-8 ms × 6
    rebuilds per screen = 25-50 ms saved). At 1000 entries: 150 ms.
  - **P3** — `_TotpCardState` caches `(code, validUntilEpoch)`; the
    `Timer.periodic 1s` still drives the countdown, but the HMAC-SHA1
    computation now runs only every 30 s. A real battery saving while the TOTP
    screen is open (~0.5 ms/s × N views).
  - **P5** — `SetupScreen`: the strength gauge is scope-isolated in a
    `ValueListenableBuilder` bound to `_pass1`. Before, `onChanged: (_)
    => setState(() {})` on both PasswordTextFields rebuilt the WHOLE screen on
    every keystroke. ~3-5 ms saved per keystroke on an S9.
  - **P6** — `AuditScreen._analyze` in a single pass: one loop fills
    `_weak`/`_duplicates`/`_old`/`_missing2fa` plus the
    `_passCount`/`_noteCount`/`_cardCount`/`_with2fa` counters (used in the
    header stats). Before: 4 `where().toList()` passes plus 4 `where().length`
    passes on every rebuild (8×500 = 4000 evaluations).

  **UX / a11y**:
  - **U2** — 4 destructive dialogs aligned on the v2.4.3 U10 pattern (Cancel
    autofocused + a red `FilledButton.tonal` using `cs.errorContainer` /
    `cs.onErrorContainer`): delete entry, delete decoy, disable heritage, turn
    screenshot protection off, and delete the whole vault (the most critical).
    Before: a red `TextButton` without autofocus, and not enough visual
    separation between the destructive action and cancelling.
  - **U3** — `_Badge` (about_screen): text in `cs.onSurface` instead of a
    thematic `color`. WCAG AA contrast reached (~13:1 against the ~2.5:1
    measured on 5 of the 6 badges) — red text on a light red background was
    failing. The coloured icon keeps the visual signal.
  - **U4** — `_scoreIcon()` (audit_screen): a colour-blind-safe icon
    (`check_circle` / `thumb_up_alt` / `warning_amber_rounded` / `error`) next
    to the label. Before, colour alone signalled the quality of the score →
    confusing for deuteranopia and protanopia. A Semantics group announces
    "Score 85 out of 100, Good" to TalkBack.
  - **U5** — `HeirViewScreen`: tooltips on the copy `IconButton`s (the heir is
    by definition unfamiliar with the app, and this is their ONLY use of it)
    plus `cs.onSurfaceVariant` instead of a hardcoded `Colors.grey` (poor
    contrast in dark mode).
  - **U6** — `entry_edit_screen` username and URL: `autocorrect: false` +
    `enableSuggestions: false` + `textCapitalization: none`. Before, Gboard
    turned `john.doe` into `John Doe` at the first character, and capitalised
    `Https://` at the start of the URL field.
  - **U7** — `about_screen`: `Image.asset` icon with `cacheWidth: 160` and
    `cacheHeight: 160` (= 2× the 80dp display width). Without that cap, Flutter
    decoded the asset at devicePixelRatio (240×240 on an S24), about 230 KB of
    RAM per instance. Aligned with PDF Tech / RFT / AI Tech.
  - **U8** — The `LinearProgressIndicator` in audit_screen (HIBP progress) and
    setup_screen (strength gauge): `semanticsLabel` plus a `semanticsValue` in
    "%" for TalkBack. Before, a blind user simply saw the section with no
    progress feedback at all over the ~6 s of the batch.
  - **U9** — `HapticFeedback` added: `selectionClick` on saving an entry (a
    successful user action), `mediumImpact` on a manual lock and on deleting an
    entry (a protective gesture), `heavyImpact` on panic and on deleting the
    whole vault (a critical action). Before, only `clipboard.copy` had
    `lightImpact`. Pattern aligned with AI Tech v0.9.1 U4.
  - **U10** — Onboarding dots: a `Semantics(label: 'X / Y')` group for
    TalkBack. Before, the dots were purely visual — swiping between pages was
    not announced to blind users.
  - **U11** — `snackBarTheme: SnackBarThemeData(behavior:
    SnackBarBehavior.floating)` added to the global `_lightTheme()` and
    `_darkTheme()`. Before, inline `ScaffoldMessenger.of(context).showSnackBar`
    call sites (38 of them outside SnackUtils) had no `behavior:floating` —
    frequent overlap with the FAB on small screens.

  **Quality**:
  - +7 guard tests in `v2_4_4_guards_test.dart` (F11/F12 QR scheme and cap, F9
    `mac.length` pre-check, F5 constant UA).
  - `flutter analyze` 0 issues, 48+7 tests green.

- **v2.4.3** (2026-05-13) — Expert audit after v2.4.2: 24 fixes
  (F1-F6+F10 security / U1 U3 U4 U6 U10 U12 U14 UX / P1.1 P2.1 P2.2
  performance).

  **Security (high priority)**:
  - **F1** — The `.ptbak` v3 format (Argon2id + AES-GCM) replaces v2 (PBKDF2
    600k + AES-CBC + HMAC). The `.ptbak` is the only file that travels *off
    device* (export for a cloud backup, transfer), so it is the most exposed to
    offline GPU brute force (~50M tries/s against PBKDF2 versus ~10/s against
    Argon2id, even on an RTX 4090). The AAD `ptbak:v=3|kdf=argon2id|m=...|
    t=...|p=...|salt=...` prevents downgrades. Reading v1/v2 is preserved for
    backward compatibility; only v3 is written. Strict bounds on the Argon2
    parameters read from the file (`4096 ≤ m ≤ 1,048,576 KiB`, `1 ≤ t ≤ 16`,
    `1 ≤ p ≤ 4`) — a `.ptbak` forged with m=2 GB to OOM the device at
    decryption is refused immediately.
  - **F2** — `deleteBiometricKey()` order reversed: the UI flag
    (`pt_biometric_enabled` in flutter_secure_storage) is now deleted FIRST,
    and the Keystore storage second, best-effort. This avoids the case where a
    partial deletion left the biometric button showing while no usable storage
    existed.
  - **F3** — `PhishingDetectorService.kt`: `System.currentTimeMillis()` →
    `SystemClock.elapsedRealtime()` (boot-based, monotonic). A rooted user
    could move the wall clock backwards between the creation of a snapshot and
    its lookup, making the 15-second freshness window reusable indefinitely.
    Aligned with `MonotonicClock` on the Dart side.
  - **F4** — `VaultService.lock()` now calls
    `ClipboardService.cancelAndClear()` (fire-and-forget). Previously only
    `PanicService.panic()` did so: a manual lock from Settings, or an auto-lock
    by timer, left a pending timer that could remove a callback on a disposed
    context, and the copied value stayed in the clipboard until it expired.
  - **F5** — `VaultService.lock()` now cryptographically wipes the bytes of the
    v4 meta cache (`_cachedSalt`, `_cachedWrappedDek`, `_cachedWrapNonce`)
    before nulling them. Those 3 fields are not secrets *stricto sensu*, but
    their concatenation is a unique fingerprint of the vault, usable to
    correlate memory dumps across sessions.
  - **F6** — `_decryptVaultV3(null)` returned `true` with 0 entries (a dead
    path after v2.0, but one that opened the vault WITHOUT authentication if a
    future caller passed null). Now strictly refused.
  - **F10** — `_v4Unlock`: the `out` key is now cloned and `finalKey` wiped
    BEFORE the meta cache is populated. This prevents an OOM/GC exception
    between `_cachedSalt = ...` and `SecretBytes.wipe` from leaving both the
    raw key and a partial cache behind.

  **UX / a11y**:
  - **U1** — `PasswordTextField`: `autofillHints: const <String>[]` disables
    the Android Autofill service (no cross-app capture of a master password),
    and `enableInteractiveSelection: _show` blocks selection and copy while the
    field is masked (against third-party clipboard managers).
  - **U3** — Search `IconButton` in the AppBar: Flutter's built-in tooltip
    (`closeButtonTooltip` / `searchFieldLabel`).
  - **U4** — `PopupMenuButton` `more_vert`: built-in `moreButtonTooltip`.
  - **U6** — The Argon2id spinner (setup and unlock) wrapped in
    `Semantics(liveRegion: true, label: t.setupEncrypting/unlockDecrypting)` —
    TalkBack now announces the status at the start of the derivation
    (previously 1-3 s of silence on a constrained device).
  - **U10** — "Delete entry" dialog: `TextButton(autofocus: true)` on Cancel (a
    safe default) plus a red `FilledButton.tonal` on Delete using
    `cs.errorContainer/onErrorContainer` instead of `Colors.red`.
  - **U12** — Home empty state: an inline `FilledButton.tonalIcon` "Add" in
    addition to the FAB, more discoverable on first launch.
  - **U14** — Search bar: an inline cross `suffixIcon` to clear the field (more
    discoverable than the AppBar Close action).

  **Performance**:
  - **P1.1** — The search bar is debounced at 150 ms (before, a setState per
    character re-ran sort + filter + toLowerCase × N entries × N fields → 8-12
    ms per character on an S9 with 500 entries).
  - **P2.1** — **ABI splits plus resourceConfigurations FR/EN** enabled
    (previously a 71 MB universal APK). Estimated arm64 saving ~25-30 MB.
    *(As of v2.7.0 the locale list is en, fr, de, it, es.)*
  - **P2.2** — `DateFormat` hoisted into a `static final _dfDMYHm`
    (`entry_detail` rebuilds often because of the 1 Hz TOTP timer).

  **Guard tests**: `test/ptbak_v3_test.dart` (10 tests: v3 round-trip, refusal
  of a wrong passphrase, refusal of forged Argon2 parameters, refusal of a
  non-argon2id KDF algorithm, refusal of a version above 3, refusal of a salt
  shorter than 16 bytes). Total: 48/48 tests green (38 plus 10 new ones).

  No change to the `.enc` vault format (v3/v4 read as before). No change to the
  legacy `.bak` vault format. Existing v2 `.ptbak` files are still read on
  import.

- **v2.4.2** (2026-05-13) — Biometric robustness after an Android fingerprint
  re-enrolment. Before: if the user deleted and re-enrolled their fingerprint,
  the biometric button displayed "Biometric failure" without explaining what to
  do, and the wrap stayed in place (resolving it required a manual off/on
  toggle in Settings). Now: explicit detection of a non-cancel
  `biometric_storage.AuthException`, automatic deletion of the wrap, and a new
  typed result `UnlockResult.biometricInvalidated` driving a clear message:
  "Android fingerprints changed: biometric unlock was disabled for safety.
  Unlock with your master password, then re-enable biometric in Settings."
  An explicit confirmation snack was also added when biometrics are enabled or
  disabled in Settings (previously silent), distinguishing a user cancellation
  from a technical failure. Aligns the pattern with Health Tech v1.5.5.

- **v2.4.0** (2026-05-13) — Zero-vuln zero-flaw audit A1-A20 + B1-B24,
  dynamic FLAG_SECURE with refcounting, a shared MonotonicClock, and
  PanicService purging the phishing snapshot.

## Reporting a vulnerability

**Please do NOT open a public issue on GitHub** — a password manager calls for
strictly coordinated disclosure.

📧 **Send an email, encrypted if you can, to: contact@files-tech.com**

Use this subject line: `[SECURITY] Pass Tech — <short description>`.

Please include:

- A clear description of the vulnerability
- The steps to reproduce it (a PoC is welcome but not required)
- The potential impact (vault compromise? key theft? biometric bypass?)
- The affected version
- A suggested fix, if you have one

## Reinforced response times (password manager)

- Acknowledgement: within 48 hours
- Initial assessment: within 7 days
- Fix: according to severity
  - Critical (vault compromise, key leak) → patch within 7 days
  - Major → patch within 30 days
  - Minor → next release

## Responsible disclosure

Please do not disclose the vulnerability publicly before a fix has been
released and a reasonable update window (90 days minimum) has been left to
users.

## Minimum supported platform

Since v1.13 (the 2026-05 hardening audit), Pass Tech requires **Android 7.0
(API 24) or later**:

- `minSdk = 24` in `android/app/build.gradle.kts`.
- APK signature **v2 and above only** (`enableV1Signing = false`) — this
  neutralises CVE-2017-13156 (Janus), which allowed malicious DEX to be
  injected into a v1-signed APK.
- Android 5 and 6 (under 0.5% of devices in 2026) are no longer supported.

Users on earlier Android versions can stay on the last v1.12.x (unsupported on
the security side) until they change hardware.

## Verifying the integrity of an APK

Every release published on GitHub carries the expected SHA-256 hash of the
arm64-v8a APK in its notes. Before installing, you can check:

```bash
sha256sum app-arm64-v8a-release.apk
```

The result must match the published value exactly. If it does not, **do not
install the APK**.

## Threat model

### Crypto pack v2.0 (vault v4)

Since v2.0.0, Pass Tech uses an entirely replaced crypto pack for the main
vault:

- **KDF**: Argon2id (RFC 9106) — m = 19,456 KiB (19 MiB), t = 2, p = 1,
  L = 32 bytes. The OWASP 2024 choice for a password manager on mobile. It
  replaces PBKDF2-HMAC-SHA256 with 600,000 iterations (v3).
- **AEAD**: AES-GCM-256 (NIST SP 800-38D), a random 96-bit nonce, a 128-bit
  tag. It replaces AES-256-CBC plus a separate HMAC-SHA256 (v3). The GCM AAD
  binds the version, the KEK alias and the KDF parameters (anti-downgrade).
- **Hardware-bound key**: `hwSecret` (32 random bytes) encrypted by a
  **256-bit AES/GCM/NoPadding KEK** in the AndroidKeyStore, under the alias
  `pt_vault_kek_v1`. `setIsStrongBoxBacked(true)` is attempted at creation,
  with a silent fallback to software TEE.
- **Final derivation**: `finalKey = HKDF-SHA256(salt, pwHash || hwSecret,
  "pt:v4", 32)`. The Keystore KEK never leaves the TEE / StrongBox.
- **Plausible deniability**: 2 KEK aliases (`pt_vault_kek_v1` and
  `pt_vault_kek_decoy_v1`) are created systematically on first install, even if
  the decoy vault is not configured — inspecting the Keystore reveals nothing
  about decoy usage. Since **v2.0.2**, a 32-byte dummy salt is generated for
  the decoy even when unused, and the timing of the decoy check is aligned with
  that of the main vault — a timing side-channel attack can no longer tell the
  two paths apart.

### Protected surface

The threat model targets three scenarios:

1. **Anti-coercion** — an attacker forces the user to unlock the app. Pass Tech
   answers with the decoy vault and panic mode. Biometric unlock is
   incompatible with this defence, and the app has excluded the two from each
   other since 2026-09-20: the fingerprint opens the **main** vault without a
   password, so an adversary who makes you place your finger bypassed the decoy
   entirely. See `THREAT_MODEL.md` §4, point 5.
2. **Loss or theft of the device** — the attacker has physical access but not
   the master password. Pass Tech answers with Argon2id, a non-extractable
   Keystore KEK, progressive lockout, auto-lock and RAM wiping.
3. **Sandboxed malware** — another process tries to read the vault. Pass Tech
   answers with the Android sandbox, FLAG_SECURE, `allowBackup=false` and an
   `IS_SENSITIVE` clipboard.

### Pass Tech protects against

- Physical theft of the device (screen lock plus hardware-bound biometrics plus
  progressive lockout)
- Reading the vault file without the master password (AES-GCM-256, Argon2id
  19 MiB / t=2)
- An offline attack on an exfiltrated vault: without the Keystore KEK (bound to
  the device), the vault is unusable even with the master password
- A silent version downgrade: the GCM AAD binds
  `v=4|alias=…|kdf=argon2id|m=…|t=…|p=…` to the tag
- Screenshots and the recent-apps preview (FLAG_SECURE)
- Android cloud backup (`allowBackup=false` plus `dataExtractionRules`)
- Brute force against the master password (progressive lockout, 5 failures →
  30 s to 30 min)
- Reuse after a timeout (configurable auto-lock plus RAM key wiping)
- Partial network MITM (HIBP through k-anonymity, `network_security_config`)

### Pass Tech does NOT protect against

- A rooted device with Frida running (a RASP warning is shown, but the user may
  continue at their own risk)
- A system keylogger or a compromised keyboard
- Full compromise of the hardware Android Keystore (TEE / StrongBox
  extraction)
- A factory reset or a Keystore wipe (Samsung Auto Blocker, factory
  restoration): the KEK is lost → the vault is **unrecoverable** even with the
  master password. Mitigation: export a `.ptbak` BEFORE a factory reset.
- An attacker who has the master password AND access to the unlocked device

### Known limitations

- **Dart `String` is not zeroizable** (M-4): the Dart VM offers no reliable way
  to erase a `String` in memory. Master passwords are typed into a
  `TextEditingController`, then converted as quickly as possible into a
  zeroizable `Uint8List`. A residual time window remains (a few milliseconds to
  a few seconds if the GC pauses). **This limitation extends to several
  paths**:
  - **`.ptbak` import**: after decryption, `Uint8List plain` is explicitly
    wiped, but `utf8.decode(plain)` and then `jsonDecode(...)` create
    intermediate Dart `String`s that cannot be wiped and that hold the
    passwords until the GC runs. Wiping `plain` reduces the surface without
    closing it.
  - **TOTP `DateTime.now()`**: `TotpService` uses the wall clock (RFC 6238
    requires clock sync with the server). A root attacker able to change the
    system clock (`adb shell date -s`) can replay an expired TOTP code within
    the tolerated ±30 s window. Partial mitigations: no TOTP is stored in the
    clear (the Base32 secret lives in the encrypted vault), and the wall clock
    cannot be used to unlock the vault itself — since v2.5.2 the lockout is
    anchored on `SystemClock.elapsedRealtime()`, insensitive to the Date and
    time settings as well as to NTP.

    > **Correction in v2.5.2 (SEC F5/F17).** This claim was FALSE up to
    > v2.5.1. `MonotonicClock.nowMs()` returns
    > `max(DateTime.now(), maxSeen)`: monotonicity only worked against clock
    > REWINDS, whereas moving the clock FORWARD is the attacker's direction.
    > Moving the date forward by a day in the Android settings erased the
    > anti-brute-force lockout, with no root and no ADB. The lockout now
    > persists a remaining duration plus an `elapsedRealtime` anchor, and
    > counts down the time that actually elapsed.
  Overall mitigation: a short auto-lock, FLAG_SECURE, and a hardware-backed
  Keystore where available.
- **Wiping the Keystore means losing the vault**: a factory reset, a factory
  restoration, and certain Samsung Auto Blocker operations invalidate the KEK.
  Without the KEK, the vault is mathematically unrecoverable even with the
  master password. Mitigation: regularly export a `.ptbak` (a portable
  Argon2id + AES-GCM pack, independent of the Keystore).
- **Panic mode is not instantaneous on some OEMs**: disguising the icon through
  an activity alias can take 1–3 s on Samsung One UI, because of the launcher
  cache.
- **Biometrics after a fingerprint re-enrolment** (M-6): `biometric_storage`
  (6.0.0-dev.5 as of v2.7.0, and every earlier version) does not expose
  `setInvalidatedByBiometricEnrollment(true)` in its public API — verified
  across the whole package, on both the Dart and the Kotlin side. If an
  attacker with physical access to the device and the Android PIN adds their
  own fingerprint, they could in theory unlock the biometrics without knowing
  the master password. Mitigation since v2.5.0: a note in the Settings UI
  telling the user to disable and re-enable biometrics after every Android
  fingerprint or face change. A definitive fix (a custom KeystoreBridge) is in
  the ROADMAP_HARDENING backlog.

### v3 → v4 migration

On the first open after updating to v2.0.0, Pass Tech automatically detects a
v3 vault and converts it to v4:

1. The master password is requested through the normal unlock UI.
2. The v3 vault is decrypted (PBKDF2 + AES-CBC + HMAC) in memory.
3. A `pt_vault_v3.enc.bak` copy is created next to the original file.
4. A new salt and hwSecret are generated; the Keystore KEK is created.
5. The vault is rewritten in v4 format (atomic tmp+rename).
6. Any biometric setup (the 64-byte v3 cache) is invalidated — it has to be
   re-enabled in Settings.

There is no way back: v3 can no longer be written. v3 `.ptbak` files remain
readable up to v2.1.

## Scope

Vulnerabilities in scope:

- Cryptographic weakness (Argon2id, AES-GCM, HKDF, AAD, nonce, IV)
- Bypassing hardware-bound biometrics
- Bypassing the lockout, or making brute force easier
- Leaking the encrypted vault or the master key outside the process
- Exploitable timing side-channels

Out of scope:

- UX bugs with no security impact
- Vulnerabilities in `flutter_secure_storage`, `biometric_storage`, `encrypt`
  or `crypto` that have already been reported upstream
- Attacks requiring a rooted or compromised device (already covered by RASP)
- Physical attacks on an unlocked device with the vault open
