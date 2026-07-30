// VaultService — orchestrateur du coffre-fort Pass Tech.
//
// Cette library a été splittée (v2.1.2) pour rendre la crypto critique plus
// auditable :
//
//   vault_service.dart        ← orchestration + état + API publique
//   vault_crypto.dart         ← AAD v4, HKDF, decrypt v3/v4 (legacy muteur)
//   vault_storage.dart        ← saveVault / saveVaultV4 (atomic write)
//   vault_unlock.dart         ← passwordMatchesPrimary, _tryUnlockSlot,
//                                _v4Unlock, unlockWithBiometric
//   vault_brute_force.dart    ← compteur d'échecs + lockout exponentiel
//   vault_migration.dart      ← migration v3 → v4
//
// Tous les fichiers ci-dessus sont des `part of 'vault_service.dart';`. Ils
// partagent la même library et accèdent donc librement aux membres privés
// (_key, _entries, _activeSlot, _keystore, _bioStorage, _wipeKey, etc.).
//
// L'API publique (createVault, unlock, addEntry, …) reste exposée par cette
// classe. Tout le reste de l'app n'a JAMAIS besoin d'importer les parts —
// l'unique import autorisé reste `package:pass_tech/services/vault_service.dart`.

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:biometric_storage/biometric_storage.dart';
import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart' as cg;
import 'package:encrypt/encrypt.dart' as enc;
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

import '../models/entry.dart';
import 'aead_service.dart';
import 'anti_phishing_service.dart';
import 'clipboard_service.dart';
import 'kdf_service.dart';
import 'keystore_service.dart';
import 'monotonic_clock.dart';

part 'vault_brute_force.dart';
part 'vault_crypto.dart';
part 'vault_migration.dart';
part 'vault_setup.dart';
part 'vault_storage.dart';
part 'vault_unlock.dart';

// Top-level function required by compute()
Uint8List pbkdf2Worker(List<dynamic> args) {
  final password = args[0] as List<int>;
  final salt = args[1] as List<int>;
  final iterations = args[2] as int;
  final keyLen = args[3] as int;

  final hmacGen = Hmac(sha256, password);
  const hLen = 32;
  final blocks = (keyLen / hLen).ceil();
  final dk = BytesBuilder();

  try {
    for (int i = 1; i <= blocks; i++) {
      final saltI = Uint8List(salt.length + 4);
      saltI.setRange(0, salt.length, salt);
      saltI[salt.length] = (i >> 24) & 0xFF;
      saltI[salt.length + 1] = (i >> 16) & 0xFF;
      saltI[salt.length + 2] = (i >> 8) & 0xFF;
      saltI[salt.length + 3] = i & 0xFF;

      var u = Uint8List.fromList(hmacGen.convert(saltI).bytes);
      final t = Uint8List.fromList(u);

      for (int j = 1; j < iterations; j++) {
        u = Uint8List.fromList(hmacGen.convert(u).bytes);
        for (int k = 0; k < t.length; k++) {
          t[k] ^= u[k];
        }
      }
      dk.add(t);
    }
    return dk.toBytes().sublist(0, keyLen);
  } finally {
    // F9 v2.3.7 — wipe le password reçu côté worker isolate (la copie
    // transférée par compute() restait en RAM jusqu'à GC sinon).
    // A8 v2.3.8 — pattern aligné `SecretBytes.wipe` (handle Uint8List
    // non-modifiable retourné par certains FFI). Pour les List<int>
    // autres que Uint8List, on tente un overwrite via setRange best-effort.
    if (password is Uint8List) {
      SecretBytes.wipe(password);
    } else {
      try {
        for (var i = 0; i < password.length; i++) {
          password[i] = 0;
        }
      } catch (_) {
        // List<int> immutable (rare via compute) : best-effort, GC nettoiera.
      }
    }
    // Wipe aussi le buffer dérivé intermédiaire (dk.toBytes() retourne une
    // copie ; le sublist final est seul retenu). On n'a pas accès au
    // buffer du BytesBuilder, mais return + immutable view minimise.
  }
}

enum UnlockResult {
  success,
  wrongPassword,
  lockedOut,

  /// (v2.4.2) Tentative biométrique sur une clé Keystore invalidée par
  /// Android — typiquement après un ré-enrôlement d'empreinte. Le wrap
  /// biométrique a été auto-nettoyé par le caller ; l'utilisateur doit
  /// passer par le master password puis réactiver la biométrie depuis
  /// Réglages. À distinguer de [wrongPassword] (annulation utilisateur
  /// silencieuse) pour donner un message clair au lieu d'un échec opaque.
  biometricInvalidated,
}

