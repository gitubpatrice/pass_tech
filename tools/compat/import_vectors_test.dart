// Import parsing vectors produced by Pass Tech 2.7.1's own ImportExportService, for the Kotlin port.
//
// Not part of the Kotlin build: it runs inside a checkout of the Flutter app at 2.7.1.
//
//   cp tools/compat/import_vectors_test.dart <flutter-checkout>/test/
//   cd <flutter-checkout>
//   PT_COMPAT_DIR=<kotlin-checkout>/app/src/test/resources/compat/2.7.1 \
//     flutter test test/import_vectors_test.dart
//
// Each case records what `ImportExportService.parse` made of one file: the format it recognised, the
// error code if it refused, and every field of every entry it produced. The three generated fields
// (id, createdAt, updatedAt) are left out: they are new on every run by design.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pass_tech/models/entry.dart';
import 'package:pass_tech/services/import_export_service.dart';

const untitled = 'Untitled';

const cases = <String, String>{
  'empty': '',
  'blank': '   \n  \t ',
  'csv_minimal': 'name,username,password\nGitHub,alice,s3cret\n',
  'csv_header_case_and_spaces': ' NAME , Login , PASSWORD , URL \nBank,bob,pw,https://bank.example\n',
  'csv_quotes_and_commas': 'name,password,notes\n"Acme, Inc.",pw,"line one\nline two"\n',
  'csv_escaped_quotes': 'name,password\n"He said ""hi""",pw\n',
  'csv_crlf': 'name,password\r\nSite,pw\r\n',
  'csv_no_password_column': 'name,username\nSite,alice\n',
  'csv_header_only': 'name,password\n',
  'csv_short_row': 'name,password\nonly-one-cell\nSite,pw\n',
  'csv_title_from_url': 'name,url,password\n,https://mail.example.com,pw\n',
  'csv_no_title_no_password': 'name,url,password\n,,\nSite,,pw\n',
  'csv_missing_cells': 'name,username,password,url,notes,totp\nSite,alice,pw\n',
  'csv_alternate_headers': 'title,user,pass,web site,comments,otp\nSite,alice,pw,https://x.example,note,ABCD\n',
  'csv_bitwarden_export_headers':
      'name,login_uri,login_username,login_password,login_totp,notes\n'
      'Social,https://facebook.com,alice,pw,JBSWY3DPEHPK3PXP,hello\n',
  'csv_categories':
      'name,url,password\n'
      'Ma Banque,,pw\n'
      'Gmail,,pw\n'
      'Insta,,pw\n'
      'Something,https://example.com,pw\n'
      'Something else,,pw\n',
  'csv_trailing_spaces': 'name,password\n  Site  ,  pw  \n',
  'json_pass_tech_full':
      '[{"id":"kept-id","type":"card","title":"Visa","category":"Banque","username":"u","password":"p",'
      '"url":"https://x.example","totpSecret":"AB","notes":"n","isFavorite":true,"cardholderName":"A B",'
      '"cardNumber":"4111111111111111","cardExpiry":"12/28","cardCvv":"123","cardPin":"9999",'
      '"cardIssuer":"Visa","createdAt":"2024-01-02T03:04:05.000","updatedAt":"2024-01-02T03:04:05.000"}]',
  'json_pass_tech_minimal': '[{"title":"Bare","category":"Autres"}]',
  'json_pass_tech_missing_title': '[{"category":"Autres"},{"title":"Kept","category":"Autres"}]',
  'json_pass_tech_bad_date': '[{"title":"Bad","category":"Autres","createdAt":"not a date"}]',
  'json_pass_tech_not_maps': '["text",42,{"title":"Kept","category":"Autres"}]',
  'json_pass_tech_unknown_type': '[{"title":"T","category":"Autres","type":"whatever"}]',
  'json_object_unknown': '{"hello":"world"}',
  'json_invalid': '{ not json',
  'bitwarden_login':
      '{"items":[{"type":1,"name":"GitHub","notes":"note","favorite":true,'
      '"login":{"username":"alice","password":"pw","totp":"JBSWY3DPEHPK3PXP",'
      '"uris":[{"uri":"https://github.com"},{"uri":"https://other.example"}]}}]}',
  'bitwarden_login_no_uris': '{"items":[{"type":1,"name":"Bank","login":{"username":"u","password":"p"}}]}',
  'bitwarden_login_empty_uris': '{"items":[{"type":1,"name":"Bank","login":{"username":"u","password":"p","uris":[]}}]}',
  'bitwarden_note': '{"items":[{"type":2,"name":"Note","notes":"body","favorite":false}]}',
  'bitwarden_card':
      '{"items":[{"type":3,"name":"Card","card":{"cardholderName":"A B","number":"4111 1111 1111 1111",'
      '"expMonth":"7","expYear":"2028","code":"123","brand":"Visa"}}]}',
  'bitwarden_card_partial_expiry': '{"items":[{"type":3,"name":"Card","card":{"number":"4111","expMonth":"7"}}]}',
  'bitwarden_card_no_card': '{"items":[{"type":3,"name":"Card"}]}',
  'bitwarden_identity_ignored': '{"items":[{"type":4,"name":"Identity"},{"type":2,"name":"Kept"}]}',
  'bitwarden_no_name': '{"items":[{"type":2}]}',
  'bitwarden_items_not_maps': '{"items":["text",{"type":2,"name":"Kept"}]}',
  'bitwarden_empty_items': '{"items":[]}',
  // Whitespace Dart trims and Java does not, and the byte order mark a Windows editor leaves in front.
  'nbsp_only': '\u00a0\u00a0',
  'json_with_bom': '\ufeff[{"title":"T","category":"Autres"}]',
  'csv_with_bom': '\ufeffname,password\nSite,pw\n',
  'json_with_leading_nbsp': '\u00a0[{"title":"T","category":"Autres"}]',
};

Map<String, dynamic> entryToMap(Entry e) => {
  'type': entryTypeToString(e.type),
  'title': e.title,
  'category': e.category,
  'username': e.username,
  'password': e.password,
  'url': e.url,
  'totpSecret': e.totpSecret,
  'notes': e.notes,
  'isFavorite': e.isFavorite,
  'cardholderName': e.cardholderName,
  'cardNumber': e.cardNumber,
  'cardExpiry': e.cardExpiry,
  'cardCvv': e.cardCvv,
  'cardPin': e.cardPin,
  'cardIssuer': e.cardIssuer,
};

void main() {
  test('import vectors', () {
    final out = <String, dynamic>{};
    cases.forEach((name, content) {
      final result = ImportExportService.parse(content, untitled: untitled);
      out[name] = {
        'input': content,
        'format': result.format,
        'error': result.error?.code.name,
        'entries': result.entries.map(entryToMap).toList(),
      };
    });

    final dir = Platform.environment['PT_COMPAT_DIR'];
    expect(dir, isNotNull, reason: 'set PT_COMPAT_DIR to the Kotlin resources directory');
    final file = File('$dir/import.json');
    file.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(out));
    // ignore: avoid_print
    print('wrote ${file.path}: ${cases.length} cases');
  });
}
