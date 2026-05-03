import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/entry.dart';
import 'vault_service.dart';

/// Héritage / dead man's switch — accès aux données du coffre par un héritier
/// (conjoint, enfant, exécuteur testamentaire) après une période d'inactivité
/// prolongée du propriétaire.
///
/// Approche cryptographique :
/// - Lors de la configuration, l'utilisateur fournit un **heir password**
///   distinct du master password.
/// - L'app crée un **snapshot chiffré séparé** (`pt_heir.enc`) contenant la
///   même liste d'entries, chiffrée avec une clé dérivée du heir_password
///   via PBKDF2-SHA256 600 000 itérations + salt aléatoire de 32 octets.
/// - Format identique au vault principal (AES-256-CBC + HMAC-SHA256 v3).
/// - L'utilisateur peut "régénérer" le snapshot pour mettre à jour ce que
///   l'héritier verra.
///
/// Mécanisme dead-man :
/// - Timestamp `last_active` mis à jour à chaque déverrouillage du primary
///   ou du decoy (mais pas du heir lui-même).
/// - Si la période d'inactivité dépasse le seuil configuré (30/60/90/180j),
///   un compte à rebours de **7 jours de grâce** démarre.
/// - Pendant la grâce, le propriétaire peut se reconnecter et tout reset.
/// - Après expiration de la grâce, l'écran d'unlock propose une option
///   « Accès héritier » avec un champ heir_password.
///
/// **Important** : le heir_password n'est jamais stocké, n'est jamais
/// recoverable. L'utilisateur doit le partager (oralement, via testament,
/// dans un endroit sûr) avec son héritier hors-bande.
class HeritageService {
  static const _storage = FlutterSecureStorage();
  static const _saltKey = 'pt_heir_salt';
  static const _enabledKey = 'pt_heir_enabled';
  static const _lastActiveKey = 'pt_last_active_ts';
  static const _thresholdKey = 'pt_heir_threshold_days';
  static const _graceStartKey = 'pt_heir_grace_start_ts';

  static const _iterations = 600000;
  static const _heirVersion = 1;
  static const _defaultThresholdDays = 90;
  static const _gracePeriodDays = 7;

  /// True si un snapshot héritage a déjà été configuré.
  Future<bool> get isEnabled async {
    final v = await _storage.read(key: _enabledKey);
    return v == '1';
  }

  /// Existence du fichier snapshot. Plus fiable que le flag (en cas de reset
  /// partiel du device).
  Future<bool> get snapshotExists async => (await _heirFile()).existsSync();

