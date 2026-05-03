# Roadmap Hardening — Pass Tech

**Statut** : 2026-05-03. Document accompagnant l'audit `AUDIT_2026-05-03.md`.
Couvre les 3 findings 🟠 ÉLEVÉ qui n'ont pas été corrigés dans la passe
courante car ils nécessitent une migration de schéma vault (v3 → v4) et,
pour H-3, une plateforme channel Kotlin maison. **Aucun de ces changements
ne doit être implémenté sans validation humaine** : une régression crypto
sur un password manager rend irrécupérables les coffres existants.

---

## Décisions verrouillées (2026-05-03)

| # | Question | Décision |
|---|---|---|
| 1 | `minSdk = 24` (Android 7+, exclut 5/6) | **Verrouillé.** Parc < 0.5 % en 2026, gain sécurité = neutralisation CVE-2017-13156 (Janus) via `enableV1Signing=false`. À documenter dans `SECURITY.md` et notes de release. |
| 2 | H-1 + H-2 + H-3 : bundle ou split ? | **Bundle dans une release majeure unique `v2.0.0`.** Un seul saut de format vault v3→v4, un seul chemin de migration testé, communication utilisateur unique. Splitter multiplierait la surface de migration et de test. |
| 3 | Lib Argon2id | **`cryptography` (Dart pur) + `cryptography_flutter` (FFI libsodium-backed)**. Pure-Dart fallback assure portabilité tests, FFI assure perf < 1 s sur S9. Benchmark obligatoire S9/S24 avant tag final, mais le choix de stack est figé. |
| 4 | Décoy + Keystore | **Créer toujours 2 alias dès la 1ʳᵉ install** (`pt_vault_kek_v1` + `pt_vault_kek_decoy_v1`), même si l'utilisateur n'active pas le decoy. Préserve le déni plausible : la simple inspection du Keystore ne révèle rien sur l'existence d'un coffre leurre. Coût : 1 KEK supplémentaire dormante (négligeable). |

---

## Vue d'ensemble — migration v4 unique

H-1, H-2 et H-3 partagent **le même point de bascule** : un nouveau format
de fichier `pt_vault.enc` (v4) lisible/écrit par une nouvelle implémentation,
avec un chemin de migration depuis v3. Plutôt que 3 migrations séparées,
**il faut les bundler dans une seule version v4**.

Ordre d'application recommandé (dans cet ordre, dans **un même tag de
release** majeur, ex. v2.0.0) :

1. H-2 (Argon2id) : remplace le KDF.
2. H-1 (AES-GCM) : remplace AES-CBC + HMAC séparé.
3. H-3 (Keystore-bound key) : enveloppe avec un secret hardware.

Ces 3 changements forment un **seul saut de format** : impossible de les
livrer indépendamment sans multiplier les chemins de migration et exploser
la surface de test.

---

## Format vault v4 — proposition

```jsonc
{
  "version":     4,
  "magic":       "PTVAULT",          // nouveau, pour distinguer
  "kdf": {
    "algo":      "argon2id",
    "m":         19456,              // 19 MiB en KiB (OWASP 2024)
    "t":         2,
    "p":         1,
    "salt":      "<base64, 32 bytes>"
  },
  "kek": {                           // Key-Encryption-Key (Keystore-bound)
    "algo":      "AES-GCM-256",
    "alias":     "pt_vault_kek_v1",  // alias AndroidKeyStore
    "wrappedDek":"<base64>",         // DEK chiffré par la KEK Keystore
    "wrapNonce": "<base64, 12 bytes>",
    "wrapTag":   "<base64, 16 bytes>" // tag GCM concaténé ou inclus dans wrappedDek
  },
  "cipher": {
    "algo":      "AES-GCM-256",
    "nonce":     "<base64, 12 bytes>",
    "data":      "<base64, ciphertext+tag>",
    "aad":       "pt:v=4|alias=pt_vault_kek_v1"
  }
}
```

### Dérivation finale (two-secret) — H-3

```
pwHash    = Argon2id(masterPassword, kdf.salt, kdf.m, kdf.t, kdf.p, 32)
hwSecret  = AndroidKeyStore.unwrap(kek.alias, kek.wrappedDek, kek.wrapNonce)
            // hwSecret ne quitte JAMAIS le TEE / StrongBox
finalKey  = HKDF-SHA256(salt=kdf.salt, ikm=pwHash || hwSecret, info="pt:v4", L=32)
plaintext = AES-GCM-Decrypt(finalKey, cipher.nonce, cipher.data, cipher.aad)
```

