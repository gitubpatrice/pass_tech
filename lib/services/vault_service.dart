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
Uint8List _pbkdf2Worker(List<dynamic> args) {
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

class VaultService {
  static final VaultService _instance = VaultService._();
  factory VaultService() => _instance;
  VaultService._();

  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );
  static const _saltKey        = 'pt_salt';
  static const _biometricKeyKey = 'pt_biometric_key';

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
    _key = await _deriveKey(masterPassword, salt);
    _entries = [];
    _isOpen = true;
    await _saveVault();
  }

  // ── Unlock ──────────────────────────────────────────────────────────────────

  Future<bool> unlock(String masterPassword) async {
    try {
      final saltB64 = await _storage.read(key: _saltKey);
      if (saltB64 == null) return false;
      _key = await _deriveKey(masterPassword, base64Decode(saltB64));
      return await _loadVault();
    } catch (_) {
      _key = null;
      return false;
    }
  }

  Future<bool> get hasBiometricKey async =>
      await _storage.containsKey(key: _biometricKeyKey);

  Future<void> saveBiometricKey() async {
    if (_key == null) return;
    await _storage.write(key: _biometricKeyKey, value: base64Encode(_key!));
  }

  Future<void> deleteBiometricKey() async =>
      _storage.delete(key: _biometricKeyKey);

  Future<bool> unlockWithBiometric() async {
    try {
      final keyB64 = await _storage.read(key: _biometricKeyKey);
      if (keyB64 == null) return false;
      _key = base64Decode(keyB64);
      return await _loadVault();
    } catch (_) {
      _key = null;
      return false;
    }
  }

  void lock() {
    _entries = [];
    _key = null;
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
    _key = await _deriveKey(newPassword, salt);
    await _saveVault();
    await deleteBiometricKey(); // invalidate old biometric key
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
  }

  // ── Internal ─────────────────────────────────────────────────────────────────

  Future<File> _vaultFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/pt_vault.enc');
  }

  Future<bool> _loadVault() async {
    final file = await _vaultFile();
    if (!file.existsSync()) {
      _entries = [];
      _isOpen = true;
      return true;
    }
    try {
      final raw = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final ivBytes     = base64Decode(raw['iv']   as String);
      final macBytes    = base64Decode(raw['mac']  as String);
      final cipherBytes = base64Decode(raw['data'] as String);

      // Verify HMAC-SHA256(macKey, IV || ciphertext) — constant-time comparison
      final macKey = _key!.sublist(32);
      final computed = Hmac(sha256, macKey).convert([...ivBytes, ...cipherBytes]).bytes;
      if (!_constEq(computed, macBytes)) {
        _key = null;
        return false;
      }

      final encKey    = enc.Key(_key!.sublist(0, 32));
      final iv        = enc.IV(Uint8List.fromList(ivBytes));
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
      final plain     = encrypter.decrypt(enc.Encrypted(Uint8List.fromList(cipherBytes)), iv: iv);

      final list = jsonDecode(plain) as List;
      _entries = list.map((e) => Entry.fromJson(e as Map<String, dynamic>)).toList();
      _isOpen = true;
      return true;
    } catch (_) {
      _key = null;
      return false;
    }
  }

  Future<void> _saveVault() async {
    if (_key == null) return;

    final iv        = enc.IV.fromSecureRandom(16);
    final encKey    = enc.Key(_key!.sublist(0, 32));
    final macKey    = _key!.sublist(32);
    final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));

    final plain     = jsonEncode(_entries.map((e) => e.toJson()).toList());
    final encrypted = encrypter.encrypt(plain, iv: iv);
    final mac       = Hmac(sha256, macKey).convert([...iv.bytes, ...encrypted.bytes]).bytes;

    final saltB64 = await _storage.read(key: _saltKey) ?? '';
    final out = {
      'version': 1,
      'salt': saltB64,
      'iv':   base64Encode(iv.bytes),
      'mac':  base64Encode(mac),
      'data': base64Encode(encrypted.bytes),
    };

    (await _vaultFile()).writeAsStringSync(jsonEncode(out));
  }

  static bool _constEq(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) { diff |= a[i] ^ b[i]; }
    return diff == 0;
  }

  static Future<Uint8List> _deriveKey(String password, List<int> salt) async =>
      compute(_pbkdf2Worker, [utf8.encode(password), salt, 100000, 64]);

  static Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List.generate(n, (_) => rng.nextInt(256)));
  }
}
