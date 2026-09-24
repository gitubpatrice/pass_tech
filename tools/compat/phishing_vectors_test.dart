// Anti-phishing verdicts produced by Pass Tech 2.7.1's own AntiPhishingService, for the Kotlin port.
//
// Not part of the Kotlin build: it runs inside a checkout of the Flutter app at 2.7.1.
//
//   cp tools/compat/phishing_vectors_test.dart <flutter-checkout>/test/
//   cd <flutter-checkout>
//   PT_COMPAT_DIR=<kotlin-checkout>/app/src/test/resources/compat/2.7.1 \
//     flutter test test/phishing_vectors_test.dart
//
// `check()` reads the enabled flag from SharedPreferences and the active domain from the
// `com.passtech.pass_tech/antiphishing` method channel; both are faked here, so what is recorded is
// exactly the comparison logic — the normalisation of the entry's URL, the subdomain rule, the
// Levenshtein threshold — and nothing of the accessibility service.
//
// The Kotlin port must give the same verdict on every row EXCEPT the ones it deliberately changes;
// those are listed in its own test, with the 2.7.1 verdict recorded here as the thing being changed.

import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/services/anti_phishing_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// (what the entry stores as its URL, what the browser's address bar is showing).
/// A `null` active domain is what the channel returns when no browser was read.
const cases = <List<String?>>[
  // Plain agreement.
  ['example.com', 'example.com'],
  ['https://example.com/login?next=/account', 'example.com'],
  ['http://example.com', 'example.com'],
  ['www.example.com', 'example.com'],
  ['EXAMPLE.COM', 'example.com'],
  ['example.com:8443', 'example.com'],
  ['https://user:secret@example.com/', 'example.com'],
  ['  example.com  ', 'example.com'],

  // Subdomains: the entry's domain covers what sits under it, not the other way round.
  ['example.com', 'login.example.com'],
  ['example.co.uk', 'www.example.co.uk'],
  ['login.example.com', 'example.com'],
  ['a.example.com', 'b.example.com'],

  // A public suffix is not a registrable domain (SEC F11 of 2.5.2).
  ['victim.github.io', 'attacker.github.io'],
  ['victim.pages.dev', 'attacker.pages.dev'],

  // Typosquatting, at the two distances that are still under the threshold.
  ['paypal.com', 'paypa1.com'],
  ['paypal.com', 'paypall.com'],
  ['example.com', 'exarnple.com'],
  ['example.com', 'example.co'],

  // Plainly another site.
  ['example.com', 'evil.com'],
  ['example.com', 'example.com.evil.com'],
  ['mabanque.fr', 'phishing-site-numero-un.tk'],

  // Nothing to compare against: the entry has no usable URL.
  ['', 'example.com'],
  ['   ', 'example.com'],
  ['not a url', 'example.com'],
  ['localhost', 'localhost'],
  ['example', 'example.com'],

  // No browser was read.
  ['example.com', null],
  ['example.com', ''],

  // Accented domains. The browser's address bar is read in its ASCII form by the accessibility
  // service; what the entry stores is whatever the owner typed.
  ['société.fr', 'xn--socit-esab.fr'],
  ['https://société.fr/compte', 'xn--socit-esab.fr'],
  ['xn--socit-esab.fr', 'xn--socit-esab.fr'],
  ['münchen.de', 'xn--mnchen-3ya.de'],

  // A fully qualified name ends with the root label.
  ['example.com.', 'example.com'],

  // Long enough to reach the 50-character cut of the distance.
  [
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.example.com',
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.example.com',
  ],
];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('anti-phishing verdicts', () async {
    const channel = MethodChannel('com.passtech.pass_tech/antiphishing');
    String? active;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getCurrentDomain') return active;
      return null;
    });
    SharedPreferences.setMockInitialValues({'anti_phishing_enabled': true});

    final service = AntiPhishingService();
    final rows = <Map<String, dynamic>>[];
    for (final pair in cases) {
      active = pair[1];
      final check = await service.check(pair[0]!);
      rows.add({
        'url': pair[0],
        'active': pair[1],
        'verdict': check.verdict.name,
        'expectedDomain': check.expectedDomain,
        'activeDomain': check.activeDomain,
        'distance': check.distance,
      });
    }

    // The flag off: the comparison is never made, whatever the browser shows.
    SharedPreferences.setMockInitialValues({'anti_phishing_enabled': false});
    active = 'evil.com';
    final off = await AntiPhishingService().check('example.com');
    expect(off.verdict, PhishingVerdict.ok);

    final dir = Platform.environment['PT_COMPAT_DIR'];
    expect(dir, isNotNull, reason: 'PT_COMPAT_DIR must point at the Kotlin test resources');
    File('$dir/phishing.json')
        .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(rows));
    stdout.writeln('wrote ${rows.length} rows to $dir/phishing.json');
  });
}
