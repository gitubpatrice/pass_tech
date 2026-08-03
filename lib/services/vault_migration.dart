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

      await _saveVaultV4(
        slot: slot,
        salt: newSalt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
        // La clé vient d'être redérivée : c'est l'un des trois moments où
        // adopter la recommandation COURANTE est correct (avec la création du
        // coffre et le changement de mot de passe maître).
        params: KdfParams.owaspMobile2024,
      );

      // V1 v2.4.0 — salt en storage écrit APRÈS le save vault réussi (aligné
      // sur `_createSlot` / `changeMasterPassword`). Avant : écrit AVANT le
      // save ; si `_saveVaultV4` throwait (IO), le fichier restait en v3 mais
      // le salt storage passait en v4 → au prochain unlock le path v3 relisait
      // un salt divergent → `wrongPassword` définitif + perte de données (le
      // `.bak` avait lui aussi son salt écrasé). Le path v4 lit son salt depuis
      // le FICHIER (`kdf.salt`, cf. `_v4Unlock`), pas depuis storage : ce write
      // ne sert qu'à rendre obsolète l'ancien salt v3.
      await VaultService._storage.write(
        key: _saltKeyFor(slot),
        value: base64Encode(newSalt),
      );

      // H2 v2.5.x — purge le `.bak` v3 dès la migration réussie. Avant : il ne
      // partait qu'au prochain `changeMasterPassword` / `deleteVault`, laissant
      // une copie complète du coffre chiffrée en PBKDF2+AES-CBC dérivée du SEUL
      // master password (aucune liaison KEK/TEE, KDF plus faible qu'Argon2id) —
      // brute-forçable offline, annulant les deux durcissements majeurs de v4.
      // Le rename atomique de `_saveVaultV4` garantit déjà la durabilité, le
      // `.bak` n'est plus un filet nécessaire une fois le v4 écrit.
      try {
        final src = await _vaultFileFor(slot);
        final bak = File('${src.path}_v3.enc.bak');
        if (await bak.exists()) await bak.delete();
      } catch (_) {
        /* best-effort : purgé sinon au prochain changeMasterPassword */
      }

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
