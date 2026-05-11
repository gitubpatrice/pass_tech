// I/O persistance du vault (atomic write + sérialisation v4).
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - la sauvegarde CRUD courante (`_saveVault`) qui relit kdf+kek depuis le
//    fichier on-disk pour les réutiliser,
//  - la sauvegarde v4 atomique (`_saveVaultV4`) avec écriture tmp + rename.
//
// Aucune crypto n'est dupliquée ici : on délègue à `AeadService` et on lit le
// `_key` (finalKey 32 octets) depuis l'état de VaultService.

part of 'vault_service.dart';

extension VaultStorage on VaultService {
  /// v4 save : preserves existing kdf.salt + kek.wrappedDek / wrapNonce from
  /// the on-disk file. Re-encrypts entries with the current 32-byte finalKey
  /// (`_key`) using AES-GCM, fresh 96-bit nonce, AAD bound to v4 metadata.
  ///
  /// Used by all CRUD operations after the vault is unlocked. The first-time
  /// write (creation / migration) routes through [_saveVaultV4] which takes
  /// the salt + wrapped material as explicit arguments.
  Future<void> _saveVault() async {
    if (_key == null) return;
    final slot = _activeSlot ?? _Slot.primary;

    // Read kdf.salt + kek block from current file. If missing (should never
    // happen post-creation), bail out silently — refusing to write a vault
    // we can't reload is safer than producing an inconsistent file.
    final file = await _vaultFileFor(slot);
    if (!await file.exists()) return;
    Map<String, dynamic> prev;
    try {
      prev = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    final kdf = prev['kdf'];
    final kek = prev['kek'];
    if (kdf is! Map || kek is! Map) return;
    final saltB64 = kdf['salt'] as String?;
    final wrappedB64 = kek['wrappedDek'] as String?;
    final nonceB64 = kek['wrapNonce'] as String?;
    if (saltB64 == null || wrappedB64 == null || nonceB64 == null) return;

    await _saveVaultV4(
      slot: slot,
      salt: base64Decode(saltB64),
      wrappedDek: base64Decode(wrappedB64),
      wrapNonce: base64Decode(nonceB64),
    );
  }

  /// Encrypt and atomically write the vault in v4 format.
  ///
  /// Caller must have populated `_key` with the 32-byte finalKey beforehand.
  /// `salt`, `wrappedDek`, `wrapNonce` are stable across CRUD writes —
  /// generated once at vault creation / migration and reused.
  Future<void> _saveVaultV4({
    required _Slot slot,
    required Uint8List salt,
    required Uint8List wrappedDek,
    required Uint8List wrapNonce,
  }) async {
    if (_key == null || _key!.length != 32) {
      throw StateError('v4 save requires a 32-byte finalKey');
    }
    final alias = _aliasFor(slot);
    final aad = _aadV4(alias);

    final plainText = utf8.encode(
      jsonEncode(_entries.map((e) => e.toJson()).toList()),
    );
    final ptBytes = Uint8List.fromList(plainText);

    final aead = await AeadService.encryptGcm(
      key: _key!,
      plaintext: ptBytes,
      aad: aad,
    );
    // P1-9 v2.4.0 — wipe le plaintext en RAM dès que le ciphertext est
    // obtenu. Avant : `ptBytes` (JSON sérialisé contenant tous les mots
    // de passe en clair) traînait jusqu'au prochain GC. `plainText`
    // (Uint8List vue de utf8.encode) wipée aussi best-effort.
    try {
      ptBytes.fillRange(0, ptBytes.length, 0);
    } catch (_) {
      /* unmodifiable view possible — cf. memory secretbytes_wipe_unmodifiable */
    }
    try {
      plainText.fillRange(0, plainText.length, 0);
    } catch (_) {
      /* best-effort */
    }
    // GCM "data" field = ciphertext || tag, mirrors Android Cipher.doFinal.
    final cipherAndTag = aead.cipherAndTag;

    final out = <String, dynamic>{
      'magic': VaultService._vaultMagic,
      'version': VaultService._currentVersion,
      'kdf': <String, dynamic>{
        'algo': 'argon2id',
        'm': VaultService._argon2M,
        't': VaultService._argon2T,
        'p': VaultService._argon2P,
        'salt': base64Encode(salt),
      },
      'kek': <String, dynamic>{
        'algo': 'AES-GCM-256',
        'alias': alias,
        'wrappedDek': base64Encode(wrappedDek),
        'wrapNonce': base64Encode(wrapNonce),
      },
      'cipher': <String, dynamic>{
        'algo': 'AES-GCM-256',
        'nonce': base64Encode(aead.nonce),
        'data': base64Encode(cipherAndTag),
        // v2.3.2 : champ 'aad' supprimé. L'AAD est recalculée depuis
        // _aadV4(alias) au déchiffrement, jamais lue depuis le JSON. Stocker
        // une copie informationnelle créait une confusion potentielle pour
        // un dev futur qui croirait ce champ autoritaire.
      },
    };

    final target = await _vaultFileFor(slot);
    final tmp = File('${target.path}.tmp');
    await tmp.writeAsString(jsonEncode(out), flush: true);
    // rename atomique : sur POSIX/Android, rename remplace sans étape
    // intermédiaire delete+rename qui pouvait laisser le vault perdu en
    // cas de crash entre les deux opérations.
    await tmp.rename(target.path);
  }
}