/// Identifie quel slot du vault est en cours d'utilisation.
/// - primary : coffre historique (file `pt_vault_a.enc`, salt pt_salt)
/// - decoy   : coffre leurre (file `pt_vault_b.enc`, salt pt_salt_decoy) —
///   TOUJOURS présent depuis H1 (leurre factice si pas de vrai decoy).
/// Noms de fichiers neutres (ex-`pt_vault.enc` / `pt_vault_decoy.enc`) migrés
/// par `ensureVaultLayout`. Voir aussi le flag `pt_decoy_configured`.
///
/// Le code ne fait JAMAIS de différence fonctionnelle entre primary et decoy.
/// Les deux ont les mêmes capacités (CRUD entries, biométrique optionnelle…).
/// Le slot actif est juste celui dont le master password a déchiffré.
/// L'attaquant qui voit le device ne peut pas savoir lequel est "réel".
enum _Slot { primary, decoy }

class VaultService {
  static final VaultService _instance = VaultService._();
  factory VaultService() => _instance;
  VaultService._();

  /// Keystore backend used for KEK operations (H-3). Defaults to the real
  /// AndroidKeyStore channel; tests inject `InMemoryKeystoreBackend` via
  /// [setKeystoreForTesting] before exercising migration / unlock paths.
  KeystoreService _keystore = const KeystoreService(
    backend: ChannelKeystoreBackend(),
  );

  @visibleForTesting
  void setKeystoreForTesting(KeystoreService ks) {
    _keystore = ks;
  }

  // flutter_secure_storage v10+ : EncryptedSharedPreferences (Jetpack Security)
  // est déprécié. La lib v10 utilise désormais ses propres ciphers en
  // interne. Migration automatique des données existantes au 1er accès.
  static const _storage = FlutterSecureStorage();

  // Secure storage keys
  // Le slot "primary" est le coffre historique (existait avant le decoy).
  // Le slot "decoy" est le coffre leurre optionnel — déni plausible.
  // L'unlock teste le password contre les deux slots successivement.
  static const _saltKey = 'pt_salt'; // = primary (rétro-compat)
  static const _decoySaltKey = 'pt_salt_decoy';
  static const _biometricStorageName = 'pt_biometric_key_v2';
  static const _biometricFlagKey = 'pt_biometric_enabled';
  static const _failCountKey = 'pt_fail_count';

  /// Ancien format du verrouillage : horodatage ABSOLU sur horloge murale.
  /// Conservé en lecture seule pour les installations verrouillées au moment
  /// de la mise à jour — plus jamais écrit (cf. SEC F5/F17).
  static const _lockoutKey = 'pt_lockout_until';

  /// SEC F5/F17 v2.5.2 — nouveau format : durée restante en millisecondes,
  /// plus l'ancre `SystemClock.elapsedRealtime()` à laquelle elle a été
  /// écrite. Insensible aux manipulations de l'horloge murale.
  static const _lockoutRemainingKey = 'pt_lockout_remaining_ms';
  static const _lockoutAnchorKey = 'pt_lockout_anchor_ms';

  // v2.5.x (H1) — flag « un VRAI coffre leurre est configuré ». Stocké chiffré
  // (EncryptedSharedPreferences, clé TEE non-extractible → illisible au
  // forensic) et TOUJOURS présent (défaut 'false') pour un profil de storage
  // constant. Remplace l'ancien signal « le fichier decoy existe » — lequel,
  // combiné au nom `_decoy`, révélait au repos l'existence du 2ᵉ coffre. Voir
  // `ensureVaultLayout` (leurre factice toujours présent + noms neutres).
  static const _decoyConfiguredKey = 'pt_decoy_configured';

  /// Slot du vault actuellement ouvert (pour les écritures ultérieures).
  /// null si aucun vault ouvert.
  _Slot? _activeSlot;

  BiometricStorageFile? _bioFile;
  Future<BiometricStorageFile> _bioStorage() async {
    // M-6 : `biometric_storage` v5.0.x utilise déjà côté natif un Cipher AES
    // KeyGenParameterSpec avec setUserAuthenticationRequired(true). En
    // revanche, le paramètre setInvalidatedByBiometricEnrollment(true) n'est
    // PAS exposé par l'API publique du package (il faudrait un fork ou un
    // MethodChannel maison pour le forcer). Conséquence : si l'utilisateur
    // ajoute/retire une empreinte, la clé Keystore liée ne sera PAS invalidée
    // automatiquement → l'attaquant qui ajoute son empreinte avant
    // déverrouillage device pourrait théoriquement déverrouiller la bio.
    // Mitigations en place :
    //  - L'ajout d'empreinte requiert le PIN/pattern device (Android impose
    //    une auth strong avant enrollment).
    //  - L'écran d'unlock conserve toujours l'option master password.
    //  - À documenter dans SECURITY.md / Réglages : « si vous ajoutez une
    //    empreinte, désactivez puis réactivez le déverrouillage biométrique
    //    pour régénérer la clé liée ».
    // TODO M-6 (suite) : envisager un MethodChannel custom AndroidKeyStore
    // pour forcer setInvalidatedByBiometricEnrollment(true) — voir
    // ROADMAP_HARDENING.md.
    return _bioFile ??= await BiometricStorage().getStorage(
      _biometricStorageName,
      options: StorageFileInitOptions(
        authenticationRequired: true,
        authenticationValidityDurationSeconds: -1,
        androidBiometricOnly: true,
      ),
      promptInfo: const PromptInfo(
        androidPromptInfo: AndroidPromptInfo(
          title: 'Pass Tech',
          subtitle: 'Déverrouiller votre coffre-fort',
          negativeButton: 'Annuler',
          confirmationRequired: false,
        ),
      ),
    );
  }

