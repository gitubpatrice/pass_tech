# Third-party notices — Pass Tech

Pass Tech 3.0 (`com.filestech.pass_tech`) is written in Kotlin with Jetpack Compose. Everything it
ships that it did not write is listed here. The versions are the ones pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml), which is the file that decides what goes
into the APK.

**No Google Play Services, no ML Kit, no Firebase, no analytics SDK, no crash reporter.** That is a
property the `Promises` workflow keeps honest by measuring the permission set of the built APK
rather than reading the source manifest.

## Shipped in the APK

| Library | Publisher | Licence | What it does here |
|---|---|---|---|
| `androidx.core:core-ktx` | Google / AOSP | Apache 2.0 | Android platform helpers |
| `androidx.activity:activity-compose` | Google / AOSP | Apache 2.0 | The activity that hosts Compose |
| `androidx.lifecycle:*` (runtime, compose, viewmodel, process) | Google / AOSP | Apache 2.0 | View models, and the process lifecycle the auto-lock observes |
| `androidx.compose:*` (UI, graphics, Material 3, icons) | Google / AOSP | Apache 2.0 | The whole user interface |
| `androidx.navigation:navigation-compose` | Google / AOSP | Apache 2.0 | Navigation |
| `androidx.datastore:datastore-preferences` | Google / AOSP | Apache 2.0 | The plain settings — never a secret |
| `androidx.core:core-splashscreen` | Google / AOSP | Apache 2.0 | The system splash screen |
| `androidx.biometric:biometric` | Google / AOSP | Apache 2.0 | `BiometricPrompt` and its `CryptoObject` |
| `com.google.dagger:hilt-android` | Google | Apache 2.0 | Dependency injection |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | JetBrains | Apache 2.0 | Concurrency |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JetBrains | Apache 2.0 | Reading and writing the vault's JSON, inside the encrypted container |
| `org.bouncycastle:bcprov-jdk18on` | The Legion of the Bouncy Castle | Bouncy Castle (MIT-style) | **Argon2id** — the key derivation. Everything else cryptographic goes through the Android platform: AES-256-GCM, HMAC-SHA256 in the Keystore, HKDF written against the platform's primitives |

The `androidx.biometric` library declares `USE_BIOMETRIC` and, for Android 8, `USE_FINGERPRINT`. An
AndroidX library also declares the dynamic-receiver permission that lets an app broadcast to itself.
Those three are the only permissions this app carries beyond `INTERNET`, and all four are pinned in
[`config/expected-permissions.txt`](config/expected-permissions.txt).

## Build and test only — not in the APK

| Tool | Publisher | Licence |
|---|---|---|
| Android Gradle Plugin, Kotlin, KSP | Google, JetBrains | Apache 2.0 |
| detekt and `detekt-formatting` | detekt contributors | Apache 2.0 |
| JUnit 5 (Jupiter, Platform) | JUnit team | EPL 2.0 |
| MockK | MockK contributors | Apache 2.0 |
| Turbine | Cash App | Apache 2.0 |
| Truth | Google | Apache 2.0 |
| Robolectric | Robolectric contributors | MIT |
| `androidx.test:*`, Compose UI test | Google / AOSP | Apache 2.0 |

## Word lists

The passphrase generator draws from five lists — English, French, German, Italian, Spanish — held in
`core/password/words/`. They are this project's own, not a third-party word list, and carry its
licence. The French one comes from the 2.x app and is kept word for word: shortening it would change
the strength of a passphrase somebody has already written down.

## Licence of Pass Tech itself

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
