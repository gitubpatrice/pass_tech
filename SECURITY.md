# Politique de sécurité — Pass Tech

> Pass Tech est un gestionnaire de mots de passe. La sécurité est la priorité absolue de ce projet. Tout signalement responsable est traité avec la plus haute priorité.

## Versions supportées

Seule la dernière version publiée sur GitHub Releases est activement maintenue côté sécurité.

| Version       | Supportée  |
| ------------- | ---------- |
| 2.4.x         | ✅          |
| 2.3.x         | ⚠️ legacy (mettre à jour) |
| 2.0.x – 2.2.x | ⚠️ migration uniquement |
| < 2.0.0       | ❌          |

### Historique des correctifs récents

- **v2.4.4** (2026-05-14) — Audit expert post-v2.4.3 (3 agents parallèles
  sécurité / performance / UX) : 22 corrections F2-F12+F15 sécu /
  P1+P3+P5+P6 perf / U2-U11 UX. Objectif : zéro vulnérabilité, zéro faille.
  `flutter analyze` 0 issue, 48+7 tests verts.

  **Sécurité (haute priorité)** :
  - **F2** — `_unlockWithBiometricInternal` : longueur de la clé
    biométrique décodée VALIDÉE avant pose en `_key`. Avant : `_key =
    base64Decode(keyB64)` posé puis check `length != 32` plus loin. Si
    le storage avait été corrompu (downgrade, disk error, attaque ciblée),
    une clé non-32B vivait brièvement en RAM. Désormais : check via une
    variable locale `candidate`, wipe + `deleteBiometricKey` + retour
    `biometricInvalidated` si invalide.
  - **F3** — Refactor anti-RAM-exposure decoy. `_v4Unlock` rendu **pur**
    (retourne `(finalKey, entries, salt, wrappedDek, wrapNonce)` au
    lieu de muter `_entries`/`_isOpen`/`_cachedSalt`). `_unlockInternal`
    réécrit pour itérer les 2 slots (déni plausible anti-timing) sans
    toucher au state global : applique le winner UNE fois après le loop.
    Avant : si l'utilisateur réutilisait le même mot de passe pour
    primary et decoy (config erronée), la 2ᵉ itération exposait
    brièvement les entries decoy en RAM (~10ms) avant écrasement.
  - **F4** — `passwordMatchesPrimary` partage le mutex `_unlockGate`
    avec `unlock()` et `unlockWithBiometric()`. Avant : un setup decoy
    ou heritage déclenché pendant un unlock en cours (deeplinks / Back
    rapide) pouvait corrompre `_key`/`_entries` pendant le snapshot/
    restore du path v3.
  - **F5** — `BreachService._userAgent` fixé constant
    (`Mozilla/5.0 (compatible)`) au lieu d'un pool rotatif de 4 UAs
    tiré au démarrage de l'app via `static final`. Avant : un observateur
    réseau (HIBP logs, MITM, ISP) qui voyait toujours le même UA durant
    une session pouvait corréler une installation Pass Tech avec un
    compte HIBP / IP. Désormais : UA strictement identique entre toutes
    les installations, toutes les sessions, banal comme un crawler générique.
  - **F6** — `_onUnlockFail` : compteur d'échecs `clamp(0, 1000)`.
    Avant : un attaquant qui spam `unlock()` montait le compteur à
    des dizaines de milliers, usure NAND et pollution storage (le
    lockout step reste plafonné à 30 min via la table). Aucun impact
    crypto, hygiène uniquement.
  - **F7** — `_TotpCard` : code TOTP masqué par défaut (`••• •••`),
    bouton oeil pour révéler. Avant : code 6-digits visible en
    permanence dans la card → shoulder-surfing trivial. Aligné sur le
    pattern `_PasswordField` qui exige `show=true`.
  - **F8** — `_HeirPasswordDialog` (saisie passphrase héritier) :
    `TextField(obscureText: true)` brut remplacé par `PasswordTextField`
    (autofillHints=[], enableInteractiveSelection=show,
    keyboardType=visiblePassword, autocorrect=false). Régression
    v2.4.3 U1 qui avait corrigé le master password mais oublié ce
    dialog. L'héritier saisit son passphrase dans un champ désormais
    protégé contre Autofill tiers et long-press copy.
  - **F9** — `.ptbak` v1/v2 legacy : `mac.length != 32` refusé AVANT
    le `compute pbkdf2Worker`. Avant :
    `SecretBytes.constantTimeEq(computed, mac)` retournait `false`
    sur length mismatch (commentaire M-2) mais après avoir consommé
    600 000 itérations PBKDF2 pour rien. Un `.ptbak` v1/v2 forgé
    avec `mac="AAA="` (3 octets) court-circuitait silencieusement.
  - **F10** — `MonotonicClock.nowMs()` sérialisée via un Future cache
    pour éviter les races `read → max → write` non-atomiques quand 2
    callers concurrent (auto-lock timer + unlock + heritage markActive)
    s'entrelaçaient autour des `await _storage.read/write`.
  - **F11** — `_extractTotpSecret` : refus strict des `otpauth://`
    avec scheme ≠ `otpauth` ou host ≠ `totp`. Avant : un QR
    `otpauth://malicious-host/whatever?secret=ABCD&issuer=<huge string>`
    était accepté tant que `secret` était base32-valide.
  - **F12** — `_extractTotpSecret` cap rawValue QR à 2048 octets
    avant `Uri.parse`. Anti-DoS marginal mais borne la surface
    d'attaque du parser sur un QR malicieux 3-4 Ko.
  - **F15** — `PanicService.panic()` reset désormais `pt_fail_count` ET
    `pt_lockout_until` dans flutter_secure_storage. Avant : après
    panic+disguise, un attaquant tombant sur le decoy pouvait déclencher
    un lockout 30 min "anormal" via 5 tentatives ratées — signal indirect
    qu'une situation d'urgence venait d'avoir lieu (le compteur antérieur
    de l'utilisateur légitime persistait). Désormais : état post-panic
    indistinguable d'un boot frais.

  **Performance** :
  - **P1** — `HomeScreen._filtered` mémoïsé via `_cachedFiltered`/
    `_cachedEntriesLength`, invalidé sur mutation (`_filter`, `_sort`,
    `_search`, mutations entries via `_refresh`). Avant : 4 passes
    `where().toList()` + sort recalculées à CHAQUE build sur 500
    entries (4-8 ms × 6 rebuilds/écran = 25-50 ms évités). Sur 1000
    entries : 150 ms.
  - **P3** — `_TotpCardState` cache `(code, validUntilEpoch)` ; le
    `Timer.periodic 1s` continue de driver le countdown mais le calcul
    HMAC-SHA1 ne tourne plus que tous les 30 s. Économie batterie
    réelle sur écran TOTP ouvert (~0.5 ms/s × N visualisations).
  - **P5** — `SetupScreen` : jauge de force scope-isolée dans un
    `ValueListenableBuilder` lié à `_pass1`. Avant : `onChanged: (_)
    => setState(() {})` sur les 2 PasswordTextField rebuilder TOUT
    l'écran à chaque frappe. Gain ~3-5 ms / frappe sur S9.
  - **P6** — `AuditScreen._analyze` single-pass : 1 boucle remplit
    `_weak`/`_duplicates`/`_old`/`_missing2fa` + compteurs
    `_passCount`/`_noteCount`/`_cardCount`/`_with2fa` (utilisés en
    header stats). Avant : 4 passes `where().toList()` + 4 passes
    `where().length` à chaque rebuild (8×500 = 4000 evals).

  **UX / a11y** :
  - **U2** — 4 dialogs destructifs alignés sur le pattern v2.4.3 U10
    (autofocus Cancel + `FilledButton.tonal` rouge avec `cs.errorContainer`
    / `cs.onErrorContainer`) : delete entry, decoy delete, heritage
    disable, screenshot protection off, deleteAll vault (le plus
    critique). Avant : `TextButton` rouge sans autofocus, pas de
    différentiation visuelle suffisante du destructif vs annulation.
  - **U3** — `_Badge` (about_screen) : texte en `cs.onSurface` au
    lieu de `color` thématique. Contraste WCAG AA atteint (~13:1 vs
    ~2.5:1 mesuré sur 5 des 6 badges) — texte rouge sur fond rouge
    clair échouait. L'icône colorée conserve le signal visuel.
  - **U4** — `_scoreIcon()` (audit_screen) : icône daltonien-safe
    (`check_circle` / `thumb_up_alt` / `warning_amber_rounded` /
    `error`) à côté du libellé. Avant : couleur seule signalait la
    qualité du score → confusion deutéranope/protanope. Semantics
    group annonce "Score 85 sur 100, Bon" à TalkBack.
  - **U5** — `HeirViewScreen` : tooltips `IconButton` copy
    (l'héritier est par définition non-familier de l'app, c'est son
    SEUL usage) + `cs.onSurfaceVariant` au lieu de `Colors.grey`
    hardcodé (contraste pauvre dark mode).
  - **U6** — `entry_edit_screen` username / URL : `autocorrect: false`
    + `enableSuggestions: false` + `textCapitalization: none`. Avant :
    Gboard transformait `john.doe` → `John Doe` au premier caractère,
    capitalisait `Https://` au début du champ URL.
  - **U7** — `about_screen` : `Image.asset` icon `cacheWidth: 160`
    + `cacheHeight: 160` (= 2× displayWidth 80dp). Sans ce cap,
    Flutter décodait l'asset à devicePixelRatio (240×240 sur S24),
    soit ~230 Ko RAM par instance. Aligné PDF Tech / RFT / AI Tech.
  - **U8** — `LinearProgressIndicator` audit_screen (HIBP progress)
    et setup_screen (jauge force) : `semanticsLabel` + `semanticsValue`
    "%" pour TalkBack. Avant : aveugle voyait juste la section
    sans aucun feedback de progression sur les ~6 s du batch.
  - **U9** — `HapticFeedback` ajouté : `selectionClick` sur save
    entry (action utilisateur réussie), `mediumImpact` sur lock manuel
    + delete entry (geste protecteur), `heavyImpact` sur panic +
    deleteAll vault (action critique). Avant : seul `clipboard.copy`
    avait `lightImpact`. Pattern aligné AI Tech v0.9.1 U4.
  - **U10** — Onboarding dots : `Semantics(label: 'X / Y')` group
    pour TalkBack. Avant : les dots étaient purement visuels — swipe
    entre pages ne s'annonçait pas pour utilisateurs aveugles.
  - **U11** — `snackBarTheme: SnackBarThemeData(behavior:
    SnackBarBehavior.floating)` ajouté aux `_lightTheme()` et
    `_darkTheme()` globaux. Avant : sites `ScaffoldMessenger.of(
    context).showSnackBar` inline (38 occurrences hors SnackUtils)
    n'avaient pas `behavior:floating` — overlap fréquent avec FAB
    sur petits écrans.

  **Qualité** :
  - +7 tests garde `v2_4_4_guards_test.dart` (F11/F12 QR
    scheme+cap, F9 mac.length pre-check, F5 UA constant).
  - `flutter analyze` 0 issue, 48+7 tests verts.

