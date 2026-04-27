import 'dart:typed_data';
import 'package:crypto/crypto.dart';

/// RFC 6238 TOTP-SHA1 / 30-second period / 6 digits.
class TotpService {
  static const _stepSeconds = 30;
  static const _digits      = 6;

  /// Returns formatted code "123 456" or "------" on invalid secret.
  static String generateCode(String secret) {
    try {
      final key = _decodeBase32(secret);
      if (key.isEmpty) return '------';
      final time    = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      final counter = time ~/ _stepSeconds;
      final cb = Uint8List(8);
      var c = counter;
      for (int i = 7; i >= 0; i--) {
        cb[i] = c & 0xFF;
        c >>= 8;
      }
      final h = Hmac(sha1, key).convert(cb).bytes;
      final offset = h[h.length - 1] & 0x0F;
      final code = ((h[offset] & 0x7F) << 24)
                 | ((h[offset + 1] & 0xFF) << 16)
                 | ((h[offset + 2] & 0xFF) << 8)
                 |  (h[offset + 3] & 0xFF);
      final mod = _powInt(10, _digits);
      final otp = (code % mod).toString().padLeft(_digits, '0');
      return '${otp.substring(0, 3)} ${otp.substring(3)}';
    } catch (_) {
      return '------';
    }
  }

  /// Seconds remaining until the next code rotation (1..30).
  static int secondsRemaining() {
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final r = _stepSeconds - (now % _stepSeconds);
    return r == 0 ? _stepSeconds : r;
  }

  /// Returns null if the input is a valid Base32 TOTP secret, else an error message.
  static String? validate(String secret) {
    final cleaned = secret.toUpperCase().replaceAll(RegExp(r'\s'), '');
    if (cleaned.isEmpty) return 'Secret vide';
    if (!RegExp(r'^[A-Z2-7=]+$').hasMatch(cleaned)) {
      return 'Caractères invalides (Base32 attendu)';
    }
    final bytes = _decodeBase32(secret);
    if (bytes.length < 10) return 'Secret trop court';
    return null;
  }

  static int _powInt(int base, int exp) {
    int r = 1;
    for (int i = 0; i < exp; i++) { r *= base; }
    return r;
  }

  static Uint8List _decodeBase32(String input) {
    final clean = input.toUpperCase().replaceAll(RegExp(r'[^A-Z2-7]'), '');
    final out = <int>[];
    int buffer = 0;
    int bitsLeft = 0;
    for (int i = 0; i < clean.length; i++) {
      final c = clean.codeUnitAt(i);
      int val;
      if (c >= 0x41 && c <= 0x5A) {
        val = c - 0x41; // A-Z = 0..25
      } else if (c >= 0x32 && c <= 0x37) {
        val = c - 0x32 + 26; // 2-7 = 26..31
      } else {
        continue;
      }
      buffer = (buffer << 5) | val;
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        bitsLeft -= 8;
        out.add((buffer >> bitsLeft) & 0xFF);
      }
    }
    return Uint8List.fromList(out);
  }
}