  // Crypto parameters
  // v3 (legacy, read-only): PBKDF2-HMAC-SHA256 600 000 iter, AES-CBC + HMAC.
  //                         (Référence historique — plus utilisé : tous les
  //                         vaults sont migrés en v4 au premier unlock.)
  // v4 (current)          : Argon2id (m=19 MiB, t=2, p=1) → HKDF, AES-GCM-256,
  //                         hwSecret 32B wrapped by an AndroidKeyStore KEK.
  static const _legacyIterations =
      100000; // for v1.0.0 vaults without iterations field
  static const _maxIterations =
      2000000; // hard cap to prevent DoS via tampered file
  static const _v3Version = 3;
  static const _currentVersion = 4;
  static const _vaultMagic = 'PTVAULT';

  // Argon2id baseline — source unique : KdfParams.owaspMobile2024.
  // (decision verrouillée — ROADMAP_HARDENING.md §3).
  static final _argon2M = KdfParams.owaspMobile2024.memoryKiB;
  static final _argon2T = KdfParams.owaspMobile2024.iterations;
  static final _argon2P = KdfParams.owaspMobile2024.parallelism;

  // Brute-force protection: progressive lockout after 5 fails
  static const _failThreshold = 5;
  static const _lockoutSteps = [30, 60, 300, 900, 1800]; // seconds

  // Vault key cache.
  //  - v3 path: 64 bytes (0-31 enc key, 32-63 HMAC key) — kept for read-only.
  //  - v4 path: 32 bytes (finalKey = HKDF(pwHash || hwSecret, info=pt:v4)).
  // Length disambiguates the two; never mixed within one open session.
  Uint8List? _key;
  List<Entry> _entries = [];
  bool _isOpen = false;

  // QW2 v2.4.0 — cache des méta v4 (salt + KEK wrap) post-unlock. Sans ce
  // cache, chaque CRUD relisait le fichier complet pour récupérer ces 3
  // champs stables → ~30-50 ms d'I/O+parse JSON par save sur S9. Wipé au
  // lock() avec le reste.
  Uint8List? _cachedSalt;
  Uint8List? _cachedWrappedDek;
  Uint8List? _cachedWrapNonce;

  bool get isOpen => _isOpen;
  List<Entry> get entries => List.unmodifiable(_entries);

  Future<bool> get vaultExists async =>
      (await _vaultFileFor(_Slot.primary)).existsSync();

  /// v2.5.x (H1) — « un VRAI coffre leurre est-il configuré ? ». Lit le flag
  /// chiffré (secure storage), PAS l'existence du fichier `_b` : depuis H1 ce
  /// fichier existe TOUJOURS (leurre factice si pas de vrai decoy), donc son
  /// existence ne dit plus rien. Le flag est illisible au repos (clé TEE).
  Future<bool> get hasDecoyVault async =>
      (await _storage.read(key: _decoyConfiguredKey)) == 'true';

  /// True si le slot actuellement déverrouillé est le coffre leurre.
  /// L'app peut s'en servir pour adapter discrètement l'UX, mais ne doit
  /// JAMAIS l'afficher visuellement (ce serait briser le déni plausible).
  bool get isDecoyActive => _activeSlot == _Slot.decoy;

  // ── Setup ───────────────────────────────────────────────────────────────────

  Future<void> createVault(String masterPassword) async {
    await _createSlot(_Slot.primary, masterPassword);
    // v2.5.x (H1) — leurre factice créé DÈS la création du coffre (profil de
    // fichiers constant : tout install a `_a` + `_b`). Flag 'false' = pas de
    // VRAI decoy. `setupDecoyVault` écrasera ce factice si l'utilisateur en
    // configure un. Best-effort : un échec ici ne doit pas bloquer la création
    // du coffre principal (déjà écrit + déverrouillé).
    try {
      await _createDummyDecoy();
      await _storage.write(key: _decoyConfiguredKey, value: 'false');
    } catch (_) {
      /* le leurre sera re-tenté au prochain boot par ensureVaultLayout */
    }
  }

