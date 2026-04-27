import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

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

class VaultService {
  static final VaultService _instance = VaultService._();
  factory VaultService() => _instance;
  VaultService._();

  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  // Secure storage keys
  static const _saltKey         = 'pt_salt';
  static const _biometricKeyKey = 'pt_biometric_key';
  static const _failCountKey    = 'pt_fail_count';
  static const _lockoutKey      = 'pt_lockout_until';

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

  Future<bool> get vaultExists async => (await _vaultFile()).existsSync();

  // ── Setup ───────────────────────────────────────────────────────────────────

  Future<void> createVault(String masterPassword) async {
    final salt = _randomBytes(32);
    await _storage.write(key: _saltKey, value: base64Encode(salt));
    _key = await _deriveKey(masterPassword, salt, _currentIterations);
    _entries = [];
    _isOpen = true;
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
    try {
      final saltB64 = await _storage.read(key: _saltKey);
      if (saltB64 == null) return UnlockResult.wrongPassword;
      final salt = base64Decode(saltB64);

      // Read vault file to get iterations (legacy v1.0.0 has no iterations field)
      final file = await _vaultFile();
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
      }

      _key = await _deriveKey(masterPassword, salt, iterations);
      final ok = _decryptVault(raw);
      if (ok) {
        await _onUnlockSuccess();
        // Silent migration: re-derive with stronger iterations and re-save in v3
        final needsMigration = isLegacy ||
            iterations < _currentIterations ||
            (raw?['version'] as int? ?? 0) < _currentVersion;
        if (needsMigration) {
          if (iterations != _currentIterations) {
            _wipeKey();
            _key = await _deriveKey(masterPassword, salt, _currentIterations);
          }
          await _saveVault(iterations: _currentIterations);
        }
        return UnlockResult.success;
      } else {
        _wipeKey();
        await _onUnlockFail();
        return UnlockResult.wrongPassword;
      }
    } catch (_) {
      _wipeKey();
      await _onUnlockFail();
      return UnlockResult.wrongPassword;
    }
  }

  Future<bool> get hasBiometricKey async =>
      _storage.containsKey(key: _biometricKeyKey);

  Future<void> saveBiometricKey() async {
    if (_key == null) return;
    await _storage.write(key: _biometricKeyKey, value: base64Encode(_key!));
  }

  Future<void> deleteBiometricKey() async =>
      _storage.delete(key: _biometricKeyKey);

  Future<UnlockResult> unlockWithBiometric() async {
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    try {
      final keyB64 = await _storage.read(key: _biometricKeyKey);
      if (keyB64 == null) return UnlockResult.wrongPassword;
      _key = base64Decode(keyB64);

      final file = await _vaultFile();
      Map<String, dynamic>? raw;
      if (file.existsSync()) {
        raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      }
      final ok = _decryptVault(raw);
      if (ok) {
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
    final salt = _randomBytes(32);
    await _storage.write(key: _saltKey, value: base64Encode(salt));
    _wipeKey();
    _key = await _deriveKey(newPassword, salt, _currentIterations);
    await _saveVault(iterations: _currentIterations);
    await deleteBiometricKey();
  }

  String exportJson() =>
      const JsonEncoder.withIndent('  ')
          .convert(_entries.map((e) => e.toJson()).toList());

  Future<void> deleteVault() async {
    lock();
    final file = await _vaultFile();
    if (file.existsSync()) file.deleteSync();
    await _storage.delete(key: _saltKey);
    await _storage.delete(key: _biometricKeyKey);
    await _storage.delete(key: _failCountKey);
    await _storage.delete(key: _lockoutKey);
  }

  // ── Internal ────────────────────────────────────────────────────────────────

  Future<File> _vaultFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/pt_vault.enc');
  }

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

    // Preserve existing iterations if not specified (incremental updates keep them)
    int storedIter = iterations ?? _currentIterations;
    if (iterations == null) {
      final file = await _vaultFile();
      if (file.existsSync()) {
        try {
          final prev = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
          storedIter = prev['iterations'] as int? ?? _currentIterations;
        } catch (_) {}
      }
    }

    final saltB64 = await _storage.read(key: _saltKey) ?? '';
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

    // Atomic write : tmp + rename to avoid corruption on crash mid-write
    final target = await _vaultFile();
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
