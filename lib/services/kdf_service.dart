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
//
// PBKDF2 helper exposed for v3 read-only during the migration window.

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
  static const owaspMobile2024 = KdfParams(
    memoryKiB: 19456,
    iterations: 2,
    parallelism: 1,
    outLen: 32,
  );
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
    final pw = Uint8List.fromList(utf8.encode(password));
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

  /// PBKDF2-HMAC-SHA256, exposed read-only for v3 vault decryption during
  /// the migration window.
  static Future<Uint8List> pbkdf2LegacyV3({
    required String password,
    required Uint8List salt,
    required int iterations,
    required int outLen,
  }) {
    return compute(_pbkdf2Isolate, <Object>[
      Uint8List.fromList(utf8.encode(password)),
      salt,
      iterations,
      outLen,
    ]);
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

Future<Uint8List> _pbkdf2Isolate(List<Object> args) async {
  final password = args[0] as Uint8List;
  final salt = args[1] as Uint8List;
  final iterations = args[2] as int;
  final outLen = args[3] as int;

  final pbkdf2 = Pbkdf2(
    macAlgorithm: Hmac.sha256(),
    iterations: iterations,
    bits: outLen * 8,
  );
  final sk = await pbkdf2.deriveKey(
    secretKey: SecretKey(password),
    nonce: salt,
  );
  final bytes = await sk.extractBytes();
  for (var i = 0; i < password.length; i++) {
    password[i] = 0;
  }
  return Uint8List.fromList(bytes);
}