  /// Crée le coffre LEURRE (decoy). Appelé depuis Settings quand l'utilisateur
  /// configure son coffre anti-coercition. Le coffre leurre est totalement
  /// distinct du primary (autre salt, autre fichier, autres entrées).
  ///
  /// **IMPORTANT** : le decoyPassword DOIT être différent du master password
  /// du primary. Sinon les 2 slots déchiffreraient avec le même mot de passe
  /// et l'unlock retournerait toujours le même (le primary qui est testé
  /// avant). L'appelant doit valider en amont que le 2 mots de passe diffèrent.
  Future<void> setupDecoyVault(String decoyPassword) async {
    // `_createSlot(decoy)` écrase le fichier `_b` (leurre factice) par un VRAI
    // coffre leurre (contenu + mot de passe choisis par l'utilisateur).
    await _createSlot(_Slot.decoy, decoyPassword);
    // v2.5.x (H1) — marque qu'un VRAI decoy existe désormais (pour l'UI).
    await _storage.write(key: _decoyConfiguredKey, value: 'true');
  }

  String _aliasFor(_Slot slot) =>
      slot == _Slot.primary ? KeystoreAliases.primary : KeystoreAliases.decoy;

  // ── Unlock ──────────────────────────────────────────────────────────────────

  /// F5 v2.3.7 — mutex re-entrant : `unlock()` est lourd (2× PBKDF2 +
  /// 2× Argon2id pour le déni plausible). Un double-tap UI rapide pouvait
  /// lancer 2 unlocks en parallèle, doublant la conso CPU/RAM (OOM
  /// possible sur Redmi 9C 3GB) et créant des races sur `_key`/`_entries`
  /// pendant l'itération du déni plausible.
  Completer<void>? _unlockGate;

  Future<UnlockResult> unlock(String masterPassword) async {
    // F5 — refus immédiat si un unlock concurrent tourne déjà.
    if (_unlockGate != null) {
      return UnlockResult.wrongPassword;
    }
    final gate = _unlockGate = Completer<void>();
    try {
      return await _unlockInternal(masterPassword);
    } finally {
      gate.complete();
      _unlockGate = null;
    }
  }

  Future<UnlockResult> _unlockInternal(String masterPassword) async {
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    // Déni plausible : on tente UNE PASSE Argon2id sur CHAQUE slot, même si
    // un slot précédent a matché. Sinon le timing révèle l'existence du
    // decoy (1× Argon2id = matché primary, 2× = matché decoy ou échec avec
    // decoy présent). Toujours 2× Argon2id → pas de side-channel.
    //
    // F3 v2.4.4 — Refactor anti-RAM-exposure decoy. Auparavant `_tryUnlockSlot`
    // mute `_entries`/`_isOpen` dès qu'un slot déchiffrait, donc dans le cas
    // (rare) où l'utilisateur avait choisi le MÊME password pour primary et
    // decoy, la 2ᵉ itération exposait brièvement les entries decoy en RAM
    // avant l'écrasement par le winner. Désormais : `_v4Unlock` est pur,
    // on capture le winner (key + entries + cache méta) localement, et on
    // applique l'état AU FINAL pour le winner uniquement.
    _Slot? matchedSlot;
    Uint8List? winnerKey;
    List<Entry>? winnerEntries;
    Uint8List? winnerSalt;
    Uint8List? winnerWrappedDek;
    Uint8List? winnerWrapNonce;
    bool winnerIsV3 = false;

    for (final slot in _Slot.values) {
      final file = await _vaultFileFor(slot);
      if (!await file.exists()) {
        // Anti-timing : consomme ~1 Argon2id même si le slot n'existe pas.
        // Password constant (pt_dummy_noop_v2) pour éviter une dépendance
        // linéaire du coût Argon2id avec la longueur du masterPassword
        // (timing oracle marginal sinon).
        final dummySalt = SecretBytes.randomBytes(32);
        final dummyOut = await KdfService.argon2id(
          password: 'pt_dummy_noop_v2',
          salt: dummySalt,
        );
        SecretBytes.wipe(dummyOut);
        continue;
      }
      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      if (version >= _currentVersion) {
        // ── Path v4 pur (refactor F3 v2.4.4) ────────────────────────────
        final r = await _v4Unlock(
          slot: slot,
          password: masterPassword,
          raw: raw,
        );
        if (r == null) continue;
        if (matchedSlot == null) {
          matchedSlot = slot;
          winnerKey = r.finalKey;
          winnerEntries = r.entries;
          winnerSalt = r.salt;
          winnerWrappedDek = r.wrappedDek;
          winnerWrapNonce = r.wrapNonce;
        } else {
          // 2ᵉ slot déchiffre aussi (user a réutilisé le même password —
          // erreur de config). Wipe immédiatement TOUS les buffers du
          // perdant : sa clé v4, son cache méta, et ses entries (liste de
          // String laissée à la merci du GC, mais on déréférence vite).
          SecretBytes.wipe(r.finalKey);
          SecretBytes.wipe(r.salt);
          SecretBytes.wipe(r.wrappedDek);
          SecretBytes.wipe(r.wrapNonce);
          // r.entries sera GC après cette itération (référence locale).
        }
      } else {
        // ── Path v3 legacy (rare, vault non-migré depuis v2.0.0) ───────
        // Si on a déjà un winner v4, on skip la migration v3 du perdant —
        // on ne migre que pour le winner (cas v3 winner ci-dessous).
        // Anti-timing : consomme ~1 Argon2id dummy pour ne pas révéler
        // qu'on a déjà un winner.
        if (matchedSlot != null) {
          final dummySalt = SecretBytes.randomBytes(32);
          final dummyOut = await KdfService.argon2id(
            password: 'pt_dummy_noop_v2',
            salt: dummySalt,
          );
          SecretBytes.wipe(dummyOut);
          continue;
        }
        final r = await _tryUnlockSlot(slot, masterPassword);
        if (r == UnlockResult.success) {
          // Le path v3 mute déjà `_key`/`_entries`/`_isOpen`/`_activeSlot`
          // et déclenche la migration v3→v4 inline. On capture juste un
          // marqueur pour ne pas réécrire l'état au final.
          matchedSlot = slot;
          winnerKey = Uint8List.fromList(_key!);
          winnerEntries = List<Entry>.from(_entries);
          winnerIsV3 = true;
        }
      }
    }

    if (matchedSlot != null && winnerKey != null) {
      if (!winnerIsV3) {
        // F3 v2.4.4 — Application des side effects UNIQUEMENT pour le
        // winner v4. Aucune mutation n'a eu lieu pendant le loop pour ce
        // path : pas de fenêtre d'exposition RAM des entries decoy.
        _wipeKey();
        _key = winnerKey;
        _entries = winnerEntries!;
        _isOpen = true;
        _activeSlot = matchedSlot;
        if (_cachedSalt != null) SecretBytes.wipe(_cachedSalt!);
        if (_cachedWrappedDek != null) SecretBytes.wipe(_cachedWrappedDek!);
        if (_cachedWrapNonce != null) SecretBytes.wipe(_cachedWrapNonce!);
        _cachedSalt = winnerSalt;
        _cachedWrappedDek = winnerWrappedDek;
        _cachedWrapNonce = winnerWrapNonce;
        await _onUnlockSuccess();
      }
      return UnlockResult.success;
    }
    _wipeKey();
    await _onUnlockFail();
    return UnlockResult.wrongPassword;
  }

