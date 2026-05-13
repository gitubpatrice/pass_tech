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

- **Dart `String` non zeroizable** : la VM Dart ne permet pas d'effacer
  fiablement une `String` en mémoire. Les master passwords sont saisis
  dans des `TextEditingController` puis convertis le plus rapidement
  possible en `Uint8List` zeroizables. Une fenêtre temporelle résiduelle
  existe (quelques ms à quelques s en cas de pause GC). Mitigation :
  auto-lock court + FLAG_SECURE.
- **Wipe Keystore = perte du coffre** : factory reset, restauration usine,
  certaines opérations Samsung Auto Blocker invalident la KEK. Sans la
  KEK, le coffre est mathématiquement irrécupérable même avec le master
  password. Mitigation : exporter régulièrement un `.ptbak` (pack Argon2id
  + AES-GCM portable, indépendant du Keystore).
- **Mode panique non-instantané sur certains OEM** : le camouflage d'icône
  via alias d'activité peut prendre 1–3 s sur Samsung One UI à cause du
  cache du launcher.

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
