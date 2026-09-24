// Two-factor code vectors produced by Pass Tech 2.7.1's own TotpService, for the Kotlin port.
//
// Not part of the Kotlin build: it runs inside a checkout of the Flutter app at 2.7.1.
//
//   cp tools/compat/totp_vectors_test.dart <flutter-checkout>/test/
//   cd <flutter-checkout>
//   PT_COMPAT_DIR=<kotlin-checkout>/app/src/test/resources/compat/2.7.1 \
//     flutter test test/totp_vectors_test.dart
//
// `generateCode` reads the wall clock and takes no time argument, so each code is recorded with the
// second it was computed in, read before and after: a pair that straddles a 30-second step is
// computed again.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/totp_service.dart';

const secrets = [
  'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
  'gezd gnbv gy3t qojq gezd gnbv gy3t qojq====',
  'JBSWY3DPEHPK3PXP',
  'JBSWY3DPEHPK3PX',
  'JBSWY3DPEHPK3PXP1',
  'JBSW-Y3DP-EHPK-3PXP-JBSW',
  'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',
  'A',
  '',
  '   ',
  '1890!',
  'ABC1',
  'ABCD EFGH',
  'GEZDGNBVGY3TQOJQ',
  'GEZDGNBVGY3TQOJ',
  '====',
  'MZXW6YTBOI======',
  'MZXW6YTBOIMZXW6YTBOI',
  'éàGEZDGNBVGY3TQOJQ',
  'GEZDGNBVGY3TQOJQ\n',
];

int nowSeconds() => DateTime.now().millisecondsSinceEpoch ~/ 1000;

void main() {
  test('generate', () {
    final dir = Platform.environment['PT_COMPAT_DIR'];
    expect(dir, isNotNull, reason: 'set PT_COMPAT_DIR');
    final out = <Map<String, Object?>>[];
    for (final secret in secrets) {
      while (true) {
        final before = nowSeconds();
        final code = TotpService.generateCode(secret);
        final after = nowSeconds();
        if (before ~/ 30 != after ~/ 30) continue;
        final error = TotpService.validate(secret);
        out.add({'secret': secret, 'time': before, 'code': code, 'validate': error?.name});
        break;
      }
    }
    File('$dir/totp.json').writeAsStringSync(const JsonEncoder.withIndent('  ').convert(out));
  });
}