  /// True if a biometric-bound vault key has been registered. We check a
  /// non-secret flag in flutter_secure_storage so we don't need to trigger
  /// a biometric prompt just to know whether the feature is available.
  Future<bool> get hasBiometricKey async =>
      (await _storage.read(key: _biometricFlagKey)) == '1';

  Future<void> saveBiometricKey() async {
    if (_key == null) return;
    // SÉCURITÉ : la biométrique est verrouillée au coffre PRIMARY.
    // Si l'utilisateur ouvre le decoy puis tente d'activer la bio, on
    // refuse — sinon la clé du decoy serait stockée dans biometric_storage
    // et un attaquant qui aurait l'app pourrait déverrouiller le decoy
    // sans connaître son password (avec juste l'empreinte). Pire encore,
    // cela trahirait l'existence du decoy à un attaquant attentif.
    if (_activeSlot != _Slot.primary) {
      throw StateError(
        'La biométrique n\'est disponible que sur le coffre principal',
      );
    }
    final store = await _bioStorage();
    await store.write(base64Encode(_key!));
    await _storage.write(key: _biometricFlagKey, value: '1');
  }

  Future<void> deleteBiometricKey() async {
    // F2 v2.4.3 — Ordre inversé : on supprime D'ABORD le flag UI (source de
    // vérité pour `hasBiometricKey` → bouton biométrie affiché ou non).
    // Si la suppression du storage Keystore échoue ensuite (cas rare,
    // typiquement clé déjà invalidée par l'OS), l'UI reflète quand même
    // l'état "biométrie désactivée" et invite l'utilisateur à réactiver
    // proprement. Avant : le flag pouvait survivre à un delete partiel, et
    // le bouton bio s'affichait alors qu'aucun storage utilisable n'existait.
    await _storage.delete(key: _biometricFlagKey);
    try {
      final store = await _bioStorage();
      await store.delete();
    } catch (_) {}
    _bioFile = null;
  }

