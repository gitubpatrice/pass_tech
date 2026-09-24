# Security policy — Pass Tech

**English** · [Français](SECURITY.fr.md)

> Pass Tech is a password manager. Security is this project's absolute priority, and every
> responsible report is handled with the highest priority.

## Supported versions

Only the latest version published on GitHub Releases is actively maintained on the security side.

| Version | Application id | Supported |
|---|---|---|
| 3.0.x | `com.filestech.pass_tech` | ✅ |
| 2.7.x | `com.passtech.pass_tech` | ❌ **end of life** — see below |
| < 2.7 | `com.passtech.pass_tech` | ❌ |

**3.0.0 is a rewrite, in Kotlin, and a different application.** It installs beside 2.x rather than
over it. To move across: export a `.ptbak` backup from 2.x, import it into 3.0, and remove the old
app once you are satisfied. The 2.x history lives in this repository's tags; its own security
document is the one shipped with it.

**2.7.1 is the last Flutter release, and it is finished.** It gets no further work of any kind,
security fixes included. Its download stays up on GitHub Releases instead of being taken down, for
one reason: it runs on Android 7, and 3.0 needs Android 8. Taking it away would remove the only
working version from phones that cannot install the replacement, and give them nothing back. Nobody
else should be sent to it.

A backup is never stranded in either direction: 3.0 reads every `.ptbak` ever written — v1, v2 and
v3 — and the file it writes imports back into 2.7.1.

## Reporting a vulnerability

**Please do NOT open a public issue on GitHub** — a password manager calls for strictly coordinated
disclosure.

📧 **Email, encrypted if you can, to: contact@files-tech.com**

Subject: `[SECURITY] Pass Tech — <short description>`.

Please include:

- a clear description of the vulnerability;
- the steps to reproduce it (a proof of concept is welcome, not required);
- the impact you see — vault compromise, key extraction, biometric bypass, a decoy vault that can be
  shown to exist;
- the affected version, and the phone and Android version you saw it on;
- a suggested fix, if you have one.

## Response times

- Acknowledgement: within 48 hours.
- Initial assessment: within 7 days.
- Fix, by severity:
  - **Critical** — vault compromise, key leak, a decoy vault provably present → patch within 7 days;
  - **Major** → patch within 30 days;
  - **Minor** → next release.

## Responsible disclosure

Please do not disclose publicly before a fix has been released and a reasonable update window — 90
days at least — has been left to the people running the app.

## Minimum supported platform

Pass Tech 3.0 requires **Android 8.0 (API 26) or later**.

- `minSdk = 26`, declared in `app/build.gradle.kts`, which is what the hardware-backed key work and
  the file-based Keystore usage assume.
- APK signature **v2, v3 and v4 only** (`enableV1Signing = false`), which neutralises
  CVE-2017-13156 (Janus): that attack injects DEX into a v1-signed APK.
- Android 7 and earlier are not supported by this version. The 2.x app went down to Android 7 and is
  no longer maintained on the security side.

## Verifying what you installed

Every release publishes the SHA-256 of **each file it carries**. Before installing:

```bash
sha256sum <the file you downloaded>
```

The result must match the value published for that same file, exactly. If it does not, **do not
install it**.

## What is protected, and what is not

The threat model is a document of its own, and it is the one to read: it names the adversaries, the
exact reach of each protection, and the residual risks that are accepted rather than solved —
including the limits of the decoy vault and of the panic mode.

→ [THREAT_MODEL.md](THREAT_MODEL.md)

## Scope

**In scope**: the vault and its encryption, the key derivation and its hardware step, the brute-force
guard, the biometric binding, the decoy vault and the panic mode, the heir snapshot, the address
check and the accessibility service it uses, the clipboard, the backup and import paths, the update
check, and the permission set of the published APK.

**Out of scope**: a rooted or otherwise compromised phone, a physical attack on the secure hardware,
Android itself, and any browser or site the address check reads. These are stated in
[THREAT_MODEL.md](THREAT_MODEL.md) §5 rather than treated as defects.

## What the build checks on every commit

- `CI` — build, unit tests, Android lint and detekt, with no baseline and no tolerated issue.
- `Promises` — builds a **release** APK and compares its **merged** manifest with
  [`config/expected-permissions.txt`](config/expected-permissions.txt), both ways. A permission that
  appears without being listed fails the build, and so does one listed that disappears. The Flutter
  app once shipped `ACCESS_NETWORK_STATE` declared nowhere in the repository; that cannot happen
  quietly again.
- `CodeQL` — static analysis on the Kotlin sources.
