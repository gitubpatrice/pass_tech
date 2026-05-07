// Crypto primitives utilisés par VaultService.
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - construction d'AAD v4,
//  - dérivation HKDF v4 (finalKey),
//  - déchiffrement v3 (legacy) et v4 (in-place),
//  - helpers de zéroïsation, RNG et comparaison constant-time (statics).
//
// Les méthodes de déchiffrement v3/v4 mutent `_entries` / `_isOpen` (legacy
// pattern). Le splitter ne corrige PAS ce comportement (TODO v2.2). Voir le
// commentaire au-dessus de [_decryptVaultV4] pour le détail.

part of 'vault_service.dart';

extension VaultCrypto on VaultService {
  /// Build the AAD string bound to a v4 vault. The exact bytes are part of
  /// the GCM tag — encrypt and decrypt MUST agree byte-for-byte.
  Uint8List _aadV4(String alias) => Uint8List.fromList(
    utf8.encode(
      'pt:v=4|alias=$alias|kdf=argon2id|m=${VaultService._argon2M}|t=${VaultService._argon2T}|p=${VaultService._argon2P}',
    ),
  );

  /// HKDF-SHA256(salt, ikm = pwHash || hwSecret, info = "pt:v4", L = 32).
  /// The `cryptography` package's Hkdf primitive runs synchronously fast on
  /// 64 bytes of IKM — no isolate needed.
  Future<Uint8List> _hkdfFinalKey({
    required Uint8List salt,
    required Uint8List pwHash,
    required Uint8List hwSecret,
  }) async {
    final ikm = Uint8List(pwHash.length + hwSecret.length);
    ikm.setRange(0, pwHash.length, pwHash);
    ikm.setRange(pwHash.length, ikm.length, hwSecret);
    try {
      final hkdf = cg.Hkdf(hmac: cg.Hmac.sha256(), outputLength: 32);
      final out = await hkdf.deriveKey(
        secretKey: cg.SecretKey(ikm),
        nonce: salt,
        info: utf8.encode('pt:v4'),
      );
      final bytes = await out.extractBytes();
      return Uint8List.fromList(bytes);
    } finally {
      VaultService._zero(ikm);
    }
  }

  /// Legacy v1/v2/v3 decryption (PBKDF2 + AES-CBC + HMAC-SHA256).
  /// Kept read-only for the migration window.
  ///
  /// Requires `_key` to be the 64-byte v3 derived key. Returns false on any
  /// error (corrupted file, wrong password, MAC mismatch, version > v3).
  bool _decryptVaultV3(Map<String, dynamic>? raw) {
    try {
      if (raw == null) {
        _entries = [];
        _isOpen = true;
        return true;
      }

      final ivBytes = base64Decode(raw['iv'] as String);
      final macBytes = base64Decode(raw['mac'] as String);
      final cipherBytes = base64Decode(raw['data'] as String);
      final version = raw['version'] as int? ?? 1;
      // _decryptVaultV3 ne traite que v1/v2/v3. v4+ doit passer par
      // _decryptVaultV4. La borne stricte protège contre les fichiers forgés
      // (ex. version 99999) qui pourraient déclencher des branches non
      // auditées au fil de futurs refactors.
      if (version < 1 || version > VaultService._v3Version) return false;
      final iterations =
          raw['iterations'] as int? ?? VaultService._legacyIterations;
      final saltB64 = raw['salt'] as String? ?? '';

      // Verify HMAC — constant-time comparison
      // v3+ : HMAC over (version || iterations || salt || IV || ciphertext)
      // v1/v2 : HMAC over (IV || ciphertext) only
      // M-3 : sublist() crée une COPIE des octets de la clé. Sans wipe explicite
      // de ces copies, _wipeKey() (qui n'agit que sur _key) laisse des bouts
      // de la clé maître en RAM jusqu'au GC. On zéroïse activement encKeyBytes
      // et macKey en finally.
      final macKey = _key!.sublist(32);
      final encKeyBytes = _key!.sublist(0, 32);
      try {
        final List<int> macInput;
        if (version >= 3) {
          final aad = utf8.encode(
            'pt:v=$version|iter=$iterations|salt=$saltB64',
          );
          macInput = [...aad, ...ivBytes, ...cipherBytes];
        } else {
          macInput = [...ivBytes, ...cipherBytes];
        }
        final computed = Hmac(sha256, macKey).convert(macInput).bytes;
        if (!VaultService._constEq(computed, macBytes)) return false;

        final encKey = enc.Key(encKeyBytes);
        final iv = enc.IV(Uint8List.fromList(ivBytes));
        final encrypter = enc.Encrypter(enc.AES(encKey, mode: enc.AESMode.cbc));
        final plain = encrypter.decrypt(
          enc.Encrypted(Uint8List.fromList(cipherBytes)),
          iv: iv,
        );

        final list = jsonDecode(plain) as List;
        _entries = list
            .map((e) => Entry.fromJson(e as Map<String, dynamic>))
            .toList();
        _isOpen = true;
        return true;
      } finally {
        VaultService._zero(macKey);
        VaultService._zero(encKeyBytes);
      }
    } catch (_) {
      return false;
    }
  }

  /// Decrypt a v4 vault map. On success, populates `_entries` and returns
  /// `true`. On any failure (wrong key, bad tag, corrupt JSON), returns
  /// `false` and leaves `_entries` untouched. The `finalKey` argument is
  /// the 32-byte HKDF output; caller is responsible for wiping it after.
  ///
  /// TODO (v2.2): refactor _decryptVaultV4 to be pure (non-mutating).
  /// Aujourd'hui la méthode mute `_entries` et `_isOpen` directement, ce qui
  /// couple le déchiffrement au state global. Plusieurs appelants
  /// (`_v4Unlock`, `unlockWithBiometric`, `passwordMatchesPrimary`) en
  /// dépendent et doivent restaurer l'état autour de l'appel — fragile mais
  /// non exploitable (Dart est single-isolate, pas de course concurrente).
  /// Refacto à faire dans un patch dédié : retourner `List<Entry>?` et
  /// laisser les appelants assigner `_entries` / `_isOpen` après succès.
  Future<bool> _decryptVaultV4(
    Map<String, dynamic> raw,
    Uint8List finalKey,
  ) async {
    try {
      if (raw['magic'] != VaultService._vaultMagic) return false;
      if (raw['version'] != VaultService._currentVersion) return false;
      final kek = raw['kek'];
      final cipher = raw['cipher'];
      if (kek is! Map || cipher is! Map) return false;
      final alias = kek['alias'] as String?;
      if (alias != KeystoreAliases.primary && alias != KeystoreAliases.decoy) {
        return false;
      }
      final nonce = base64Decode(cipher['nonce'] as String);
      final dataBlob = base64Decode(cipher['data'] as String);
      final split = AeadService.splitCipherAndTag(dataBlob);

      final aad = _aadV4(alias!);
      final pt = await AeadService.decryptGcm(
        key: finalKey,
        nonce: nonce,
        ciphertext: split.ciphertext,
        tag: split.tag,
        aad: aad,
      );
      if (pt == null) return false;
      final list = jsonDecode(utf8.decode(pt)) as List;
      _entries = list
          .map((e) => Entry.fromJson(e as Map<String, dynamic>))
          .toList();
      _isOpen = true;
      return true;
    } catch (_) {
      return false;
    }
  }
}