  void lock() {
    _wipeKey();
    _entries = [];
    _isOpen = false;
    _activeSlot = null;
    // F5 v2.4.3 — wipe cryptographique des bytes (et pas seulement
    // déréférencement) du cache méta. Salt et wrap ne sont pas secrets stricto
    // sensu mais leur concaténation est un fingerprint unique du vault,
    // utilisable pour corréler des dumps mémoire across sessions.
    if (_cachedSalt != null) SecretBytes.wipe(_cachedSalt!);
    if (_cachedWrappedDek != null) SecretBytes.wipe(_cachedWrappedDek!);
    if (_cachedWrapNonce != null) SecretBytes.wipe(_cachedWrapNonce!);
    _cachedSalt = null;
    _cachedWrappedDek = null;
    _cachedWrapNonce = null;
    // F17 v2.3.7 — reset la référence BiometricStorageFile pour qu'un
    // unlock biométrique post-panic re-acquière le storage proprement
    // (sinon référence dangling vers un fichier potentiellement supprimé).
    _bioFile = null;
    // P0-1 v2.4.0 — purge le snapshot anti-phishing (domaine bancaire
    // courant) côté natif. Sans ça, le domaine reste en RAM ~15 s post-lock,
    // récupérable par instrumentation. Fire-and-forget : le lock() reste
    // synchrone côté caller (auto-lock timer, lifecycle), best-effort.
    unawaited(AntiPhishingService.clearSnapshot());
    // F4 v2.4.3 — purge du clipboard ET annulation du timer auto-clear.
    // Avant : `lock()` (auto-lock timer Settings, lock manuel) laissait un
    // timer pendant qui pouvait re-tirer un callback sur context disposé,
    // et la valeur copiée restait dans le presse-papier jusqu'à expiration.
    // Seul `PanicService.panic()` faisait ce nettoyage — incohérent.
    unawaited(ClipboardService.cancelAndClear());
  }

  // ── CRUD ────────────────────────────────────────────────────────────────────

  Future<void> addEntry(Entry e) async {
    _entries.add(e);
    await _saveVault();
  }

  Future<void> updateEntry(Entry e) async {
    final i = _entries.indexWhere((x) => x.id == e.id);
    if (i >= 0) {
      _entries[i] = e;
      await _saveVault();
    }
  }

  Future<void> deleteEntry(String id) async {
    _entries.removeWhere((e) => e.id == id);
    await _saveVault();
  }

  // ── Settings ────────────────────────────────────────────────────────────────

  String exportJson() => const JsonEncoder.withIndent(
    '  ',
  ).convert(_entries.map((e) => e.toJson()).toList());

  /// SEC F6 v2.5.2 — premier barreau de l'échelle de rembourrage : 64 Kio.
  ///
  /// Volontairement GÉNÉREUX. Le déni plausible exige que les deux
  /// emplacements aient la MÊME taille ; le moyen le plus robuste d'y parvenir
  /// est que tous les coffres réalistes tiennent sur le même barreau. 64 Kio
  /// de JSON représentent plusieurs centaines d'entrées : en pratique un
  /// coffre réel, un coffre leurre réel et un leurre factice sont tous les
  /// trois à 64 Kio, donc rigoureusement indistinguables par la taille.
  /// Le surcoût de stockage est négligeable et le coût AES-GCM sur 64 Kio est
  /// sous la milliseconde.
  static const int _paddingBaseRungBytes = 65536;

  /// Barreau de l'échelle couvrant [bytes]. Progression ×4 : 64 Kio, 256 Kio,
  /// 1 Mio, 4 Mio… Les barreaux sont rares et très espacés, pour que franchir
  /// l'un d'eux reste un événement exceptionnel.
  static int _ladderRungFor(int bytes) {
    var rung = _paddingBaseRungBytes;
    while (rung < bytes) {
      rung *= 4;
    }
    return rung;
  }

  /// Rembourre [plain] par des espaces jusqu'au barreau couvrant à la fois sa
  /// propre longueur et [minPlainBytes] — la longueur de clair de l'AUTRE
  /// emplacement, pour que les deux fichiers coïncident.
  ///
  /// Le remplissage utilise l'espace (0x20) et non des octets nuls :
  /// `jsonDecode` ignore les espaces de fin, donc le clair rembourré se relit
  /// avec le décodeur EXISTANT. Aucun bump de version de format, aucune
  /// migration, et les coffres déjà écrits sans rembourrage continuent de
  /// s'ouvrir — vérifié empiriquement avant implémentation.
  static Uint8List _padToLadder(Uint8List plain, {int minPlainBytes = 0}) {
    final need = plain.length > minPlainBytes ? plain.length : minPlainBytes;
    final target = _ladderRungFor(need);
    final out = Uint8List(target)..fillRange(0, target, 0x20);
    out.setRange(0, plain.length, plain);
    return out;
  }

  /// Longueur du CLAIR de l'emplacement opposé à [slot], sans posséder sa clé.
  ///
  /// AES-GCM préserve la longueur et `cipher.data` vaut `ciphertext || tag`,
  /// donc `longueurClair = base64Decode(data).length - tagBytes`. C'est ce qui
  /// rend l'appariement possible depuis une session leurre, qui ne détient pas
  /// la clé du principal — et c'est aussi, par construction, exactement
  /// l'information dont disposait l'examinateur pour distinguer les deux
  /// fichiers. On s'en sert ici pour la neutraliser.
  ///
  /// Retourne 0 (aucune contrainte) si le fichier est absent, illisible, ou au
  /// format v3 hérité qui n'a pas de champ `cipher`.
  Future<int> _otherSlotPlainLength(_Slot slot) async {
    final other = slot == _Slot.primary ? _Slot.decoy : _Slot.primary;
    try {
      final f = await _vaultFileFor(other);
      if (!f.existsSync()) return 0;
      final raw = jsonDecode(await f.readAsString());
      if (raw is! Map<String, dynamic>) return 0;
      final cipher = raw['cipher'];
      if (cipher is! Map) return 0;
      final data = cipher['data'];
      if (data is! String) return 0;
      final len = base64Decode(data).length - AeadService.tagBytes;
      return len > 0 ? len : 0;
    } catch (_) {
      return 0;
    }
  }

