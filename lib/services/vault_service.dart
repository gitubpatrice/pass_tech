import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:biometric_storage/biometric_storage.dart';
import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart' as cg;
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

import '../models/entry.dart';
import 'aead_service.dart';
import 'kdf_service.dart';
import 'keystore_service.dart';

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
  static const _legacyBiometricKeyKey = 'pt_biometric_key'; // pre-v1.6 storage
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
  // v4 (current)          : Argon2id (m=19 MiB, t=2, p=1) → HKDF, AES-GCM-256,
  //                         hwSecret 32B wrapped by an AndroidKeyStore KEK.
  // ignore: unused_field
  static const _currentIterations = 600000; // v3 reference, not used in v4
  static const _legacyIterations =
      100000; // for v1.0.0 vaults without iterations field
  static const _maxIterations =
      2000000; // hard cap to prevent DoS via tampered file
  static const _v3Version = 3;
  static const _currentVersion = 4;
  static const _vaultMagic = 'PTVAULT';

  // Argon2id baseline (decision verrouillée — ROADMAP_HARDENING.md §3).
  static const _argon2M = 19456; // KiB
  static const _argon2T = 2;
  static const _argon2P = 1;

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

  /// True si [password] déverrouille le coffre primary. Utilisé pour vérifier
  /// que le password du leurre diffère bien du password du primary AVANT
  /// la création. Ne touche pas à _key / _entries (ne déverrouille pas
  /// vraiment l'app — le test est isolé puis nettoyé).
  Future<bool> passwordMatchesPrimary(String password) async {
    try {
      final file = await _vaultFileFor(_Slot.primary);
      if (!file.existsSync()) return false;
      final raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      // Snapshot current state so the test doesn't leak into the open vault.
      final savedKey = _key == null ? null : Uint8List.fromList(_key!);
      final savedEntries = List<Entry>.from(_entries);
      final savedOpen = _isOpen;
      final savedSlot = _activeSlot;
      try {
        if (version >= _currentVersion) {
          final fk = await _v4Unlock(
            slot: _Slot.primary,
            password: password,
            raw: raw,
          );
          if (fk == null) return false;
          _zero(fk);
          return true;
        }
        // v3 path — derive PBKDF2 then attempt MAC check.
        final saltB64 = await _storage.read(key: _saltKeyFor(_Slot.primary));
        if (saltB64 == null) return false;
        final salt = base64Decode(saltB64);
        final iter = raw['iterations'] as int? ?? _legacyIterations;
        if (iter < 1 || iter > _maxIterations) return false;
        _key = await _deriveKey(password, salt, iter);
        final ok = _decryptVaultV3(raw);
        return ok;
      } finally {
        _wipeKey();
        _key = savedKey;
        _entries = savedEntries;
        _isOpen = savedOpen;
        _activeSlot = savedSlot;
      }
    } catch (_) {
      return false;
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
    await _createSlot(_Slot.decoy, decoyPassword);
  }

  Future<void> _createSlot(_Slot slot, String password) async {
    // v4 : Argon2id + Keystore-bound KEK + AES-GCM.
    // Decision #4: always create both KEK aliases on fresh setup, even if
    // decoy not configured, to keep the keystore profile constant for all
    // users (preserves plausible deniability).
    await _keystore.ensureBothKeksExist();

    final salt = _randomBytes(32);
    await _storage.write(key: _saltKeyFor(slot), value: base64Encode(salt));

    // Derive pwHash with Argon2id (isolate).
    final pwHash = await KdfService.argon2id(password: password, salt: salt);

    // Generate hwSecret (32 random bytes), wrap with KEK.
    final hwSecret = _randomBytes(32);
    Uint8List? finalKey;
    try {
      final alias = _aliasFor(slot);
      final wrap = await _keystore.wrap(alias, hwSecret);

      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      _key = Uint8List.fromList(finalKey);
      _entries = [];
      _isOpen = true;
      _activeSlot = slot;

      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
      await _onUnlockSuccess();
    } finally {
      _zero(pwHash);
      _zero(hwSecret);
      if (finalKey != null) _zero(finalKey);
    }
  }

  String _aliasFor(_Slot slot) =>
      slot == _Slot.primary ? KeystoreAliases.primary : KeystoreAliases.decoy;

  // ── Unlock ──────────────────────────────────────────────────────────────────

  /// Returns remaining lockout in seconds, or null if not locked out.
  Future<int?> getLockoutRemaining() async {
    final s = await _storage.read(key: _lockoutKey);
    if (s == null) return null;
    final until = int.tryParse(s) ?? 0;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now >= until) return null;
    return ((until - now) / 1000).ceil();
  }

  Future<UnlockResult> unlock(String masterPassword) async {
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

  /// Tente de déverrouiller un slot précis. Retourne success ou wrongPassword.
  ///
  /// v3 path : if `version <= 3`, derive PBKDF2 key, decrypt with v3, then
  /// trigger automatic migration to v4 (atomic — backup `.bak` is created
  /// before the v3 file is overwritten).
  /// v4 path : Argon2id + Keystore unwrap + AES-GCM decrypt.
  Future<UnlockResult> _tryUnlockSlot(_Slot slot, String masterPassword) async {
    try {
      final file = await _vaultFileFor(slot);
      if (!file.existsSync()) return UnlockResult.wrongPassword;

      final raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      if (version >= _currentVersion) {
        // ── v4 path ──
        final fk = await _v4Unlock(
          slot: slot,
          password: masterPassword,
          raw: raw,
        );
        if (fk == null) {
          _wipeKey();
          return UnlockResult.wrongPassword;
        }
        _wipeKey();
        _key = fk;
        _activeSlot = slot;
        await _onUnlockSuccess();
        return UnlockResult.success;
      }

      if (version > _v3Version || version < 1) {
        // Forged version (e.g. 99999) or invalid : refuse.
        return UnlockResult.wrongPassword;
      }

      // ── v3 path : decrypt then migrate ──
      final saltKey = _saltKeyFor(slot);
      final saltB64 = await _storage.read(key: saltKey);
      if (saltB64 == null) return UnlockResult.wrongPassword;
      final salt = base64Decode(saltB64);

      final stored = raw['iterations'] as int?;
      int iterations;
      if (stored == null) {
        iterations = _legacyIterations;
      } else {
        if (stored < 1 || stored > _maxIterations) {
          return UnlockResult.wrongPassword;
        }
        iterations = stored;
      }

      _key = await _deriveKey(masterPassword, salt, iterations);
      final ok = _decryptVaultV3(raw);
      if (!ok) {
        _wipeKey();
        return UnlockResult.wrongPassword;
      }
      // Active slot must be set before any save (migration writes the v4 file
      // into the active slot).
      _activeSlot = slot;
      await _onUnlockSuccess();

      // Migrate v3 → v4. _migrateV3ToV4 wipes the v3 64-byte key and replaces
      // it with the new 32-byte v4 finalKey. Biometric pre-v4 cache is
      // invalidated below — user must re-enrol after first v4 unlock.
      final migrated = await _migrateV3ToV4(
        slot: slot,
        password: masterPassword,
      );
      if (!migrated) {
        // The v3 read succeeded so entries are in memory — but persisting v4
        // failed. Fail closed : lock and ask user to retry.
        _wipeKey();
        _entries = [];
        _isOpen = false;
        _activeSlot = null;
        return UnlockResult.wrongPassword;
      }

      // Old v3 biometric cache is now useless (different key shape).
      if (slot == _Slot.primary) {
        await deleteBiometricKey();
      }
      return UnlockResult.success;
    } catch (_) {
      _wipeKey();
      return UnlockResult.wrongPassword;
    }
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
    await _storage.delete(key: _legacyBiometricKeyKey);
  }

  Future<void> deleteBiometricKey() async {
    try {
      final store = await _bioStorage();
      await store.delete();
    } catch (_) {}
    await _storage.delete(key: _biometricFlagKey);
    await _storage.delete(key: _legacyBiometricKeyKey);
    _bioFile = null;
  }

  Future<UnlockResult> unlockWithBiometric() async {
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    try {
      final store = await _bioStorage();
      final keyB64 = await store.read();
      if (keyB64 == null || keyB64.isEmpty) return UnlockResult.wrongPassword;
      _key = base64Decode(keyB64);

      // La biométrique est volontairement liée au coffre PRIMARY uniquement.
      // Permettre la bio sur le coffre leurre briserait le déni plausible.
      final file = await _vaultFileFor(_Slot.primary);
      if (!file.existsSync()) {
        _wipeKey();
        return UnlockResult.wrongPassword;
      }
      final raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      bool ok;
      if (version >= _currentVersion) {
        // v4 : the cached _key IS the 32-byte finalKey, no Argon2id / KEK
        // unwrap needed. The GCM tag binds the AAD so wrong-key fails closed.
        if (_key == null || _key!.length != 32) {
          ok = false;
        } else {
          ok = await _decryptVaultV4(raw, _key!);
        }
      } else {
        ok = _decryptVaultV3(raw);
      }

      if (ok) {
        _activeSlot = _Slot.primary;
        await _onUnlockSuccess();
        // If the stored key was a v3 64-byte cache, force re-enrol after the
        // upcoming v4 migration trip — but we only reach here if the vault is
        // already v4 (v3 unlock-with-bio still works as a safety net for the
        // first unlock after upgrade; the bio cache will be wiped on
        // _migrateV3ToV4 path triggered by password unlock).
        return UnlockResult.success;
      }
      _wipeKey();
      return UnlockResult.wrongPassword;
    } catch (_) {
      _wipeKey();
      return UnlockResult.wrongPassword;
    }
  }

  void lock() {
    _wipeKey();
    _entries = [];
    _isOpen = false;
    _activeSlot = null;
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

  Future<void> changeMasterPassword(String newPassword) async {
    // v4 : Argon2id + re-wrap fresh hwSecret. Le slot opposé n'est pas affecté.
    final slot = _activeSlot ?? _Slot.primary;
    final salt = _randomBytes(32);
    await _storage.write(key: _saltKeyFor(slot), value: base64Encode(salt));

    final hwSecret = _randomBytes(32);
    Uint8List? pwHash;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: newPassword, salt: salt);
      final alias = _aliasFor(slot);
      // KEK reste la même (alias inchangé) — on re-wrap juste un hwSecret neuf.
      final wrap = await _keystore.wrap(alias, hwSecret);
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      _wipeKey();
      _key = Uint8List.fromList(finalKey);

      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
    } finally {
      if (pwHash != null) _zero(pwHash);
      _zero(hwSecret);
      if (finalKey != null) _zero(finalKey);
    }

    // La biométrique est liée au PRIMARY uniquement. Ne la supprimer QUE
    // si on change le password du primary — sinon un changement de password
    // sur le decoy révélerait l'existence du decoy à un attaquant qui
    // remarquerait que la bio fonctionne plus après son intervention.
    if (slot == _Slot.primary) {
      await deleteBiometricKey();
    }
  }

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

  // ── Internal ────────────────────────────────────────────────────────────────

  Future<File> _vaultFileFor(_Slot slot) async {
    final dir = await getApplicationDocumentsDirectory();
    final name = slot == _Slot.primary ? 'pt_vault.enc' : 'pt_vault_decoy.enc';
    return File('${dir.path}/$name');
  }

  String _saltKeyFor(_Slot slot) =>
      slot == _Slot.primary ? _saltKey : _decoySaltKey;

  /// Legacy v1/v2/v3 decryption (PBKDF2 + AES-CBC + HMAC-SHA256).
  /// Kept read-only for the migration window. Will be removed in v2.1.
  ///
  /// Requires `_key` to be the 64-byte v3 derived key. Returns false on any
  /// error (corrupted file, wrong password, MAC mismatch, version > v3).
  bool _decryptVaultV3(Map<String, dynamic>? raw) {
    try {
      if (raw == null) {
        _entries = [];
        _isOpen = true;
        return true;
      }

      final ivBytes = base64Decode(raw['iv'] as String);
      final macBytes = base64Decode(raw['mac'] as String);
      final cipherBytes = base64Decode(raw['data'] as String);
      final version = raw['version'] as int? ?? 1;
      // _decryptVaultV3 ne traite que v1/v2/v3. v4+ doit passer par
      // _decryptVaultV4. La borne stricte protège contre les fichiers forgés
      // (ex. version 99999) qui pourraient déclencher des branches non
      // auditées au fil de futurs refactors.
      if (version < 1 || version > _v3Version) return false;
      final iterations = raw['iterations'] as int? ?? _legacyIterations;
      final saltB64 = raw['salt'] as String? ?? '';

      // Verify HMAC — constant-time comparison
      // v3+ : HMAC over (version || iterations || salt || IV || ciphertext)
      // v1/v2 : HMAC over (IV || ciphertext) only
      // M-3 : sublist() crée une COPIE des octets de la clé. Sans wipe explicite
      // de ces copies, _wipeKey() (qui n'agit que sur _key) laisse des bouts
      // de la clé maître en RAM jusqu'au GC. On zéroïse activement encKeyBytes
      // et macKey en finally.
      final macKey = _key!.sublist(32);
      final encKeyBytes = _key!.sublist(0, 32);
      try {
        final List<int> macInput;
        if (version >= 3) {
          final aad = utf8.encode(
            'pt:v=$version|iter=$iterations|salt=$saltB64',
          );
          macInput = [...aad, ...ivBytes, ...cipherBytes];
        } else {
          macInput = [...ivBytes, ...cipherBytes];
        }
        final computed = Hmac(sha256, macKey).convert(macInput).bytes;
        if (!_constEq(computed, macBytes)) return false;

        final encKey = enc.Key(encKeyBytes);
        final iv = enc.IV(Uint8List.fromList(ivBytes));
        final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
        final plain = encrypter.decrypt(
          enc.Encrypted(Uint8List.fromList(cipherBytes)),
          iv: iv,
        );

        final list = jsonDecode(plain) as List;
        _entries = list
            .map((e) => Entry.fromJson(e as Map<String, dynamic>))
            .toList();
        _isOpen = true;
        return true;
      } finally {
        _zero(macKey);
        _zero(encKeyBytes);
      }
    } catch (_) {
      return false;
    }
  }

  /// v4 save : preserves existing kdf.salt + kek.wrappedDek / wrapNonce from
  /// the on-disk file. Re-encrypts entries with the current 32-byte finalKey
  /// (`_key`) using AES-GCM, fresh 96-bit nonce, AAD bound to v4 metadata.
  ///
  /// Used by all CRUD operations after the vault is unlocked. The first-time
  /// write (creation / migration) routes through [_saveVaultV4] which takes
  /// the salt + wrapped material as explicit arguments.
  ///
  Future<void> _saveVault() async {
    if (_key == null) return;
    final slot = _activeSlot ?? _Slot.primary;

    // Read kdf.salt + kek block from current file. If missing (should never
    // happen post-creation), bail out silently — refusing to write a vault
    // we can't reload is safer than producing an inconsistent file.
    final file = await _vaultFileFor(slot);
    if (!file.existsSync()) return;
    Map<String, dynamic> prev;
    try {
      prev = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    final kdf = prev['kdf'];
    final kek = prev['kek'];
    if (kdf is! Map || kek is! Map) return;
    final saltB64 = kdf['salt'] as String?;
    final wrappedB64 = kek['wrappedDek'] as String?;
    final nonceB64 = kek['wrapNonce'] as String?;
    if (saltB64 == null || wrappedB64 == null || nonceB64 == null) return;

    await _saveVaultV4(
      slot: slot,
      salt: base64Decode(saltB64),
      wrappedDek: base64Decode(wrappedB64),
      wrapNonce: base64Decode(nonceB64),
    );
  }

  // ── v4 helpers ──────────────────────────────────────────────────────────────

  /// Build the AAD string bound to a v4 vault. The exact bytes are part of
  /// the GCM tag — encrypt and decrypt MUST agree byte-for-byte.
  Uint8List _aadV4(String alias) => Uint8List.fromList(
    utf8.encode(
      'pt:v=4|alias=$alias|kdf=argon2id|m=$_argon2M|t=$_argon2T|p=$_argon2P',
    ),
  );

  /// HKDF-SHA256(salt, ikm = pwHash || hwSecret, info = "pt:v4", L = 32).
  /// The `cryptography` package's Hkdf primitive runs synchronously fast on
  /// 64 bytes of IKM — no isolate needed.
  Future<Uint8List> _hkdfFinalKey({
    required Uint8List salt,
    required Uint8List pwHash,
    required Uint8List hwSecret,
  }) async {
    final ikm = Uint8List(pwHash.length + hwSecret.length);
    ikm.setRange(0, pwHash.length, pwHash);
    ikm.setRange(pwHash.length, ikm.length, hwSecret);
    try {
      final hkdf = cg.Hkdf(hmac: cg.Hmac.sha256(), outputLength: 32);
      final out = await hkdf.deriveKey(
        secretKey: cg.SecretKey(ikm),
        nonce: salt,
        info: utf8.encode('pt:v4'),
      );
      final bytes = await out.extractBytes();
      return Uint8List.fromList(bytes);
    } finally {
      _zero(ikm);
    }
  }

  /// Encrypt and atomically write the vault in v4 format.
  ///
  /// Caller must have populated `_key` with the 32-byte finalKey beforehand.
  /// `salt`, `wrappedDek`, `wrapNonce` are stable across CRUD writes —
  /// generated once at vault creation / migration and reused.
  Future<void> _saveVaultV4({
    required _Slot slot,
    required Uint8List salt,
    required Uint8List wrappedDek,
    required Uint8List wrapNonce,
  }) async {
    if (_key == null || _key!.length != 32) {
      throw StateError('v4 save requires a 32-byte finalKey');
    }
    final alias = _aliasFor(slot);
    final aad = _aadV4(alias);

    final plainText = utf8.encode(
      jsonEncode(_entries.map((e) => e.toJson()).toList()),
    );
    final ptBytes = Uint8List.fromList(plainText);

    final aead = await AeadService.encryptGcm(
      key: _key!,
      plaintext: ptBytes,
      aad: aad,
    );
    // GCM "data" field = ciphertext || tag, mirrors Android Cipher.doFinal.
    final cipherAndTag = aead.cipherAndTag;

    final out = <String, dynamic>{
      'magic': _vaultMagic,
      'version': _currentVersion,
      'kdf': <String, dynamic>{
        'algo': 'argon2id',
        'm': _argon2M,
        't': _argon2T,
        'p': _argon2P,
        'salt': base64Encode(salt),
      },
      'kek': <String, dynamic>{
        'algo': 'AES-GCM-256',
        'alias': alias,
        'wrappedDek': base64Encode(wrappedDek),
        'wrapNonce': base64Encode(wrapNonce),
      },
      'cipher': <String, dynamic>{
        'algo': 'AES-GCM-256',
        'nonce': base64Encode(aead.nonce),
        'data': base64Encode(cipherAndTag),
        'aad': utf8.decode(aad),
      },
    };

    final target = await _vaultFileFor(slot);
    final tmp = File('${target.path}.tmp');
    tmp.writeAsStringSync(jsonEncode(out), flush: true);
    if (target.existsSync()) target.deleteSync();
    tmp.renameSync(target.path);
  }

  /// Decrypt a v4 vault map. On success, populates `_entries` and returns
  /// `true`. On any failure (wrong key, bad tag, corrupt JSON), returns
  /// `false` and leaves `_entries` untouched. The `finalKey` argument is
  /// the 32-byte HKDF output; caller is responsible for wiping it after.
  Future<bool> _decryptVaultV4(
    Map<String, dynamic> raw,
    Uint8List finalKey,
  ) async {
    try {
      if (raw['magic'] != _vaultMagic) return false;
      if (raw['version'] != _currentVersion) return false;
      final kek = raw['kek'];
      final cipher = raw['cipher'];
      if (kek is! Map || cipher is! Map) return false;
      final alias = kek['alias'] as String?;
      if (alias != KeystoreAliases.primary && alias != KeystoreAliases.decoy) {
        return false;
      }
      final nonce = base64Decode(cipher['nonce'] as String);
      final dataBlob = base64Decode(cipher['data'] as String);
      final split = AeadService.splitCipherAndTag(dataBlob);

      final aad = _aadV4(alias!);
      final pt = await AeadService.decryptGcm(
        key: finalKey,
        nonce: nonce,
        ciphertext: split.ciphertext,
        tag: split.tag,
        aad: aad,
      );
      if (pt == null) return false;
      final list = jsonDecode(utf8.decode(pt)) as List;
      _entries = list
          .map((e) => Entry.fromJson(e as Map<String, dynamic>))
          .toList();
      _isOpen = true;
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Try to unlock a v4 vault for `slot`. Returns the recovered finalKey on
  /// success, or `null` on failure. `_entries` is populated as a side-effect
  /// of [_decryptVaultV4]. Caller wipes the returned key after use.
  Future<Uint8List?> _v4Unlock({
    required _Slot slot,
    required String password,
    required Map<String, dynamic> raw,
  }) async {
    final kdf = raw['kdf'];
    final kek = raw['kek'];
    if (kdf is! Map || kek is! Map) return null;
    final salt = base64Decode(kdf['salt'] as String);
    final wrappedDek = base64Decode(kek['wrappedDek'] as String);
    final wrapNonce = base64Decode(kek['wrapNonce'] as String);
    final alias = kek['alias'] as String;

    Uint8List? pwHash;
    Uint8List? hwSecret;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: password, salt: salt);
      try {
        hwSecret = await _keystore.unwrap(alias, wrappedDek, wrapNonce);
      } catch (_) {
        return null;
      }
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );
      final ok = await _decryptVaultV4(raw, finalKey);
      if (!ok) {
        _zero(finalKey);
        return null;
      }
      // Caller takes ownership of the buffer.
      final out = Uint8List.fromList(finalKey);
      _zero(finalKey);
      return out;
    } finally {
      if (pwHash != null) _zero(pwHash);
      if (hwSecret != null) _zero(hwSecret);
    }
  }

  /// Migrate a v3 vault (already loaded as `raw`, password verified by the
  /// caller via successful v3 decrypt) to v4 format.
  ///
  ///  1. Backs up the v3 file as `pt_vault_v3.enc.bak` (best-effort).
  ///  2. Generates a new 32-byte salt + 32-byte hwSecret.
  ///  3. Ensures both KEK aliases exist in the keystore (decision #4).
  ///  4. Wraps hwSecret with the slot KEK.
  ///  5. Recomputes finalKey and writes the vault in v4 format.
  ///
  /// On any failure, the v3 file is preserved (we never delete it before the
  /// v4 write succeeds — the atomic write `tmp+rename` only overwrites once
  /// the new bytes are durable).
  ///
  /// Wipes pwHash, hwSecret, finalKey before return. Replaces `_key` with
  /// the new v4 finalKey so the caller can keep operating.
  Future<bool> _migrateV3ToV4({
    required _Slot slot,
    required String password,
  }) async {
    // Best-effort backup of v3 ciphertext.
    try {
      final src = await _vaultFileFor(slot);
      if (src.existsSync()) {
        final bak = File('${src.path}_v3.enc.bak');
        // Always overwrite an older bak if a previous migration attempt
        // failed mid-flight: the source is the authoritative v3 file.
        src.copySync(bak.path);
      }
    } catch (_) {
      /* non-fatal */
    }

    await _keystore.ensureBothKeksExist();

    final newSalt = _randomBytes(32);
    final hwSecret = _randomBytes(32);
    Uint8List? pwHash;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: password, salt: newSalt);
      final alias = _aliasFor(slot);
      final wrap = await _keystore.wrap(alias, hwSecret);
      finalKey = await _hkdfFinalKey(
        salt: newSalt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      // Update _key to the new v4 finalKey before writing.
      _wipeKey();
      _key = Uint8List.fromList(finalKey);

      // Persist the new salt under the existing _saltKeyFor() entry. The
      // legacy v3 PBKDF2 salt is overwritten — v3 is no longer readable.
      await _storage.write(
        key: _saltKeyFor(slot),
        value: base64Encode(newSalt),
      );

      await _saveVaultV4(
        slot: slot,
        salt: newSalt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
      return true;
    } catch (_) {
      return false;
    } finally {
      if (pwHash != null) _zero(pwHash);
      _zero(hwSecret);
      if (finalKey != null) _zero(finalKey);
    }
  }

  // ── Brute-force protection ──────────────────────────────────────────────────

  Future<void> _onUnlockFail() async {
    final s = await _storage.read(key: _failCountKey);
    final count = (int.tryParse(s ?? '0') ?? 0) + 1;
    await _storage.write(key: _failCountKey, value: count.toString());

    if (count >= _failThreshold) {
      final stepIdx = (count - _failThreshold).clamp(
        0,
        _lockoutSteps.length - 1,
      );
      final lockSec = _lockoutSteps[stepIdx];
      final until = DateTime.now().millisecondsSinceEpoch + lockSec * 1000;
      await _storage.write(key: _lockoutKey, value: until.toString());
    }
  }

  Future<void> _onUnlockSuccess() async {
    await _storage.delete(key: _failCountKey);
    await _storage.delete(key: _lockoutKey);
  }

  // ── Memory hygiene ──────────────────────────────────────────────────────────

  void _wipeKey() {
    if (_key != null) {
      for (int i = 0; i < _key!.length; i++) {
        _key![i] = 0;
      }
      _key = null;
    }
  }

  /// Zéroïse une copie temporaire de matériel cryptographique (sublist du
  /// _key, par exemple). Ne pas confondre avec _wipeKey qui agit sur l'instance
  /// principale _key. M-3 : appelé pour les copies créées via Uint8List.sublist
  /// dans _decryptVault et _saveVault.
  static void _zero(Uint8List buf) {
    for (int i = 0; i < buf.length; i++) {
      buf[i] = 0;
    }
  }

  // ── Crypto helpers ──────────────────────────────────────────────────────────

  // M-2 : ce _constEq retourne tôt si les longueurs diffèrent. C'est
  // inoffensif ici car on l'utilise UNIQUEMENT pour des HMAC-SHA256 (32 octets
  // fixes). Ne PAS le réutiliser pour comparer des secrets de longueur
  // variable — un attaquant pourrait observer un timing distinct selon la
  // longueur.
  static bool _constEq(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  static Future<Uint8List> _deriveKey(
    String password,
    List<int> salt,
    int iterations,
  ) async =>
      compute(pbkdf2Worker, [utf8.encode(password), salt, iterations, 64]);

  static Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List.generate(n, (_) => rng.nextInt(256)));
  }
}
