// AEAD service — v4 hardening (H-1).
//
// AES-GCM 256 (NIST SP 800-38D) replaces AES-CBC + separate HMAC-SHA256 from
// v3. GCM provides authenticated encryption with associated data (AAD) in a
// single primitive, avoiding the encrypt-then-MAC pitfalls (HMAC key
// separation, constant-time MAC compare).
//
// Backed by `cryptography` (pure Dart) with FFI acceleration on Android via
// `cryptography_flutter` (auto-registered).
//
// AAD policy (anti-downgrade) — for the vault file:
//   pt:v=4|alias=<alias>|kdf=argon2id|m=19456|t=2|p=1
// AAD MUST be byte-identical at encrypt and decrypt time, otherwise the GCM
// tag check fails closed.
//
// Nonce: 96 bits (12 bytes), random from a CSPRNG. Reuse is catastrophic for
// GCM, so each call generates a fresh nonce — never derive from a counter
// without state guarantees.

import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

class AeadResult {
  /// Ciphertext only (no tag, no nonce).
  final Uint8List ciphertext;

  /// 12-byte GCM nonce. Caller is responsible for storing it.
  final Uint8List nonce;

  /// 16-byte GCM tag. Stored alongside ciphertext or concatenated.
  final Uint8List tag;

  const AeadResult({
    required this.ciphertext,
    required this.nonce,
    required this.tag,
  });

  /// Convenience: ciphertext concatenated with tag, ready to base64-encode.
  Uint8List get cipherAndTag {
    final out = Uint8List(ciphertext.length + tag.length);
    out.setRange(0, ciphertext.length, ciphertext);
    out.setRange(ciphertext.length, out.length, tag);
    return out;
  }
}

class AeadService {
  AeadService._();

  static const int nonceBytes = 12;
  static const int tagBytes = 16;
  static const int keyBytes = 32;

  static final AesGcm _algo = AesGcm.with256bits();

  /// Encrypt with AES-GCM-256. Generates a fresh 96-bit random nonce.
  ///
  /// `aad` is authenticated but not encrypted — bind contextual metadata
  /// (version, alias, kdf params) to prevent silent downgrade.
  static Future<AeadResult> encryptGcm({
    required Uint8List key,
    required Uint8List plaintext,
    required Uint8List aad,
  }) async {
    if (key.length != keyBytes) {
      throw ArgumentError('AES-GCM-256 key must be $keyBytes bytes');
    }
    final nonce = _randomBytes(nonceBytes);
    final box = await _algo.encrypt(
      plaintext,
      secretKey: SecretKey(key),
      nonce: nonce,
      aad: aad,
    );
    return AeadResult(
      ciphertext: Uint8List.fromList(box.cipherText),
      nonce: Uint8List.fromList(box.nonce),
      tag: Uint8List.fromList(box.mac.bytes),
    );
  }

  /// Decrypt with AES-GCM-256. Returns `null` if the GCM tag check fails
  /// (wrong key, tampered ciphertext, AAD mismatch, wrong nonce).
  ///
  /// Never logs the plaintext or the key. Errors are swallowed and converted
  /// to `null` so callers can't leak the failure reason via timing/log diff.
  static Future<Uint8List?> decryptGcm({
    required Uint8List key,
    required Uint8List nonce,
    required Uint8List ciphertext,
    required Uint8List tag,
    required Uint8List aad,
  }) async {
    if (key.length != keyBytes) return null;
    if (nonce.length != nonceBytes) return null;
    if (tag.length != tagBytes) return null;
    try {
      final box = SecretBox(ciphertext, nonce: nonce, mac: Mac(tag));
      final plain = await _algo.decrypt(
        box,
        secretKey: SecretKey(key),
        aad: aad,
      );
      return Uint8List.fromList(plain);
    } catch (_) {
      // SecretBoxAuthenticationError or any other failure: fail closed.
      return null;
    }
  }

  /// Helper to split a `cipherAndTag` blob (ciphertext || tag) into its
  /// two parts. Format used in vault v4 cipher.data field.
  static ({Uint8List ciphertext, Uint8List tag}) splitCipherAndTag(
    Uint8List cipherAndTag,
  ) {
    if (cipherAndTag.length < tagBytes) {
      throw ArgumentError('cipherAndTag shorter than tag length');
    }
    final ctLen = cipherAndTag.length - tagBytes;
    return (
      ciphertext: Uint8List.fromList(cipherAndTag.sublist(0, ctLen)),
      tag: Uint8List.fromList(cipherAndTag.sublist(ctLen)),
    );
  }

  static Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    final out = Uint8List(n);
    for (var i = 0; i < n; i++) {
      out[i] = rng.nextInt(256);
    }
    return out;
  }
}