  /// Entrées de test pour le rembourrage, privé à la bibliothèque. Le
  /// rembourrage conditionne le déni plausible : il doit être couvert par des
  /// tests, pas seulement par relecture.
  @visibleForTesting
  static Uint8List padToLadderForTest(
    Uint8List plain, {
    int minPlainBytes = 0,
  }) => _padToLadder(plain, minPlainBytes: minPlainBytes);

  @visibleForTesting
  static int get paddingBaseRungForTest => _paddingBaseRungBytes;

  /// Écrase le contenu d'un fichier par des octets aléatoires avant l'unlink,
  /// pour qu'une récupération des blocs sous-jacents ne rende pas le clair.
  /// Best-effort : sur un système de fichiers à copie sur écriture (F2FS,
  /// couche flash) l'écrasement ne garantit pas la destruction physique.
  static void _shredSync(File file) {
    try {
      if (!file.existsSync()) return;
      final len = file.lengthSync();
      if (len > 0) {
        final rnd = Random.secure();
        file.writeAsBytesSync(
          Uint8List.fromList(List<int>.generate(len, (_) => rnd.nextInt(256))),
          flush: true,
        );
      }
      file.deleteSync();
    } catch (_) {
      // Best-effort : on tente quand même l'unlink si l'écrasement a échoué.
      try {
        if (file.existsSync()) file.deleteSync();
      } catch (_) {}
    }
  }

  /// Levée par [deleteVault] quand l'appel vient d'une session leurre.
  /// L'appelant doit présenter ce refus à l'utilisateur sans révéler qu'un
  /// coffre principal existe — le message d'erreur ne doit RIEN dire de plus
  /// que « opération indisponible ».
  static const decoySessionDeleteRefused = 'pt_delete_refused_decoy_session';

  Future<void> deleteVault() async {
    // SEC F12 v2.5.2 — Avant : `deleteVault` ne consultait NI `_activeSlot` NI
    // aucune réauthentification, et son unique appelant l'exposait derrière un
    // simple dialogue de confirmation, sans garde `isDecoyActive`. Une session
    // ouverte avec le mot de passe LEURRE pouvait donc anéantir le coffre
    // principal : `_keystore.deleteAll()` détruit les DEUX KEK liées au TEE,
    // rendant même une copie antérieure de `pt_vault_a.enc` définitivement
    // indéchiffrable. C'est l'inverse exact de l'objet du leurre — la victime
    // livre le mot de passe leurre en comptant sur le fait que le vrai coffre
    // reste sûr ET caché.
    if (_activeSlot != _Slot.primary) {
      throw StateError(decoySessionDeleteRefused);
    }
    lock();
    final dir = await getApplicationDocumentsDirectory();
    // SEC F1 v2.5.2 — Avant : liste de noms CODÉE EN DUR, qui oubliait
    // `pt_heir.enc` (l'instantané Héritage). Après « Tout supprimer », une
    // copie déchiffrable de CHAQUE entrée survivait donc dans le stockage
    // privé — et contrairement au coffre principal, cet instantané est dérivé
    // du seul mot de passe héritier SANS liaison hwSecret/TEE : il est
    // attaquable hors ligne depuis une simple copie de fichier. Le dialogue
    // promettait pourtant une suppression définitive.
    // Désormais : on ÉNUMÈRE les artefacts `pt_*` du répertoire documents,
    // pour que tout artefact futur soit couvert par défaut au lieu d'attendre
    // qu'on pense à l'ajouter ici.
    try {
      for (final ent in dir.listSync(followLinks: false)) {
        if (ent is! File) continue;
        final name = ent.uri.pathSegments.last;
        if (name.startsWith('pt_')) _shredSync(ent);
      }
    } catch (_) {
      /* Répertoire illisible : on retombe sur la liste explicite ci-dessous. */
    }
    // Filet de sécurité si l'énumération a échoué (et couvre les `.bak` v3).
    for (final name in const [
      'pt_vault_a.enc',
      'pt_vault_b.enc',
      'pt_vault.enc',
      'pt_vault_decoy.enc',
      'pt_heir.enc',
    ]) {
      _shredSync(File('${dir.path}/$name'));
      _shredSync(File('${dir.path}/${name}_v3.enc.bak'));
    }
    for (final slot in _Slot.values) {
      await _storage.delete(key: _saltKeyFor(slot));
    }
    // v2.5.x (H1) — efface le flag decoy (repart à zéro).
    await _storage.delete(key: _decoyConfiguredKey);
    // SEC F1 v2.5.2 — état Héritage : sans ça, `pt_heir_enabled` restait à '1'
    // et le bouton « Accès héritier » réapparaissait sur l'écran de
    // déverrouillage après le délai de grâce, servant l'ancien coffre.
    for (final k in const [
      'pt_heir_salt',
      'pt_heir_enabled',
      'pt_heir_threshold_days',
      'pt_heir_grace_start_ts',
      'pt_last_active_ts',
    ]) {
      await _storage.delete(key: k);
    }
    await deleteBiometricKey();
    // v4 : détruit aussi les 2 KEK keystore (decision #4).
    try {
      await _keystore.deleteAll();
    } catch (_) {
      /* Keystore inaccessible : best-effort */
    }
    await _storage.delete(key: _failCountKey);
    await _storage.delete(key: _lockoutKey);
    // SEC F5/F17 v2.5.2 — le nouveau format doit être purgé ici aussi, sinon
    // un verrouillage survivait à la suppression complète du coffre.
    await _storage.delete(key: _lockoutRemainingKey);
    await _storage.delete(key: _lockoutAnchorKey);
  }

