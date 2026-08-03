// KDF service — v4 hardening (H-2).
//
// Argon2id (RFC 9106) replaces PBKDF2-HMAC-SHA256 from v3.
// Parameters fixed by ROADMAP_HARDENING.md (decision #3) :
//   m = 19456 KiB (19 MiB, OWASP 2024)
//   t = 2
//   p = 1
//   outLen = 32
//
// Uses `cryptography` (pure Dart, also covers RFC 9106 test vectors). The
// `cryptography_flutter` plugin auto-registers FFI-backed Argon2id on
// Android at startup (no `enable()` call needed since v2.3.x). On non-Android
// targets the pure-Dart fallback is used (slower but correct).
//
// Crypto runs on a background isolate via `compute()` so the unlock screen
// stays responsive during the ~1 s cost on a Galaxy S9.

import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:flutter/foundation.dart';

class KdfParams {
  /// Memory cost in KiB. OWASP 2024 baseline = 19456 (19 MiB).
  final int memoryKiB;

  /// Time cost (iterations).
  final int iterations;

  /// Parallelism lanes. p=1 on mobile (single core, lowest variance).
  final int parallelism;

  /// Output key length in bytes.
  final int outLen;

  const KdfParams({
    required this.memoryKiB,
    required this.iterations,
    required this.parallelism,
    required this.outLen,
  });

  /// OWASP 2024 baseline for password manager unlock on mobile.
  ///
  /// ⚠️ Cette constante est la valeur d'ÉCRITURE : elle s'applique aux coffres,
  /// instantanés et sauvegardes **créés maintenant**. Elle n'est plus la valeur
  /// de LECTURE — voir [fromFileOrNull]. C'est ce découplage qui permet de la
  /// faire évoluer un jour sans rendre illisible ce qui existe déjà.
  static const owaspMobile2024 = KdfParams(
    memoryKiB: 19456,
    iterations: 2,
    parallelism: 1,
    outLen: 32,
  );

  // Bornes de lecture. Elles ne disent pas ce qui est RECOMMANDÉ (c'est le rôle
  // de `owaspMobile2024`) mais ce qu'on accepte d'un fichier sans se mettre en
  // danger : un fichier hostile annonçant m = 1 Gio ou t = 64 épuiserait la
  // mémoire et le processeur de l'appareil rien qu'à l'ouverture.
  static const _minMemoryKiB = 4096;
  static const _maxMemoryKiB = 1024 * 1024;
  static const _maxIterations = 16;
  static const _maxParallelism = 4;

  /// Relit les paramètres Argon2id **écrits dans un fichier**.
  ///
  /// AUDIT 2026-08-03 — jusqu'ici, `kdf.m` / `kdf.t` / `kdf.p` étaient
  /// sérialisés dans les trois formats (coffre v4, instantané héritier v2,
  /// sauvegarde `.ptbak` v3) mais **jamais relus** : la dérivation employait
  /// systématiquement les constantes de compilation. Les champs du fichier
  /// étaient purement décoratifs — exactement le piège que la v2.3.2 avait
  /// supprimé pour le champ `aad` du coffre, au motif qu'« un dev futur
  /// croirait ce champ autoritaire ».
  ///
  /// Le `.ptbak` faisait pire : `importEncrypted` lisait bien m/t/p pour
  /// dériver la clé, mais construisait l'AAD à partir des constantes. Deux
  /// sources de vérité contradictoires dans une seule fonction.
  ///
  /// Conséquence si l'on relevait un jour [owaspMobile2024] — ce que la veille
  /// OWASP finira par imposer : **tous les coffres, instantanés et sauvegardes
  /// existants deviendraient indéchiffrables d'un coup**, en silence, sous le
  /// message « mot de passe incorrect ». Aucun garde-fou n'existait, et aucun
  /// test ne couvrait le cas (ceux du `.ptbak` ne forgent que des valeurs HORS
  /// bornes, jamais des valeurs valides mais différentes).
  ///
  /// Contrat :
  ///  - champs absents → [fallback] (fichier d'une version qui ne les écrivait
  ///    pas ; en pratique aucune, mais on ne casse rien) ;
  ///  - champs présents et sains → ces valeurs-là ;
  ///  - champs présents mais hors bornes ou du mauvais type → `null`,
  ///    l'appelant DOIT refuser le fichier (fail-closed).
  static KdfParams? fromFileOrNull(
    Map<dynamic, dynamic> kdf, {
    KdfParams fallback = owaspMobile2024,
    int outLen = 32,
  }) {
    final m = kdf['m'];
    final t = kdf['t'];
    final p = kdf['p'];
    if (m == null && t == null && p == null) return fallback;
    if (m is! int || t is! int || p is! int) return null;
    if (m < _minMemoryKiB || m > _maxMemoryKiB) return null;
    if (t < 1 || t > _maxIterations) return null;
    if (p < 1 || p > _maxParallelism) return null;
    return KdfParams(
      memoryKiB: m,
      iterations: t,
      parallelism: p,
      outLen: outLen,
    );
  }
}

/// Marker so callers know which algorithm was used for the derived key.
enum KdfAlgo { argon2id, pbkdf2HmacSha256Legacy }

class KdfService {
  KdfService._();

  /// Derive a key with Argon2id (v4). Runs on a background isolate.
  static Future<Uint8List> argon2id({
    required String password,
    required Uint8List salt,
    KdfParams params = KdfParams.owaspMobile2024,
  }) async {
    // SEC 2026-08-03 (Gemini PT-001) — plus de `Uint8List.fromList(...)`.
    //
    // `utf8.encode` rend DÉJÀ un `Uint8List`. L'envelopper en créait une
    // seconde copie, et seule celle-là était effacée : l'originale, qui
    // contient le mot de passe maître en clair, restait en mémoire jusqu'au
    // ramasse-miettes. `vault_storage.dart` documente ce piège exact depuis la
    // v2.5.x et l'évite — la règle n'avait pas été propagée ici, ni aux quatre
    // autres sites du même motif.
    final pw = utf8.encode(password);
    try {
      return await compute(_argon2idIsolate, <Object>[
        pw,
        salt,
        params.memoryKiB,
        params.iterations,
        params.parallelism,
        params.outLen,
      ]);
    } finally {
      // Wipe the local UTF-8 password copy. The original Dart String is still
      // on the heap (limitation documented in SECURITY.md, item M-4).
      for (var i = 0; i < pw.length; i++) {
        pw[i] = 0;
      }
    }
  }
}

// Top-level isolate entry-points (compute() requires them top-level).

Future<Uint8List> _argon2idIsolate(List<Object> args) async {
  final password = args[0] as Uint8List;
  final salt = args[1] as Uint8List;
  final memoryKiB = args[2] as int;
  final iterations = args[3] as int;
  final parallelism = args[4] as int;
  final outLen = args[5] as int;

  final algo = Argon2id(
    memory: memoryKiB,
    parallelism: parallelism,
    iterations: iterations,
    hashLength: outLen,
  );

  final secretKey = await algo.deriveKey(
    secretKey: SecretKey(password),
    nonce: salt,
  );
  final bytes = await secretKey.extractBytes();
  for (var i = 0; i < password.length; i++) {
    password[i] = 0;
  }
  return Uint8List.fromList(bytes);
}