  /// Seuil d'inactivité configuré en jours (défaut 90).
  Future<int> getThresholdDays() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_thresholdKey) ?? _defaultThresholdDays;
  }

  Future<void> setThresholdDays(int days) async {
    if (days < 7 || days > 365) {
      throw ArgumentError('Seuil hors plage [7, 365] jours');
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_thresholdKey, days);
  }

  /// Marque l'utilisateur principal comme actif maintenant. Appelé à chaque
  /// unlock du vault primary (PAS depuis le heir lui-même). Reset aussi
  /// la grace period si elle était démarrée.
  Future<void> markActive() async {
    // Si l'écriture échoue (FS R/O, low memory, prefs corrompues), on logge
    // mais on n'interrompt pas le flow d'unlock. Le risque est que le timer
    // ne se reset pas → l'héritier accède plus tôt que prévu, ce qui est
    // moins grave qu'un échec d'unlock visible à l'utilisateur.
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt(_lastActiveKey, DateTime.now().millisecondsSinceEpoch);
      await prefs.remove(_graceStartKey);
    } catch (e) {
      debugPrint('HeritageService.markActive failed: $e');
    }
  }

  /// Nombre de jours depuis la dernière activité du primary. -1 si jamais
  /// déverrouillé (donc pas de last_active).
  Future<int> getInactivityDays() async {
    final prefs = await SharedPreferences.getInstance();
    final ts = prefs.getInt(_lastActiveKey);
    if (ts == null) return -1;
    final diff = DateTime.now().millisecondsSinceEpoch - ts;
    return diff ~/ (1000 * 60 * 60 * 24);
  }

  /// Démarre le compte à rebours de grâce si pas déjà démarré. Appelé à
  /// chaque ouverture de l'app si l'inactivité dépasse le seuil.
  Future<void> startGraceIfNeeded() async {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.containsKey(_graceStartKey)) return;
    await prefs.setInt(_graceStartKey, DateTime.now().millisecondsSinceEpoch);
  }

  /// Jours restants de la période de grâce. -1 si pas démarrée.
  /// 0 si expirée (l'héritier peut accéder).
  Future<int> getGraceDaysRemaining() async {
    final prefs = await SharedPreferences.getInstance();
    final ts = prefs.getInt(_graceStartKey);
    if (ts == null) return -1;
    final elapsed = DateTime.now().millisecondsSinceEpoch - ts;
    final remainingMs = _gracePeriodDays * 24 * 60 * 60 * 1000 - elapsed;
    if (remainingMs <= 0) return 0;
    return (remainingMs / (1000 * 60 * 60 * 24)).ceil();
  }

  /// True si l'écran d'unlock doit proposer l'option « Accès héritier ».
  /// Conditions : héritage activé + inactivité > threshold + grâce expirée.
  Future<bool> shouldShowHeirOption() async {
    if (!await isEnabled) return false;
    if (!await snapshotExists) return false;
    final threshold = await getThresholdDays();
    final inactivity = await getInactivityDays();
    if (inactivity < 0 || inactivity < threshold) return false;
    // Démarre la grâce si pas démarrée
    await startGraceIfNeeded();
    final remaining = await getGraceDaysRemaining();
    return remaining == 0;
  }

  // ── Setup / update / disable ────────────────────────────────────────────

  /// Configure (ou met à jour) le snapshot héritage avec le contenu actuel
  /// du vault. **Le vault doit être déverrouillé** (sur primary, pas decoy).
  ///
  /// [heirPassword] doit différer du password primary (vérifié à l'appel
  /// via VaultService.passwordMatchesPrimary par l'UI).
  Future<void> setupOrUpdateSnapshot({required String heirPassword}) async {
    if (heirPassword.length < 8) {
      throw ArgumentError('Heir password : 8 caractères minimum');
    }
    final entries = VaultService().entries;
    if (entries.isEmpty) {
      throw StateError('Le coffre est vide — rien à transmettre');
    }
    final salt = _randomBytes(32);
    final key = await _deriveKey(heirPassword, salt, _iterations);
    try {
      // Ordre critique : salt AVANT le fichier. Si crash après salt et
      // avant fichier, le prochain setup réécrasera salt sans état corrompu.
      // Inverse causerait "fichier sans salt" → unlockAsHeir retourne null
      // pour toujours sans recovery possible.
      await _storage.write(key: _saltKey, value: base64Encode(salt));
      await _writeSnapshot(entries, key, salt);
      await _storage.write(key: _enabledKey, value: '1');
    } finally {
      _wipe(key);
    }
  }

  /// Supprime le snapshot et désactive l'héritage.
  Future<void> disable() async {
    final f = await _heirFile();
    if (f.existsSync()) f.deleteSync();
    await _storage.delete(key: _saltKey);
    await _storage.delete(key: _enabledKey);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_graceStartKey);
  }

  // ── Unlock as heir ──────────────────────────────────────────────────────

  /// Tente de déverrouiller le snapshot avec [heirPassword]. Retourne la
  /// liste des entries si succès, null sinon.
  /// Ne touche **PAS** au state du VaultService primary — l'héritier obtient
  /// uniquement la liste, à charge du caller d'afficher / partager.
  Future<List<Entry>?> unlockAsHeir(String heirPassword) async {
    try {
      final saltB64 = await _storage.read(key: _saltKey);
      if (saltB64 == null) return null;
      final salt = base64Decode(saltB64);
      final f = await _heirFile();
      if (!f.existsSync()) return null;
      final raw = jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
      final iter = raw['iterations'] as int? ?? _iterations;
      // Garde-fou : iterations bornées (anti-DoS sur fichier altéré)
      if (iter < 1 || iter > 2000000) return null;
      final key = await _deriveKey(heirPassword, salt, iter);
      try {
        return _readSnapshot(raw, key);
      } finally {
        _wipe(key);
      }
    } catch (_) {
      return null;
    }
  }

  // ── Internal ────────────────────────────────────────────────────────────

  Future<File> _heirFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/pt_heir.enc');
  }

  Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List<int>.generate(n, (_) => rng.nextInt(256)));
  }

  Future<Uint8List> _deriveKey(String password, Uint8List salt, int iter) {
    return compute(pbkdf2Worker, [utf8.encode(password), salt, iter, 64]);
  }

  void _wipe(Uint8List k) {
    for (var i = 0; i < k.length; i++) {
      k[i] = 0;
    }
  }

  // M-2 : retour précoce sur length mismatch — inoffensif uniquement parce
  // qu'on compare des HMAC-SHA256 de longueur fixe (32 octets). Ne pas
  // réutiliser pour des secrets de longueur variable.
  bool _constEq(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  Future<void> _writeSnapshot(
    List<Entry> entries,
    Uint8List key,
    Uint8List salt,
  ) async {
    final iv = enc.IV.fromSecureRandom(16);
    // M-3 : sublist crée des copies. Zéroïser en finally pour éviter de
    // laisser des fragments de la heir key en RAM jusqu'au GC.
    final encKeyBytes = key.sublist(0, 32);
    final macKey = key.sublist(32);
    try {
      final encKey = enc.Key(encKeyBytes);
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));

      final plain = jsonEncode(entries.map((e) => e.toJson()).toList());
      final encrypted = encrypter.encrypt(plain, iv: iv);

      final saltB64 = base64Encode(salt);
      final aad = utf8.encode(
        'pt-heir:v=$_heirVersion|iter=$_iterations|salt=$saltB64',
      );
      final mac = Hmac(
        sha256,
        macKey,
      ).convert([...aad, ...iv.bytes, ...encrypted.bytes]).bytes;

      final out = {
        'version': _heirVersion,
        'iterations': _iterations,
        'salt': saltB64,
        'iv': base64Encode(iv.bytes),
        'mac': base64Encode(mac),
        'data': base64Encode(encrypted.bytes),
      };

      // Écriture atomique : tmp + rename (anti corruption sur crash mid-write)
      final target = await _heirFile();
      final tmp = File('${target.path}.tmp');
      tmp.writeAsStringSync(jsonEncode(out), flush: true);
      if (target.existsSync()) target.deleteSync();
      tmp.renameSync(target.path);
    } finally {
      _wipe(encKeyBytes);
      _wipe(macKey);
    }
  }

  List<Entry>? _readSnapshot(Map<String, dynamic> raw, Uint8List key) {
    Uint8List? macKey;
    Uint8List? encKeyBytes;
    try {
      final ivBytes = base64Decode(raw['iv'] as String);
      final macBytes = base64Decode(raw['mac'] as String);
      final cipherBytes = base64Decode(raw['data'] as String);
      final saltB64 = raw['salt'] as String? ?? '';
      final version = raw['version'] as int? ?? 1;
      // M-9 : version bornée
      if (version < 1 || version > _heirVersion) return null;
      final iterations = raw['iterations'] as int? ?? _iterations;

      macKey = key.sublist(32);
      final aad = utf8.encode(
        'pt-heir:v=$version|iter=$iterations|salt=$saltB64',
      );
      final computed = Hmac(
        sha256,
        macKey,
      ).convert([...aad, ...ivBytes, ...cipherBytes]).bytes;
      if (!_constEq(computed, macBytes)) return null;

      encKeyBytes = key.sublist(0, 32);
      final encKey = enc.Key(encKeyBytes);
      final iv = enc.IV(Uint8List.fromList(ivBytes));
      final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
      final plain = encrypter.decrypt(
        enc.Encrypted(Uint8List.fromList(cipherBytes)),
        iv: iv,
      );
      final list = jsonDecode(plain) as List;
      return list
          .map((e) => Entry.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return null;
    } finally {
      // M-3 : zéroïser les sous-buffers dérivés de la heir key
      if (macKey != null) _wipe(macKey);
      if (encKeyBytes != null) _wipe(encKeyBytes);
    }
  }
}
