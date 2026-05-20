import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/entry.dart';
import 'aead_service.dart';
import 'kdf_service.dart';
import 'monotonic_clock.dart';
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
///   via Argon2id (m=19 MiB, t=2, p=1) + salt aléatoire de 32 octets.
/// - Format v2 (depuis v2.2.0) : AES-GCM-256 avec AAD bound (anti-downgrade).
/// - Format v1 (legacy, read-only) : PBKDF2-SHA256 600 000 + AES-CBC + HMAC.
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

  static const _iterations = 600000; // legacy v1 only (PBKDF2)
  static const _heirVersionV1 = 1; // PBKDF2 + AES-CBC + HMAC-SHA256
  static const _heirVersionV2 = 2; // Argon2id + AES-GCM-256 (v2.2.0)
  static const _defaultThresholdDays = 90;
  static const _gracePeriodDays = 7;

  // Argon2id baseline — source unique : KdfParams.owaspMobile2024.
  static final _argon2M = KdfParams.owaspMobile2024.memoryKiB;
  static final _argon2T = KdfParams.owaspMobile2024.iterations;
  static final _argon2P = KdfParams.owaspMobile2024.parallelism;

  /// True si un snapshot héritage a déjà été configuré.
  Future<bool> get isEnabled async {
    final v = await _storage.read(key: _enabledKey);
    return v == '1';
  }

  /// Existence du fichier snapshot. Plus fiable que le flag (en cas de reset
  /// partiel du device).
  Future<bool> get snapshotExists async => (await _heirFile()).existsSync();

  /// v2.3.2 : migre les 3 clés de SharedPreferences (non chiffré, forgeable
  /// sur device rooté) vers FlutterSecureStorage (Keystore-backed, non
  /// forgeable). Best-effort : si la migration échoue, on continue avec FSS
  /// vide et le compteur dead-man redémarrera proprement.
  Future<void> _migrateFromPrefsIfNeeded() async {
    try {
      if (await _storage.containsKey(key: _lastActiveKey)) return;
      final prefs = await SharedPreferences.getInstance();
      final last = prefs.getInt(_lastActiveKey);
      final grace = prefs.getInt(_graceStartKey);
      final threshold = prefs.getInt(_thresholdKey);
      if (last != null) {
        await _storage.write(key: _lastActiveKey, value: last.toString());
        await prefs.remove(_lastActiveKey);
      }
      if (grace != null) {
        await _storage.write(key: _graceStartKey, value: grace.toString());
        await prefs.remove(_graceStartKey);
      }
      if (threshold != null) {
        await _storage.write(key: _thresholdKey, value: threshold.toString());
        await prefs.remove(_thresholdKey);
      }
    } catch (e) {
      if (kDebugMode) {
        debugPrint('HeritageService._migrateFromPrefsIfNeeded failed: $e');
      }
    }
  }

  /// Seuil d'inactivité configuré en jours (défaut 90).
  Future<int> getThresholdDays() async {
    await _migrateFromPrefsIfNeeded();
    final v = await _storage.read(key: _thresholdKey);
    return int.tryParse(v ?? '') ?? _defaultThresholdDays;
  }

  Future<void> setThresholdDays(int days) async {
    if (days < 7 || days > 365) {
      throw ArgumentError('Seuil hors plage [7, 365] jours');
    }
    await _storage.write(key: _thresholdKey, value: days.toString());
  }

  /// Marque l'utilisateur principal comme actif maintenant. Appelé à chaque
  /// unlock du vault primary (PAS depuis le heir lui-même). Reset aussi
  /// la grace period si elle était démarrée.
  Future<void> markActive() async {
    // Si l'écriture échoue (FS R/O, low memory, FSS indisponible), on logge
    // mais on n'interrompt pas le flow d'unlock. Le risque est que le timer
    // ne se reset pas → l'héritier accède plus tôt que prévu, ce qui est
    // moins grave qu'un échec d'unlock visible à l'utilisateur.
    try {
      // A6 v2.3.8 — horloge monotone (anti clock-skew root).
      final now = await MonotonicClock.nowMs();
      await _storage.write(key: _lastActiveKey, value: now.toString());
      await _storage.delete(key: _graceStartKey);
    } catch (e) {
      if (kDebugMode) debugPrint('HeritageService.markActive failed: $e');
    }
  }

  /// Nombre de jours depuis la dernière activité du primary. -1 si jamais
  /// déverrouillé (donc pas de last_active).
  Future<int> getInactivityDays() async {
    await _migrateFromPrefsIfNeeded();
    final v = await _storage.read(key: _lastActiveKey);
    final ts = int.tryParse(v ?? '');
    if (ts == null) return -1;
    final now = await MonotonicClock.nowMs();
    final diff = now - ts;
    if (diff < 0) return 0;
    return diff ~/ (1000 * 60 * 60 * 24);
  }

  /// Démarre le compte à rebours de grâce si pas déjà démarré. Appelé à
  /// chaque ouverture de l'app si l'inactivité dépasse le seuil.
  Future<void> startGraceIfNeeded() async {
    if (await _storage.containsKey(key: _graceStartKey)) return;
    final now = await MonotonicClock.nowMs();
    await _storage.write(key: _graceStartKey, value: now.toString());
  }

  /// Jours restants de la période de grâce. -1 si pas démarrée.
  /// 0 si expirée (l'héritier peut accéder).
  Future<int> getGraceDaysRemaining() async {
    final v = await _storage.read(key: _graceStartKey);
    final ts = int.tryParse(v ?? '');
    if (ts == null) return -1;
    final now = await MonotonicClock.nowMs();
    final elapsed = now - ts;
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
    // v2.2.0 : write target = v2 (Argon2id + AES-GCM-256). Le salt sert à la
    // dérivation Argon2id ET à l'AAD (anti-downgrade). v1 reste lisible pour
    // les snapshots historiques (pas de break compat).
    final salt = SecretBytes.randomBytes(32);
    final key = await KdfService.argon2id(password: heirPassword, salt: salt);
    // v2.5.0 (F6) : ordre revu — fichier AVANT salt + rollback explicite si
    // l'écriture du salt échoue. Ancien ordre (salt → fichier) créait une
    // fenêtre de désync sur les updates : si l'écriture du fichier échouait
    // après l'écriture du nouveau salt, l'ancien fichier devenait illisible
    // pour l'héritier (chiffré avec l'ancien salt mais le nouveau salt est
    // déjà persisté). Nouveau flow : tmp+rename atomique du fichier, puis
    // salt, puis enabled. Si salt/enabled fail, restauration des anciennes
    // valeurs depuis le snapshot RAM.
    final oldSalt = await _storage.read(key: _saltKey);
    final oldEnabled = await _storage.read(key: _enabledKey);
    try {
      // 1) Fichier nouveau (atomic tmp+rename dans _writeSnapshotV2). Si
      //    échec ici, ancien fichier + ancien salt intacts.
      await _writeSnapshotV2(entries, key, salt);
      try {
        // 2) Salt cohérent avec le nouveau fichier.
        await _storage.write(key: _saltKey, value: base64Encode(salt));
        // 3) Activation.
        await _storage.write(key: _enabledKey, value: '1');
      } catch (e) {
        // Rollback : si l'écriture du salt ou de enabled échoue, restaurer
        // les anciennes valeurs pour rester cohérent avec l'ancien fichier
        // (qui a été remplacé — perte des anciennes données héritage, mais
        // l'état storage reste cohérent et un nouveau setup réparera).
        try {
          if (oldSalt != null) {
            await _storage.write(key: _saltKey, value: oldSalt);
          } else {
            await _storage.delete(key: _saltKey);
          }
          if (oldEnabled != null) {
            await _storage.write(key: _enabledKey, value: oldEnabled);
          } else {
            await _storage.delete(key: _enabledKey);
          }
        } catch (_) {
          // Best-effort rollback — si même ça échoue, le storage est
          // probablement HS, l'exception originale remonte.
        }
        rethrow;
      }
    } finally {
      SecretBytes.wipe(key);
    }
  }

  /// Supprime le snapshot et désactive l'héritage.
  Future<void> disable() async {
    final f = await _heirFile();
    if (f.existsSync()) f.deleteSync();
    await _storage.delete(key: _saltKey);
    await _storage.delete(key: _enabledKey);
    await _storage.delete(key: _graceStartKey);
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
      final version = raw['version'] as int? ?? _heirVersionV1;
      // M-9 : version bornée
      if (version < 1 || version > _heirVersionV2) return null;

      if (version == _heirVersionV2) {
        // v2 : Argon2id 32B → AES-GCM-256
        final key = await KdfService.argon2id(
          password: heirPassword,
          salt: salt,
        );
        try {
          return await _readSnapshotV2(raw, key);
        } finally {
          SecretBytes.wipe(key);
        }
      }

      // v1 (legacy) : PBKDF2 64B → AES-CBC + HMAC-SHA256
      final iter = raw['iterations'] as int? ?? _iterations;
      if (iter < 1 || iter > 2000000) return null;
      final key = await _deriveKeyV1(heirPassword, salt, iter);
      try {
        return _readSnapshotV1(raw, key);
      } finally {
        SecretBytes.wipe(key);
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

  // v2.2.0 — shims locaux supprimés. Les callsites utilisent `SecretBytes.*`
  // directement.
  //
  // M-2 : `SecretBytes.constantTimeEq` retourne tôt sur length mismatch.
  // Inoffensif ici (HMAC-SHA256 32 octets fixes). Ne pas réutiliser pour
  // des secrets de longueur variable.

  Future<Uint8List> _deriveKeyV1(String password, Uint8List salt, int iter) {
    return compute(pbkdf2Worker, [utf8.encode(password), salt, iter, 64]);
  }

  /// AAD bound to a v2 heir snapshot (anti-downgrade).
  Uint8List _aadV2(String saltB64) => Uint8List.fromList(
    utf8.encode(
      'pt-heir:v=$_heirVersionV2|kdf=argon2id|m=$_argon2M|t=$_argon2T|p=$_argon2P|salt=$saltB64',
    ),
  );

  /// Écrit le snapshot au format v2 (Argon2id + AES-GCM-256).
  /// `key` doit être la sortie Argon2id 32B.
  Future<void> _writeSnapshotV2(
    List<Entry> entries,
    Uint8List key,
    Uint8List salt,
  ) async {
    final saltB64 = base64Encode(salt);
    final aad = _aadV2(saltB64);
    final plain = Uint8List.fromList(
      utf8.encode(jsonEncode(entries.map((e) => e.toJson()).toList())),
    );
    final res = await AeadService.encryptGcm(
      key: key,
      plaintext: plain,
      aad: aad,
    );

    final out = {
      'magic': 'PTHEIR',
      'version': _heirVersionV2,
      'kdf': {
        'algo': 'argon2id',
        'm': _argon2M,
        't': _argon2T,
        'p': _argon2P,
        'salt': saltB64,
      },
      'cipher': {
        'nonce': base64Encode(res.nonce),
        'data': base64Encode(res.cipherAndTag),
      },
    };

    // Écriture atomique : tmp + rename (anti corruption sur crash mid-write)
    // F26 v2.3.7 : POSIX rename() overwrite atomique. Le delete préalable
    // créait une fenêtre où un kill OS entre delete et rename perdait le
    // snapshot. rename() seul est l'invariant correct.
    final target = await _heirFile();
    final tmp = File('${target.path}.tmp');
    tmp.writeAsStringSync(jsonEncode(out), flush: true);
    tmp.renameSync(target.path);
  }

  /// Déchiffre un snapshot v2. La clé fournie est la sortie Argon2id 32B.
  Future<List<Entry>?> _readSnapshotV2(
    Map<String, dynamic> raw,
    Uint8List key,
  ) async {
    try {
      if (raw['magic'] != 'PTHEIR') return null;
      final cipher = raw['cipher'];
      final kdf = raw['kdf'];
      if (cipher is! Map || kdf is! Map) return null;
      final saltB64 = kdf['salt'] as String? ?? '';
      final nonce = base64Decode(cipher['nonce'] as String);
      final dataBlob = base64Decode(cipher['data'] as String);
      final split = AeadService.splitCipherAndTag(dataBlob);
      final aad = _aadV2(saltB64);
      final pt = await AeadService.decryptGcm(
        key: key,
        nonce: nonce,
        ciphertext: split.ciphertext,
        tag: split.tag,
        aad: aad,
      );
      if (pt == null) return null;
      final list = jsonDecode(utf8.decode(pt)) as List;
      return list
          .map((e) => Entry.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return null;
    }
  }

  /// Lecture v1 (legacy : PBKDF2 + AES-CBC + HMAC-SHA256). Conservé en
  /// read-only pour les snapshots créés avant v2.2.0.
  List<Entry>? _readSnapshotV1(Map<String, dynamic> raw, Uint8List key) {
    Uint8List? macKey;
    Uint8List? encKeyBytes;
    try {
      final ivBytes = base64Decode(raw['iv'] as String);
      final macBytes = base64Decode(raw['mac'] as String);
      final cipherBytes = base64Decode(raw['data'] as String);
      final saltB64 = raw['salt'] as String? ?? '';
      // P2-fix v2.3.3 : refuse un fichier forgé avec salt vide ou trop court.
      // Sans cette borne, PBKDF2 sur salt vide réduirait la difficulté de
      // brute-force d'un attaquant ayant accès au fichier (root requis).
      if (saltB64.isEmpty || base64Decode(saltB64).length < 16) return null;
      final version = raw['version'] as int? ?? 1;
      if (version != _heirVersionV1) return null;
      final iterations = raw['iterations'] as int? ?? _iterations;

      macKey = key.sublist(32);
      final aad = utf8.encode(
        'pt-heir:v=$version|iter=$iterations|salt=$saltB64',
      );
      final computed = Hmac(
        sha256,
        macKey,
      ).convert([...aad, ...ivBytes, ...cipherBytes]).bytes;
      if (!SecretBytes.constantTimeEq(computed, macBytes)) return null;

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
      if (macKey != null) SecretBytes.wipe(macKey);
      if (encKeyBytes != null) SecretBytes.wipe(encKeyBytes);
    }
  }
}