Notes :
- `hwSecret` est une `byte[32]` aléatoire générée à la **création du vault**
  par Android et chiffrée par une clé `AES/GCM/NoPadding` Keystore non-
  extractible (`setUserAuthenticationRequired(false)` car l'auth est déjà
  faite par le master password ; sinon c'est une triple auth).
- La Keystore stocke uniquement la **KEK** (clé qui chiffre `hwSecret`).
  `hwSecret` lui-même est sérialisé chiffré dans le JSON → portable entre
  installations sur le même device, perdu en cas de wipe (= comportement
  attendu : pas de fuite cloud).
- StrongBox : tenter `setIsStrongBoxBacked(true)` à la création, fallback
  sur TEE software si non dispo (S22/S23 OK, S9 ancien non).

---

## Composants à implémenter

### 1. KDF Argon2id (H-2)

**Fichiers à toucher** :
- `pubspec.yaml` : ajouter `cryptography: ^2.7.x` (Dart pur, fallback +
  vecteurs de test) **et** `cryptography_flutter: ^2.x` (FFI libsodium-backed,
  natif Android). Décision #3 verrouillée. Benchmark S9/S24 sert uniquement
  à fixer `m`/`t`/`p` finaux (cible : < 1 s par unlock).
- `lib/services/kdf_service.dart` (nouveau) : encapsule `argon2id(password,
  salt, m, t, p, outLen)` derrière `compute()` (isolate).
- `lib/services/vault_service.dart` :
  - `_deriveKey()` devient `_deriveKeyV4()` (Argon2id) ou `_deriveKeyV3()`
    (PBKDF2, conservé pour read-only).
  - Constantes : `_argon2M = 19456 /* KiB */`, `_argon2T = 2`, `_argon2P = 1`.

**Tests à ajouter** : vecteurs RFC 9106 §5.4 (Argon2id) → `test/argon2_test.dart`.

### 2. AES-GCM + suppression HMAC séparé (H-1)

**Fichiers à toucher** :
- `lib/services/vault_service.dart` : nouveau `_encryptVaultV4()` /
  `_decryptVaultV4()`. Conserver `_decryptVault()` legacy pour v1/v2/v3 read.
- `lib/services/import_export_service.dart` : même chose pour `.ptbak`
  format v3 (incrémenter `_backupVersion = 3`).
- `lib/services/heritage_service.dart` : format `pt_heir.enc` v2 GCM.

API GCM : décision #3 verrouillée — `cryptography` (Dart pur) +
`cryptography_flutter` (natif Android). `pointycastle` n'est pas retenu
(plus lent, surface d'audit plus large pour la même qualité crypto).

**AAD GCM** : reprendre la chaîne `pt:v=4|alias=...|kdf=argon2id` pour
inclure tous les paramètres dans le tag GCM (anti-downgrade).

**Tests à ajouter** : NIST GCM test vectors (gcmEncryptExtIV256.rsp) +
round-trip + bit-flip detection (déjà couvert par GCM tag).

### 3. Keystore-bound key (H-3)

**Plateforme channel Kotlin** :

Nouveau fichier : `android/app/src/main/kotlin/com/passtech/pass_tech/KeystoreBridge.kt`

```kotlin
class KeystoreBridge(private val ctx: Context) : MethodCallHandler {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "createKek"   -> result.success(createKek(call.argument<String>("alias")!!))
            "wrap"        -> result.success(wrap(call.argument<String>("alias")!!,
                                                 call.argument<ByteArray>("plaintext")!!))
            "unwrap"      -> result.success(unwrap(call.argument<String>("alias")!!,
                                                   call.argument<ByteArray>("ciphertext")!!,
                                                   call.argument<ByteArray>("nonce")!!))
            "deleteKek"   -> { ks.deleteEntry(call.argument<String>("alias")!!); result.success(null) }
            "hasKek"      -> result.success(ks.containsAlias(call.argument<String>("alias")!!))
            else          -> result.notImplemented()
        }
    }

    private fun createKek(alias: String): Boolean {
        if (ks.containsAlias(alias)) return false
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false) // master password fait l'auth
            // .setIsStrongBoxBacked(true)         // try-catch fallback TEE
            .build()
        gen.init(spec)
        gen.generateKey()
        return true
    }

    private fun wrap(alias: String, plaintext: ByteArray): Map<String, ByteArray> {
        val key = ks.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext)
        return mapOf("ciphertext" to ct, "nonce" to cipher.iv)
    }

    private fun unwrap(alias: String, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }
}
```

**Branchement Flutter** :
- `MainActivity.configureFlutterEngine()` enregistre `MethodChannel
  "com.passtech.pass_tech/keystore"` → `KeystoreBridge`.
- `lib/services/keystore_service.dart` (nouveau) : wrappe le channel.

**Cycle de vie** :
- `setupVault(password)` : génère `hwSecret` (32 bytes random Dart), appelle
  `keystore.createKek("pt_vault_kek_v1")`, puis `keystore.wrap(alias,
  hwSecret)` → stocke `wrappedDek`+`wrapNonce` dans le JSON.
- `unlock(password)` : `pwHash = Argon2id(...)`, `hwSecret =
  keystore.unwrap(alias, wrappedDek, wrapNonce)`, `finalKey = HKDF(...)`.
- `deleteVault()` : appelle `keystore.deleteKek("pt_vault_kek_v1")`.

**Décoy** (décision #4 verrouillée) : créer **systématiquement** les 2 alias
`pt_vault_kek_v1` + `pt_vault_kek_decoy_v1` dès la première install, même
si l'utilisateur n'active jamais le decoy. La simple inspection du Keystore
ne révèle alors rien : le profil "2 alias" est constant pour tous les
utilisateurs Pass Tech. Sans cette discipline, la présence de 2 alias
trahirait l'existence d'un coffre leurre et casserait la promesse de déni
plausible.

---

## Migration v3 → v4 — algorithme

À la 1ʳᵉ ouverture après MAJ :

```
1. Lecture vault → détection version.
2. Si version == 3 :
   a. Demander le master password (UI normale d'unlock).
   b. Décoder via _decryptVaultV3() (PBKDF2 + AES-CBC).
   c. Si OK :
      - Générer salt v4 (32 bytes), hwSecret (32 bytes).
      - createKek(alias).
      - wrap(alias, hwSecret) → wrappedDek + wrapNonce.
      - pwHashV4 = Argon2id(pwd, salt v4, ...).
      - finalKey = HKDF(salt v4, pwHashV4 || hwSecret, "pt:v4").
      - Chiffrer entries via AES-GCM(finalKey, nonce, AAD).
      - Atomic write dans pt_vault.enc (format v4).
      - Wipe pwHashV4, hwSecret, finalKey, ancienne clé.
3. Pas de retour en arrière : le format v3 ne peut plus être réécrit.
4. Conserver sur 1 release une fonction "Récupération v3 → v4 manuelle" en
   cas de bug → backup automatique du fichier v3 dans pt_vault_v3.enc.bak,
   supprimé après 1ʳᵉ ouverture v4 réussie.
```

**Risques** :
- Argon2id S9 (Snapdragon 845, RAM 4 Go) : 19 MiB × 2 itérations ≈ 1.5 s.
  Acceptable mais **à benchmarker** avant lock-in. Si trop lent, descendre
  à `m=12 MiB, t=3` (toujours OWASP-compliant).
- StrongBox absent : silently fallback TEE — **logger en debug uniquement**,
  ne pas exposer le détail à l'utilisateur (info marketing : "stocké dans
  l'élément sécurisé du téléphone").
- KEK Keystore détruite (factory reset, fallback Auto Blocker Samsung) :
  vault irrécupérable même avec master password. **Documenter clairement
  dans SECURITY.md** : "exporter une `.ptbak` AVANT factory reset".

---

## Tests à écrire avant merge v4

1. `test/argon2_test.dart` — RFC 9106 §5.4 vectors.
2. `test/aes_gcm_test.dart` — NIST gcmEncryptExtIV256.rsp.
3. `test/migration_v3_to_v4_test.dart` — round-trip : créer un vault v3 par
   l'ancien code, ouvrir avec le nouveau, vérifier conversion automatique
   et lecture des entries.
4. `test/keystore_bridge_test.dart` — mockés (le vrai Keystore exige device
   ou émulateur API 28+).
5. Tests E2E sur device Samsung S9 + S24 : unlock latency, factory reset,
   ajout/retrait d'empreinte.

---

## Estimations

| Lot | Effort dev | Effort tests | Risque |
|---|---|---|---|
| H-2 (Argon2id) | 2 j | 1 j | Moyen (perf S9) |
| H-1 (AES-GCM)  | 2 j | 1 j | Faible (algo standard) |
| H-3 (Keystore) | 4 j | 3 j | **Élevé** — Keystore fragile (Samsung Auto Blocker, OEM bugs API 28-31) |
| Migration v3→v4 + UX | 2 j | 2 j | **Élevé** (perte de données possible) |
| **Total** | **10 j** | **7 j** | — |

**Décisions verrouillées** (cf. table en tête de doc). Reste à exécuter :
- Plan de rollout : beta interne → release-v2.0.0 avec backup automatique
  v3 ; communication utilisateur explicite ("après mise à jour, déverrouillez
  votre coffre — la migration est automatique").
- Benchmark Argon2id S9/S24 avant tag final pour fixer définitivement
  `m`/`t`/`p` (cible : < 1 s unlock S9).

---

## Suite des Moyens documentés ailleurs

- **M-6** (`setInvalidatedByBiometricEnrollment`) : nécessite un
  MethodChannel custom qui contourne `biometric_storage` v5. À faire dans
  la même release que H-3 (le bridge Keystore est déjà là).
- **M-4** (`String _password` non wipable) : limite intrinsèque Dart. À
  documenter dans `SECURITY.md` comme "modèle de menace : heap RAM
  inaccessible sauf dump root + FLAG_SECURE déjà actif".
- **M-10** (migration `flutter_secure_storage` v9→v10) : vérifier au démarrage
  qu'aucune clé legacy ne reste. Test d'intégration à ajouter.
- **M-11** (User-Agent `PassTech` exposant l'app) : remplacer par
  `Mozilla/5.0` ou rendre paramétrable via Settings.
