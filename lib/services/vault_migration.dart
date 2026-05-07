// Migration v3 → v4 du vault.
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe la
// méthode `_migrateV3ToV4` qui :
//
//  1. Sauvegarde best-effort le ciphertext v3 sur disque (`*_v3.enc.bak`).
//  2. Génère un nouveau salt 32B + hwSecret 32B.
//  3. S'assure que les 2 alias KEK existent (decision #4 — toujours créer
//     les deux pour préserver le déni plausible).
//  4. Wrap hwSecret avec la KEK du slot.
//  5. Recalcule finalKey via HKDF + écrit le vault v4 (atomic tmp+rename).
//
// Le caller (path v3 de `_tryUnlockSlot`) a déjà vérifié le mot de passe en
// déchiffrant la version v3 ; on prend le password en argument pour redériver
// pwHash via Argon2id avec le NOUVEAU salt v4.

part of 'vault_service.dart';

extension VaultMigration on VaultService {
  /// Migrate a v3 vault (already loaded as `raw`, password verified by the
  /// caller via successful v3 decrypt) to v4 format.
  ///
  /// On any failure, the v3 file is preserved (we never delete it before the
  /// v4 write succeeds — the atomic write `tmp+rename` only overwrites once
  /// the new bytes are durable).
  ///
  /// Wipes pwHash, hwSecret, finalKey before return. Replaces `_key` with
  /// the new v4 finalKey so the caller can keep operating.
  Future<bool> _migrateV3ToV4({
    required _Slot slot,
    required String password,
  }) async {
    // Best-effort backup of v3 ciphertext.
    try {
      final src = await _vaultFileFor(slot);
      if (src.existsSync()) {
        final bak = File('${src.path}_v3.enc.bak');
        // Always overwrite an older bak if a previous migration attempt
        // failed mid-flight: the source is the authoritative v3 file.
        src.copySync(bak.path);
      }
    } catch (_) {
      /* non-fatal */
    }

    await _keystore.ensureBothKeksExist();

    final newSalt = SecretBytes.randomBytes(32);
    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? pwHash;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: password, salt: newSalt);
      final alias = _aliasFor(slot);
      final wrap = await _keystore.wrap(alias, hwSecret);
      finalKey = await _hkdfFinalKey(
        salt: newSalt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      // Update _key to the new v4 finalKey before writing.
      _wipeKey();
      _key = Uint8List.fromList(finalKey);

      // Persist the new salt under the existing _saltKeyFor() entry. The
      // legacy v3 PBKDF2 salt is overwritten — v3 is no longer readable.
      await VaultService._storage.write(
        key: _saltKeyFor(slot),
        value: base64Encode(newSalt),
      );

      await _saveVaultV4(
        slot: slot,
        salt: newSalt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
      return true;
    } catch (_) {
      return false;
    } finally {
      if (pwHash != null) SecretBytes.wipe(pwHash);
      SecretBytes.wipe(hwSecret);
      if (finalKey != null) SecretBytes.wipe(finalKey);
    }
  }
}
