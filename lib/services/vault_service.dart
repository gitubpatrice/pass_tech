import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:biometric_storage/biometric_storage.dart';
import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

import '../models/entry.dart';

// Top-level function required by compute()
Uint8List pbkdf2Worker(List<dynamic> args) {
  final password   = args[0] as List<int>;
  final salt       = args[1] as List<int>;
  final iterations = args[2] as int;
  final keyLen     = args[3] as int;

  final hmacGen = Hmac(sha256, password);
  const hLen   = 32;
  final blocks = (keyLen / hLen).ceil();
  final dk     = BytesBuilder();

  for (int i = 1; i <= blocks; i++) {
    final saltI = Uint8List(salt.length + 4);
    saltI.setRange(0, salt.length, salt);
    saltI[salt.length]     = (i >> 24) & 0xFF;
    saltI[salt.length + 1] = (i >> 16) & 0xFF;
    saltI[salt.length + 2] = (i >> 8)  & 0xFF;
    saltI[salt.length + 3] =  i        & 0xFF;

    var u = Uint8List.fromList(hmacGen.convert(saltI).bytes);
    final t = Uint8List.fromList(u);

    for (int j = 1; j < iterations; j++) {
      u = Uint8List.fromList(hmacGen.convert(u).bytes);
      for (int k = 0; k < t.length; k++) { t[k] ^= u[k]; }
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

  // flutter_secure_storage v10+ : EncryptedSharedPreferences (Jetpack Security)
  // est déprécié. La lib v10 utilise désormais ses propres ciphers en
  // interne. Migration automatique des données existantes au 1er accès.
  static const _storage = FlutterSecureStorage();

  // Secure storage keys
  // Le slot "primary" est le coffre historique (existait avant le decoy).
  // Le slot "decoy" est le coffre leurre optionnel — déni plausible.
  // L'unlock teste le password contre les deux slots successivement.
  static const _saltKey               = 'pt_salt';        // = primary (rétro-compat)
  static const _decoySaltKey          = 'pt_salt_decoy';
  static const _legacyBiometricKeyKey = 'pt_biometric_key'; // pre-v1.6 storage
  static const _biometricStorageName  = 'pt_biometric_key_v2';
  static const _biometricFlagKey      = 'pt_biometric_enabled';
  static const _failCountKey          = 'pt_fail_count';
  static const _lockoutKey            = 'pt_lockout_until';

  /// Slot du vault actuellement ouvert (pour les écritures ultérieures).
  /// null si aucun vault ouvert.
  _Slot? _activeSlot;

  BiometricStorageFile? _bioFile;
  Future<BiometricStorageFile> _bioStorage() async {
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

  // Crypto parameters (OWASP 2023 PBKDF2-SHA256 ≥ 600 000)
  static const _currentIterations = 600000;
  static const _legacyIterations  = 100000; // for v1.0.0 vaults without iterations field
  static const _maxIterations     = 2000000; // hard cap to prevent DoS via tampered file
  static const _currentVersion    = 3;

  // Brute-force protection: progressive lockout after 5 fails
  static const _failThreshold = 5;
  static const _lockoutSteps  = [30, 60, 300, 900, 1800]; // seconds

  // 64-byte derived key: bytes 0-31 = AES-256 enc key, 32-63 = HMAC-SHA256 key
  Uint8List? _key;
  List<Entry> _entries = [];
  bool _isOpen = false;

  bool get isOpen => _isOpen;
  List<Entry> get entries => List.unmodifiable(_entries);

  Future<bool> get vaultExists async => (await _vaultFileFor(_Slot.primary)).existsSync();

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
      final saltB64 = await _storage.read(key: _saltKeyFor(_Slot.primary));
      if (saltB64 == null) return false;
      final salt = base64Decode(saltB64);
      final file = await _vaultFileFor(_Slot.primary);
      if (!file.existsSync()) return false;
      final raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final iter = raw['iterations'] as int? ?? _legacyIterations;
      if (iter < 1 || iter > _maxIterations) return false;
      // Sauvegarde l'état courant. CRITIQUE : on CLONE _key pour éviter que
      // _wipeKey() ne zéroïse aussi notre référence sauvegardée (les bytes
      // du buffer sont partagés avec _key actuel).
      final savedKey = _key == null ? null : Uint8List.fromList(_key!);
      final savedEntries = List<Entry>.from(_entries);
      final savedOpen = _isOpen;
      final savedSlot = _activeSlot;
      try {
        _key = await _deriveKey(password, salt, iter);
        final ok = _decryptVault(raw);
        return ok;
      } finally {
        // Wipe la clé de test, puis restaure le clone du vrai _key.
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
    final salt = _randomBytes(32);
    await _storage.write(
        key: _saltKeyFor(slot), value: base64Encode(salt));
    _key = await _deriveKey(password, salt, _currentIterations);
    _entries = [];
    _isOpen = true;
    _activeSlot = slot;
    await _saveVault(iterations: _currentIterations);
    await _onUnlockSuccess();
  }

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
  Future<UnlockResult> _tryUnlockSlot(_Slot slot, String masterPassword) async {
    try {
      final saltKey = _saltKeyFor(slot);
      final saltB64 = await _storage.read(key: saltKey);
      if (saltB64 == null) return UnlockResult.wrongPassword;
      final salt = base64Decode(saltB64);

      final file = await _vaultFileFor(slot);
      Map<String, dynamic>? raw;
      int iterations = _currentIterations;
      bool isLegacy  = false;
      if (file.existsSync()) {
        raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
        final stored = raw['iterations'] as int?;
        if (stored == null) {
          iterations = _legacyIterations;
          isLegacy   = true;
        } else {
          if (stored < 1 || stored > _maxIterations) {
            return UnlockResult.wrongPassword;
          }
          iterations = stored;
        }
      } else {
        // Pas de fichier pour ce slot → impossible de matcher
        return UnlockResult.wrongPassword;
      }

      _key = await _deriveKey(masterPassword, salt, iterations);
      final ok = _decryptVault(raw);
      if (ok) {
        await _onUnlockSuccess();
        final needsMigration = isLegacy ||
            iterations < _currentIterations ||
            (raw['version'] as int? ?? 0) < _currentVersion;
        if (needsMigration) {
          if (iterations != _currentIterations) {
            _wipeKey();
            _key = await _deriveKey(masterPassword, salt, _currentIterations);
          }
          // Sauve dans le bon slot avant de retourner success.
          _activeSlot = slot;
          await _saveVault(iterations: _currentIterations);
        }
        return UnlockResult.success;
      } else {
        _wipeKey();
        return UnlockResult.wrongPassword;
      }
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
      throw StateError('La biométrique n\'est disponible que sur le coffre principal');
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
      // Permettre la bio sur le coffre leurre briserait le déni plausible
      // (un attaquant verrait l'option "déverrouiller en biométrique" même
      //  pour le décoy → trace que l'app fait du dual-vault).
      final file = await _vaultFileFor(_Slot.primary);
      Map<String, dynamic>? raw;
      if (file.existsSync()) {
        raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      }
      final ok = _decryptVault(raw);
      if (ok) {
        _activeSlot = _Slot.primary;
        await _onUnlockSuccess();
        return UnlockResult.success;
      } else {
        _wipeKey();
        return UnlockResult.wrongPassword;
      }
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
    // Change uniquement le password du slot actuellement déverrouillé.
    // Le slot opposé (decoy ou primary) n'est pas affecté.
    final slot = _activeSlot ?? _Slot.primary;
    final salt = _randomBytes(32);
    await _storage.write(
        key: _saltKeyFor(slot), value: base64Encode(salt));
    _wipeKey();
    _key = await _deriveKey(newPassword, salt, _currentIterations);
    await _saveVault(iterations: _currentIterations);
    // La biométrique est liée au PRIMARY uniquement. Ne la supprimer QUE
    // si on change le password du primary — sinon un changement de password
    // sur le decoy révélerait l'existence du decoy à un attaquant qui
    // remarquerait que la bio fonctionne plus après son intervention.
    if (slot == _Slot.primary) {
      await deleteBiometricKey();
    }
  }

  String exportJson() =>
      const JsonEncoder.withIndent('  ')
          .convert(_entries.map((e) => e.toJson()).toList());

  Future<void> deleteVault() async {
    lock();
    // Supprime les 2 slots : reset complet de l'app.
    for (final slot in _Slot.values) {
      final file = await _vaultFileFor(slot);
      if (file.existsSync()) file.deleteSync();
      await _storage.delete(key: _saltKeyFor(slot));
    }
    await deleteBiometricKey();
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

  bool _decryptVault(Map<String, dynamic>? raw) {
    try {
      if (raw == null) {
        _entries = [];
        _isOpen = true;
        return true;
      }

      final ivBytes     = base64Decode(raw['iv']   as String);
      final macBytes    = base64Decode(raw['mac']  as String);
      final cipherBytes = base64Decode(raw['data'] as String);
      final version     = raw['version'] as int? ?? 1;
      final iterations  = raw['iterations'] as int? ?? _legacyIterations;
      final saltB64     = raw['salt'] as String? ?? '';

      // Verify HMAC — constant-time comparison
      // v3+ : HMAC over (version || iterations || salt || IV || ciphertext)
      // v1/v2 : HMAC over (IV || ciphertext) only
      final macKey = _key!.sublist(32);
      final List<int> macInput;
      if (version >= 3) {
        final aad = utf8.encode('pt:v=$version|iter=$iterations|salt=$saltB64');
        macInput = [...aad, ...ivBytes, ...cipherBytes];
      } else {
        macInput = [...ivBytes, ...cipherBytes];
      }
      final computed = Hmac(sha256, macKey).convert(macInput).bytes;
      if (!_constEq(computed, macBytes)) return false;

      final encKey    = enc.Key(_key!.sublist(0, 32));
      final iv        = enc.IV(Uint8List.fromList(ivBytes));
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
      final plain = encrypter.decrypt(
          enc.Encrypted(Uint8List.fromList(cipherBytes)),
          iv: iv);

      final list = jsonDecode(plain) as List;
      _entries = list.map((e) => Entry.fromJson(e as Map<String, dynamic>)).toList();
      _isOpen = true;
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> _saveVault({int? iterations}) async {
    if (_key == null) return;
    // Le slot doit être défini : tout flow d'écriture passe par unlock ou
    // create/setup qui le posent. Garde-fou silencieux pour ne pas crasher.
    final slot = _activeSlot ?? _Slot.primary;

    // Preserve existing iterations if not specified (incremental updates keep them)
    int storedIter = iterations ?? _currentIterations;
    if (iterations == null) {
      final file = await _vaultFileFor(slot);
      if (file.existsSync()) {
        try {
          final prev = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
          storedIter = prev['iterations'] as int? ?? _currentIterations;
        } catch (_) {}
      }
    }

    final saltB64 = await _storage.read(key: _saltKeyFor(slot)) ?? '';
    final iv        = enc.IV.fromSecureRandom(16);
    final encKey    = enc.Key(_key!.sublist(0, 32));
    final macKey    = _key!.sublist(32);
    final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));

    final plain     = jsonEncode(_entries.map((e) => e.toJson()).toList());
    final encrypted = encrypter.encrypt(plain, iv: iv);

    // v3 MAC covers (version || iterations || salt || IV || ciphertext)
    final aad = utf8.encode(
        'pt:v=$_currentVersion|iter=$storedIter|salt=$saltB64');
    final mac = Hmac(sha256, macKey)
        .convert([...aad, ...iv.bytes, ...encrypted.bytes]).bytes;

    final out = {
      'version':    _currentVersion,
      'iterations': storedIter,
      'salt':       saltB64,
      'iv':         base64Encode(iv.bytes),
      'mac':        base64Encode(mac),
      'data':       base64Encode(encrypted.bytes),
    };

    // Atomic write dans le slot actif : tmp + rename anti-corruption.
    final target = await _vaultFileFor(slot);
    final tmp = File('${target.path}.tmp');
    tmp.writeAsStringSync(jsonEncode(out), flush: true);
    if (target.existsSync()) target.deleteSync();
    tmp.renameSync(target.path);
  }

  // ── Brute-force protection ──────────────────────────────────────────────────

  Future<void> _onUnlockFail() async {
    final s = await _storage.read(key: _failCountKey);
    final count = (int.tryParse(s ?? '0') ?? 0) + 1;
    await _storage.write(key: _failCountKey, value: count.toString());

    if (count >= _failThreshold) {
      final stepIdx = (count - _failThreshold).clamp(0, _lockoutSteps.length - 1);
      final lockSec = _lockoutSteps[stepIdx];
      final until   = DateTime.now().millisecondsSinceEpoch + lockSec * 1000;
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
      for (int i = 0; i < _key!.length; i++) { _key![i] = 0; }
      _key = null;
    }
  }

  // ── Crypto helpers ──────────────────────────────────────────────────────────

  static bool _constEq(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) { diff |= a[i] ^ b[i]; }
    return diff == 0;
  }

  static Future<Uint8List> _deriveKey(
          String password, List<int> salt, int iterations) async =>
      compute(pbkdf2Worker, [utf8.encode(password), salt, iterations, 64]);

  static Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List.generate(n, (_) => rng.nextInt(256)));
  }
}
