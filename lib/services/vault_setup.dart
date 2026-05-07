// Création / re-création des slots de coffre.
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - `_createSlot` : génère salt, dérive Argon2id, wrap hwSecret avec KEK,
//    écrit le vault v4 initial,
//  - `changeMasterPassword` : rotate salt + hwSecret + finalKey du slot
//    actif (la KEK reste la même, on re-wrap juste un nouvel hwSecret).
//
// Ces deux opérations partagent la même séquence (salt → Argon2id → wrap →
// HKDF → save) ; les isoler ici allège l'orchestrateur principal.

part of 'vault_service.dart';

extension VaultSetup on VaultService {
  Future<void> _createSlot(_Slot slot, String password) async {
    // v4 : Argon2id + Keystore-bound KEK + AES-GCM.
    // Decision #4: always create both KEK aliases on fresh setup, even if
    // decoy not configured, to keep the keystore profile constant for all
    // users (preserves plausible deniability).
    await _keystore.ensureBothKeksExist();

    final salt = SecretBytes.randomBytes(32);
    await VaultService._storage.write(
      key: _saltKeyFor(slot),
      value: base64Encode(salt),
    );

    // Derive pwHash with Argon2id (isolate).
    final pwHash = await KdfService.argon2id(password: password, salt: salt);

    // Generate hwSecret (32 random bytes), wrap with KEK.
    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? finalKey;
    try {
      final alias = _aliasFor(slot);
      final wrap = await _keystore.wrap(alias, hwSecret);

      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      _key = Uint8List.fromList(finalKey);
      _entries = [];
      _isOpen = true;
      _activeSlot = slot;

      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
      await _onUnlockSuccess();
    } finally {
      SecretBytes.wipe(pwHash);
      SecretBytes.wipe(hwSecret);
      if (finalKey != null) SecretBytes.wipe(finalKey);
    }
  }

  /// Change le master password du slot actif. La KEK Keystore reste la même
  /// (même alias, donc déni plausible préservé), seul le hwSecret est rotaté
  /// et re-wrappé. La biométrique liée au PRIMARY est invalidée si on change
  /// le password du PRIMARY ; conserver la bio sur PRIMARY après changement
  /// du DECOY trahirait son existence.
  Future<void> changeMasterPassword(String newPassword) async {
    // v4 : Argon2id + re-wrap fresh hwSecret. Le slot opposé n'est pas affecté.
    final slot = _activeSlot ?? _Slot.primary;
    final salt = SecretBytes.randomBytes(32);
    await VaultService._storage.write(
      key: _saltKeyFor(slot),
      value: base64Encode(salt),
    );

    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? pwHash;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: newPassword, salt: salt);
      final alias = _aliasFor(slot);
      // KEK reste la même (alias inchangé) — on re-wrap juste un hwSecret neuf.
      final wrap = await _keystore.wrap(alias, hwSecret);
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      _wipeKey();
      _key = Uint8List.fromList(finalKey);

      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
      );
    } finally {
      if (pwHash != null) SecretBytes.wipe(pwHash);
      SecretBytes.wipe(hwSecret);
      if (finalKey != null) SecretBytes.wipe(finalKey);
    }

    // La biométrique est liée au PRIMARY uniquement. Ne la supprimer QUE
    // si on change le password du primary — sinon un changement de password
    // sur le decoy révélerait l'existence du decoy à un attaquant qui
    // remarquerait que la bio fonctionne plus après son intervention.
    if (slot == _Slot.primary) {
      await deleteBiometricKey();
    }
  }
}