- **v2.4.3** (2026-05-13) — Audit expert post-v2.4.2 : 24 corrections
  (F1-F6+F10 sécu / U1 U3 U4 U6 U10 U12 U14 UX / P1.1 P2.1 P2.2 perf).

  **Sécurité (haute priorité)** :
  - **F1** — Format `.ptbak` v3 (Argon2id + AES-GCM) remplace v2 (PBKDF2 600k
    + AES-CBC + HMAC). Le `.ptbak` est le seul fichier qui circule
    *hors device* (export pour backup cloud, transfert) — il est donc
    le plus exposé au brute-force GPU offline (~50 M tries/s contre
    PBKDF2 vs ~10/s contre Argon2id même sur RTX 4090). AAD `ptbak:v=3|
    kdf=argon2id|m=...|t=...|p=...|salt=...` anti-downgrade. Lecture
    v1/v2 préservée pour rétro-compat ; écriture v3 uniquement.
    Bornes strictes sur params Argon2 lus (`4096 ≤ m ≤ 1 048 576 KiB`,
    `1 ≤ t ≤ 16`, `1 ≤ p ≤ 4`) — refus immédiat d'un `.ptbak` forgé
    avec m=2 Go destiné à OOM le device au déchiffrement.
  - **F2** — `deleteBiometricKey()` ordre inversé : le flag UI
    (`pt_biometric_enabled` dans flutter_secure_storage) est désormais
    supprimé en PREMIER, le storage Keystore best-effort en second.
    Évite le cas où une suppression partielle laissait le bouton
    biométrique affiché alors qu'aucun storage utilisable n'existait.
  - **F3** — `PhishingDetectorService.kt` : `System.currentTimeMillis()`
    → `SystemClock.elapsedRealtime()` (boot-based monotone). Un user
    rooté pouvait faire reculer la wall-clock entre la création d'un
    snapshot et son lookup, rendant la fenêtre de fraîcheur 15 s
    réutilisable indéfiniment. Aligné sur `MonotonicClock` côté Dart.
  - **F4** — `VaultService.lock()` appelle désormais
    `ClipboardService.cancelAndClear()` (fire-and-forget). Auparavant
    seul `PanicService.panic()` le faisait : un lock manuel depuis
    Settings, un auto-lock par timer, laissait un timer pendant qui
    pouvait retirer un callback sur un context disposé, et la valeur
    copiée restait dans le presse-papier jusqu'à expiration.
  - **F5** — `VaultService.lock()` wipe désormais cryptographiquement
    les bytes du cache méta v4 (`_cachedSalt`, `_cachedWrappedDek`,
    `_cachedWrapNonce`) avant nullification. Ces 3 champs ne sont pas
    secrets *stricto sensu* mais leur concaténation est un fingerprint
    unique du vault, exploitable pour corréler des dumps mémoire
    cross-sessions.
  - **F6** — `_decryptVaultV3(null)` retournait `true` avec 0 entry
    (path mort post-v2.0 mais qui ouvrait le vault SANS authentification
    si un futur appelant passait null). Désormais refus strict.
  - **F10** — `_v4Unlock` : la clé `out` est désormais clonée et
    `finalKey` wipée AVANT le peuplement du cache méta. Évite qu'une
    exception OOM/GC entre `_cachedSalt = ...` et `SecretBytes.wipe`
    laisse à la fois la clé brute et un cache partiel.

  **UX / a11y** :
  - **U1** — `PasswordTextField` : `autofillHints: const <String>[]`
    désactive le service Autofill Android (pas de capture cross-app
    d'un master password) + `enableInteractiveSelection: _show` bloque
    la sélection/copie quand masqué (anti clipboard manager tiers).
  - **U3** — Search IconButton AppBar : tooltip Flutter built-in
    (`closeButtonTooltip` / `searchFieldLabel`).
  - **U4** — PopupMenuButton `more_vert` : `moreButtonTooltip` built-in.
  - **U6** — Spinner Argon2id (setup + unlock) wrap `Semantics(
    liveRegion: true, label: t.setupEncrypting/unlockDecrypting)` —
    TalkBack annonce désormais le statut au début de la dérivation
    (auparavant 1-3 s de silence sur device contraint).
  - **U10** — Dialog "Supprimer entrée" : `TextButton(autofocus: true)`
    sur Annuler (safe default) + `FilledButton.tonal` rouge sur Delete
    avec `cs.errorContainer/onErrorContainer` au lieu de `Colors.red`.
  - **U12** — Empty state home : ajout `FilledButton.tonalIcon` "Ajouter"
    inline en plus du FAB, plus découvrable au premier lancement.
  - **U14** — Search bar : `suffixIcon` croix inline pour vider le
    champ (plus découvrable que l'action AppBar Close).

  **Performance** :
  - **P1.1** — Search bar debouncée 150 ms (avant : setState par char
    relançait sort+filter+toLowerCase × N entries × N champs → 8-12 ms
    par char sur S9 avec 500 entries).
  - **P2.1** — **splits ABI + resourceConfigurations FR/EN** activés
    (auparavant APK universel 71 Mo). Gain estimé arm64 ~25-30 Mo.
  - **P2.2** — `DateFormat` hissé en `static final _dfDMYHm`
    (entry_detail rebuild fréquent à cause du TOTP timer 1 Hz).

  **Tests garde** : `test/ptbak_v3_test.dart` (10 tests : round-trip
  v3, refus wrong passphrase, refus params Argon2 forgés, refus KDF
  algo non-argon2id, refus version > 3, refus salt < 16 octets).
  Total tests : 48/48 verts (38 + 10 nouveaux).

  Aucun changement de format vault `.enc` (v3/v4 lus comme avant).
  Aucun changement de format vault legacy `.bak`. Les `.ptbak` v2
  existants sont toujours lus en import.

- **v2.4.2** (2026-05-13) — Robustesse biométrique après ré-enrôlement
  d'empreinte Android. Avant : si l'utilisateur supprimait puis
  ré-enrôlait son empreinte, le bouton biométrique affichait « Échec
  biométrique » sans expliquer la marche à suivre, et le wrap restait
  en place (la résolution exigeait un toggle off/on manuel dans
  Réglages). Maintenant : détection explicite de l'exception
  `biometric_storage.AuthException` non-cancel, auto-suppression du
  wrap, et nouveau résultat typé `UnlockResult.biometricInvalidated`
  qui pilote un message clair « Empreinte Android modifiée : le
  déverrouillage biométrique a été désactivé par sécurité. Déverrouillez
  avec votre mot de passe principal, puis réactivez la biométrie dans
  Réglages. ». Ajout d'un snack de confirmation explicite à
  l'activation/désactivation de la biométrie dans Réglages (auparavant
  silencieux), avec discrimination annulation utilisateur vs échec
  technique. Aligne le pattern avec Health Tech v1.5.5.
- **v2.4.0** (2026-05-13) — Audit zéro-vuln zéro-faille A1-A20 +
  B1-B24, FLAG_SECURE dynamique avec refcount, MonotonicClock partagé,
  PanicService purge phishing snapshot.

## Signaler une vulnérabilité

**Merci de ne PAS ouvrir d'issue publique sur GitHub** — un gestionnaire de mots de passe demande une divulgation strictement coordonnée.

📧 **Envoyez un email chiffré (si possible) à : contact@files-tech.com**

Indiquez dans le sujet : `[SECURITY] Pass Tech — <description courte>`.

Merci d'inclure :

- Une description claire de la vulnérabilité
- Les étapes pour la reproduire (PoC bienvenue mais non requise)
- L'impact potentiel (compromission du vault ? vol clé ? bypass biométrie ?)
- La version affectée
- Une suggestion de correctif si possible

## Délai de réponse renforcé (gestionnaire de mots de passe)

- Accusé de réception : sous 48 heures
- Évaluation initiale : sous 7 jours
- Correctif : selon la criticité
  - Critique (compromission du vault, fuite clé) → patch sous 7 jours
  - Majeure → patch sous 30 jours
  - Mineure → version suivante

## Divulgation responsable

Merci de ne pas divulguer publiquement la vulnérabilité avant qu'un correctif ne soit publié et qu'un délai raisonnable de mise à jour (90 jours minimum) ait été laissé aux utilisateurs.

## Plateforme minimale supportée

Depuis la v1.13 (audit hardening 2026-05), Pass Tech requiert **Android 7.0
(API 24) ou supérieur** :

- `minSdk = 24` dans `android/app/build.gradle.kts`.
- Signature APK **v2+ uniquement** (`enableV1Signing = false`) — neutralise
  CVE-2017-13156 (Janus) qui permettait d'injecter du DEX malveillant dans
  un APK v1-signé.
- Android 5 et 6 (parc < 0.5 % en 2026) ne sont plus pris en charge.

Les utilisateurs sur Android antérieur peuvent rester sur la dernière v1.12.x
(non supportée côté sécurité) jusqu'à migration matérielle.

## Vérification de l'intégrité d'un APK

Chaque release publiée sur GitHub contient un hash SHA-256 attendu pour l'APK arm64-v8a dans les notes. Avant install, vous pouvez vérifier :

```bash
sha256sum app-arm64-v8a-release.apk
```

Le résultat doit correspondre exactement à la valeur publiée. Sinon, **ne pas installer l'APK**.

## Modèle de menace

### Pack cryptographique v2.0 (vault v4)

Depuis la v2.0.0, Pass Tech utilise un pack cryptographique entièrement
remplacé pour le coffre principal :

- **KDF** : Argon2id (RFC 9106) — m = 19 456 KiB (19 MiB), t = 2, p = 1,
  L = 32 octets. Choix OWASP 2024 pour gestionnaire de mots de passe sur
  mobile. Remplace PBKDF2-HMAC-SHA256 600 000 iter (v3).
- **AEAD** : AES-GCM-256 (NIST SP 800-38D), nonce 96 bits aléatoires,
  tag 128 bits. Remplace AES-256-CBC + HMAC-SHA256 séparé (v3). L'AAD
  GCM lie la version, l'alias KEK et les paramètres KDF (anti-downgrade).
- **Clé liée matériel** : `hwSecret` (32 octets aléatoires) chiffré par
  une **KEK AES/GCM/NoPadding 256 bits** dans l'AndroidKeyStore, alias
  `pt_vault_kek_v1`. Tentative `setIsStrongBoxBacked(true)` à la création,
  fallback silencieux sur TEE software.
- **Dérivation finale** : `finalKey = HKDF-SHA256(salt, pwHash || hwSecret,
  "pt:v4", 32)`. La KEK Keystore ne quitte jamais le TEE / StrongBox.
- **Déni plausible** : 2 alias KEK (`pt_vault_kek_v1` +
  `pt_vault_kek_decoy_v1`) sont créés systématiquement à la 1ʳᵉ install,
  même si le coffre leurre n'est pas configuré — l'inspection du Keystore
  ne révèle rien sur l'usage du décoy. Depuis la **v2.0.2**, un sel dummy
  de 32 octets est généré pour le décoy même sans usage et le timing de
  la vérification du leurre est aligné sur celui du coffre principal —
  une attaque side-channel temporelle ne peut plus distinguer les deux
  chemins.

### Surface protégée

Le modèle de menace cible trois scénarios :

1. **Anti-coercition** — un attaquant force l'utilisateur à déverrouiller
   l'app. Pass Tech répond avec le coffre leurre + mode panique.
2. **Perte / vol de l'appareil** — l'attaquant a un accès physique mais
   pas le master password. Pass Tech répond avec Argon2id + KEK Keystore
   non-extractible + lockout progressif + auto-lock + wipe RAM.
3. **Malware sandbox** — un autre process tente de lire le coffre. Pass
   Tech répond avec sandbox Android, FLAG_SECURE, allowBackup=false,
   clipboard `IS_SENSITIVE`.

### Pass Tech protège contre

- Vol physique de l'appareil (verrouillage écran + biométrique hardware-bound + lockout progressif)
- Lecture du fichier vault sans le mot de passe maître (AES-GCM-256, Argon2id 19 MiB / t=2)
- Attaque hors-ligne sur le vault exfiltré : sans la KEK Keystore (liée à l'appareil), le vault est inutilisable même avec le master password
- Downgrade silencieux de version : l'AAD GCM lie `v=4|alias=…|kdf=argon2id|m=…|t=…|p=…` au tag
- Capture d'écran et aperçu du sélecteur récent (FLAG_SECURE)
- Backup cloud Android (allowBackup=false + dataExtractionRules)
- Brute-force du master password (lockout progressif 5 fails → 30s à 30min)
- Réutilisation après timeout (auto-lock configurable + wipe clé RAM)
- Trafic réseau MITM partiel (HIBP en k-anonymity, network_security_config)

### Pass Tech NE protège PAS contre

- Appareil rooté + Frida actif (un avertissement RASP est affiché mais l'utilisateur peut continuer à ses risques)
- Keylogger système / clavier compromis
- Compromission complète du Keystore Android matériel (extraction TEE / StrongBox)
- Factory reset ou wipe Keystore (Samsung Auto Blocker, restauration usine) :
  la KEK est perdue → coffre **irrécupérable** même avec master password.
  Mitigation : exporter une `.ptbak` AVANT factory reset.
- Attaquant ayant le mot de passe maître ET un accès au device déverrouillé

### Limitations connues

- **Dart `String` non zeroizable** (M-4) : la VM Dart ne permet pas
  d'effacer fiablement une `String` en mémoire. Les master passwords
  sont saisis dans des `TextEditingController` puis convertis le plus
  rapidement possible en `Uint8List` zeroizables. Une fenêtre
  temporelle résiduelle existe (quelques ms à quelques s en cas de pause
  GC). **Cette limitation s'étend à plusieurs chemins** :
  - **Import `.ptbak`** : après déchiffrement, `Uint8List plain` est
    explicitement wipé, mais `utf8.decode(plain)` puis `jsonDecode(...)`
    créent des `String` Dart intermédiaires non-wipables qui contiennent
    les mots de passe jusqu'au GC. Le wipe de `plain` réduit la surface
    sans la fermer.
  - **TOTP `DateTime.now()`** : `TotpService` utilise le wall-clock
    (RFC 6238 oblige le sync horloge avec le serveur). Un attaquant
    root capable de modifier l'horloge système (`adb shell date -s`)
    peut rejouer un code TOTP expiré dans la fenêtre ±30 s tolérée.
    Mitigations partielles : pas de stockage TOTP en clair (le secret
    Base32 est dans le vault chiffré), wall-clock pas utilisable pour
    déverrouiller le vault lui-même (les chemins lockout passent par
    `MonotonicClock`).
  Mitigation globale : auto-lock court + FLAG_SECURE + Keystore
  hardware-backed quand disponible.
- **Wipe Keystore = perte du coffre** : factory reset, restauration usine,
  certaines opérations Samsung Auto Blocker invalident la KEK. Sans la
  KEK, le coffre est mathématiquement irrécupérable même avec le master
  password. Mitigation : exporter régulièrement un `.ptbak` (pack Argon2id
  + AES-GCM portable, indépendant du Keystore).
- **Mode panique non-instantané sur certains OEM** : le camouflage d'icône
  via alias d'activité peut prendre 1–3 s sur Samsung One UI à cause du
  cache du launcher.
- **Biométrie post-réenrôlement empreinte** (M-6) : `biometric_storage`
  v5.0.x n'expose pas `setInvalidatedByBiometricEnrollment(true)` dans
  son API publique. Si un attaquant ayant accès physique au device et
  au PIN Android ajoute sa propre empreinte, il pourrait théoriquement
  déverrouiller la biométrie sans connaître le master password.
  Mitigation v2.5.0 : note UI dans Réglages → désactiver/réactiver la
  biométrie après chaque modification d'empreinte/visage Android. Fix
  définitif (KeystoreBridge custom) en backlog ROADMAP_HARDENING.

### Migration v3 → v4

À la 1ʳᵉ ouverture après mise à jour vers la v2.0.0, Pass Tech détecte
automatiquement un coffre v3 et le convertit en v4 :

1. Le master password est demandé via l'UI normale d'unlock.
2. Le coffre v3 est déchiffré (PBKDF2 + AES-CBC + HMAC) en mémoire.
3. Une copie `pt_vault_v3.enc.bak` est créée à côté du fichier original.
4. Un nouveau salt + hwSecret sont générés ; la KEK Keystore est créée.
5. Le coffre est ré-écrit en format v4 (atomic tmp+rename).
6. La biométrique éventuelle (cache 64 octets v3) est invalidée — il faut
   la réactiver dans Réglages.

Aucun retour en arrière : v3 ne peut plus être réécrit. Les fichiers
`.ptbak` v3 restent compatibles en lecture jusqu'à la v2.1.

## Périmètre

Vulnérabilités acceptées :

- Faiblesse cryptographique (Argon2id, AES-GCM, HKDF, AAD, nonce, IV)
- Bypass de la biométrie hardware-bound
- Bypass du lockout / brute-force facilité
- Fuite du vault chiffré ou de la clé maître hors processus
- Side-channels timing exploitables

Hors périmètre :

- Bugs UX sans impact sécurité
- Vulnérabilités dans `flutter_secure_storage`, `biometric_storage`, `encrypt`, `crypto` déjà reportées en amont
- Attaques nécessitant un appareil rooté/compromis (déjà couvert par RASP)
- Attaques physiques sur l'appareil déverrouillé avec le vault ouvert
