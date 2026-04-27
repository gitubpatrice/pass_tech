import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

/// Privacy-preserving breach check via HaveIBeenPwned k-anonymity API.
/// Only the first 5 chars of the SHA-1 hash leave the device.
class BreachService {
  /// Returns the number of times the password was found in known breaches,
  /// or 0 if not found, or -1 on network error.
  static Future<int> checkPassword(String password) async {
    if (password.isEmpty) return 0;
    try {
      final hash = sha1.convert(utf8.encode(password)).toString().toUpperCase();
      final prefix = hash.substring(0, 5);
      final suffix = hash.substring(5);

      final resp = await http
          .get(
            Uri.parse('https://api.pwnedpasswords.com/range/$prefix'),
            headers: const {
              'User-Agent': 'PassTech',
              'Add-Padding': 'true',
            },
          )
          .timeout(const Duration(seconds: 8));

      if (resp.statusCode != 200) return -1;

      for (final line in resp.body.split('\n')) {
        final parts = line.trim().split(':');
        if (parts.length == 2 && parts[0] == suffix) {
          return int.tryParse(parts[1]) ?? 1;
        }
      }
      return 0;
    } catch (_) {
      return -1;
    }
  }
}