  /// Désactive le VRAI coffre leurre sans toucher au primary. Utilisé depuis
  /// Settings.
  ///
  /// v2.5.x (H1) — ne SUPPRIME PLUS le fichier `_b` (le profil de fichiers doit
  /// rester constant : sinon la disparition du 2ᵉ fichier prouverait au repos
  /// qu'un decoy réel a existé puis été retiré). On l'ÉCRASE par un leurre
  /// factice non-déverrouillable et on remet le flag à 'false'. Après ça, un
  /// attaquant forensic voit toujours 2 fichiers indistinguables et ne peut
  /// pas prouver qu'un decoy réel a existé.
  Future<void> deleteDecoyVault() async {
    await _createDummyDecoy();
    await _storage.write(key: _decoyConfiguredKey, value: 'false');
  }

  // ── Internal helpers (paths) ────────────────────────────────────────────────

  Future<File> _vaultFileFor(_Slot slot) async {
    final dir = await getApplicationDocumentsDirectory();
    // v2.5.x (H1) — noms neutres indistinguables. Avant : `pt_vault.enc` /
    // `pt_vault_decoy.enc` — le suffixe `_decoy` désignait au forensic quel
    // fichier était le vrai coffre (déni plausible cassé au repos). `_a`
    // (ex-primary) / `_b` (ex-decoy) sont indistinguables ; combinés au leurre
    // factice toujours présent (`ensureVaultLayout`), un attaquant ne peut plus
    // prouver l'existence d'un second coffre réel à partir des fichiers.
    final newFile = File(
      '${dir.path}/${slot == _Slot.primary ? 'pt_vault_a.enc' : 'pt_vault_b.enc'}',
    );
    // FALLBACK rétro-compat — filet de sécurité « sans rien casser » : si la
    // migration de noms (`ensureVaultLayout`) n'a pas encore tourné OU a échoué
    // (IO/Keystore), l'ancien fichier peut être le SEUL présent. On retombe
    // dessus tant que le nouveau n'existe pas → on ne perd JAMAIS l'accès au
    // coffre (et donc jamais de fausse détection « pas de coffre » qui
    // conduirait à un écrasement au setup). La migration renommera au prochain
    // boot réussi.
    if (!newFile.existsSync()) {
      final oldFile = File(
        '${dir.path}/${slot == _Slot.primary ? 'pt_vault.enc' : 'pt_vault_decoy.enc'}',
      );
      if (oldFile.existsSync()) return oldFile;
    }
    return newFile;
  }

  String _saltKeyFor(_Slot slot) =>
      slot == _Slot.primary ? _saltKey : _decoySaltKey;

  // ── Memory hygiene ──────────────────────────────────────────────────────────

  void _wipeKey() {
    if (_key != null) {
      for (int i = 0; i < _key!.length; i++) {
        _key![i] = 0;
      }
      _key = null;
    }
  }

  // ── Crypto helpers (statics — re-utilisés par les parts) ────────────────────
  //
  // v2.2.0 : les shims `_zero / _constEq / _randomBytes` ont été supprimés.
  // Les parts utilisent désormais `SecretBytes.*` directement (cf. v2.1.1 où
  // l'helper a été centralisé dans `files_tech_core`).
  //
  // M-2 (note de sécurité conservée) : `SecretBytes.constantTimeEq` retourne
  // tôt si les longueurs diffèrent. C'est inoffensif tant qu'on l'utilise sur
  // des HMAC/AEAD tags de taille fixe (cas de tous les callsites de la lib).
  // Ne PAS l'utiliser pour comparer des secrets de longueur variable.

  static Future<Uint8List> _deriveKey(
    String password,
    List<int> salt,
    int iterations,
  ) async =>
      compute(pbkdf2Worker, [utf8.encode(password), salt, iterations, 64]);
}
