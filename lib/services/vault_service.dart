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

enum UnlockResult { success, wrongPassword, lockedOut }

/// Identifie quel slot du vault est en cours d'utilisation.
/// - primary : coffre historique (file pt_vault.enc, salt pt_salt)
/// - decoy   : coffre leurre (file pt_vault_decoy.enc, salt pt_salt_decoy)
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
  static const _lockoutKey = 'pt_lockout_until';

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

  bool get isOpen => _isOpen;
  List<Entry> get entries => List.unmodifiable(_entries);

  Future<bool> get vaultExists async =>
      (await _vaultFileFor(_Slot.primary)).existsSync();

  Future<bool> get hasDecoyVault async =>
      (await _vaultFileFor(_Slot.decoy)).existsSync();

  /// True si le slot actuellement déverrouillé est le coffre leurre.
  /// L'app peut s'en servir pour adapter discrètement l'UX, mais ne doit
  /// JAMAIS l'afficher visuellement (ce serait briser le déni plausible).
  bool get isDecoyActive => _activeSlot == _Slot.decoy;

  // ── Setup ───────────────────────────────────────────────────────────────────

  Future<void> createVault(String masterPassword) async {
    await _createSlot(_Slot.primary, masterPassword);
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
    await _createSlot(_Slot.decoy, decoyPassword);
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
    // Déni plausible : on tente PBKDF2 sur CHAQUE slot, même si un slot
    // précédent a matché. Sinon le timing révèle l'existence du decoy
    // (1× PBKDF2 = matché primary, 2× PBKDF2 = matché decoy ou échec
    // avec decoy présent, etc.). Toujours 2× PBKDF2 → pas de side-channel.
    _Slot? matchedSlot;
    Uint8List? winnerKey;
    List<Entry>? winnerEntries;
    for (final slot in _Slot.values) {
      final r = await _tryUnlockSlot(slot, masterPassword);
      if (r == UnlockResult.success && matchedSlot == null) {
        // Capture l'état du slot gagnant AVANT que la prochaine itération
        // l'écrase. On clone la clé pour ne pas la perdre quand _wipeKey
        // sera appelé pendant l'itération suivante.
        matchedSlot = slot;
        winnerKey = Uint8List.fromList(_key!);
        winnerEntries = List<Entry>.from(_entries);
      }
    }
    if (matchedSlot != null && winnerKey != null) {
      _wipeKey();
      _key = winnerKey;
      _entries = winnerEntries!;
      _isOpen = true;
      _activeSlot = matchedSlot;
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
    try {
      final store = await _bioStorage();
      await store.delete();
    } catch (_) {}
    await _storage.delete(key: _biometricFlagKey);
    _bioFile = null;
  }

  void lock() {
    _wipeKey();
    _entries = [];
    _isOpen = false;
    _activeSlot = null;
    // F17 v2.3.7 — reset la référence BiometricStorageFile pour qu'un
    // unlock biométrique post-panic re-acquière le storage proprement
    // (sinon référence dangling vers un fichier potentiellement supprimé).
    _bioFile = null;
    // P0-1 v2.4.0 — purge le snapshot anti-phishing (domaine bancaire
    // courant) côté natif. Sans ça, le domaine reste en RAM ~15 s post-lock,
    // récupérable par instrumentation. Fire-and-forget : le lock() reste
    // synchrone côté caller (auto-lock timer, lifecycle), best-effort.
    unawaited(AntiPhishingService.clearSnapshot());
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

  Future<void> deleteVault() async {
    lock();
    // Supprime les 2 slots : reset complet de l'app.
    for (final slot in _Slot.values) {
      final file = await _vaultFileFor(slot);
      if (file.existsSync()) file.deleteSync();
      // v3 backup, créé pendant la migration v3→v4 — on l'efface aussi.
      final bak = File('${file.path}_v3.enc.bak');
      if (bak.existsSync()) bak.deleteSync();
      await _storage.delete(key: _saltKeyFor(slot));
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
  }

  /// Supprime UNIQUEMENT le coffre leurre, sans toucher au primary.
  /// Utilisé depuis Settings si l'utilisateur veut désactiver le décoy.
  Future<void> deleteDecoyVault() async {
    final file = await _vaultFileFor(_Slot.decoy);
    if (file.existsSync()) file.deleteSync();
    await _storage.delete(key: _saltKeyFor(_Slot.decoy));
  }

  // ── Internal helpers (paths) ────────────────────────────────────────────────

  Future<File> _vaultFileFor(_Slot slot) async {
    final dir = await getApplicationDocumentsDirectory();
    final name = slot == _Slot.primary ? 'pt_vault.enc' : 'pt_vault_decoy.enc';
    return File('${dir.path}/$name');
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
