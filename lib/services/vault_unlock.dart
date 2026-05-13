// Workflow de déverrouillage (passphrase, biométrique, dummy timing).
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - `passwordMatchesPrimary` (vérification isolée pour la création de decoy),
//  - `_tryUnlockSlot` (path v3 + path v4, anti-timing avec dummy Argon2id),
//  - `_v4Unlock` (Argon2id + KEK unwrap + AES-GCM decrypt),
//  - `unlockWithBiometric` (cache `_key` 32B + AES-GCM decrypt direct).
//
// v2.2.0 : `_decryptVaultV4` (vault_crypto) est désormais pur. Les méthodes
// ici assignent `_entries` / `_isOpen` localement après succès. Le path v3
// (legacy) est encore muteur, donc `passwordMatchesPrimary` snapshot/restore
// uniquement pour ce path.

part of 'vault_service.dart';

extension VaultUnlock on VaultService {
  /// True si [password] déverrouille le coffre primary. Utilisé pour vérifier
  /// que le password du leurre diffère bien du password du primary AVANT
  /// la création. Ne touche pas à _key / _entries (ne déverrouille pas
  /// vraiment l'app — le test est isolé puis nettoyé).
  Future<bool> passwordMatchesPrimary(String password) async {
    try {
      final file = await _vaultFileFor(_Slot.primary);
      if (!await file.exists()) return false;
      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      // v2.2.0 : `_decryptVaultV4` est désormais pur — il ne touche plus
      // `_entries` / `_isOpen`, donc plus besoin de snapshot/restore pour le
      // path v4. Le path v3 (legacy, read-only) mute encore : on garde le
      // snapshot pour ce cas. Le snapshot de `_key` reste utile car v3 dérive
      // une clé PBKDF2 que l'on doit wipe et restaurer.
      final savedKey = _key == null ? null : Uint8List.fromList(_key!);
      final savedEntries = List<Entry>.from(_entries);
      final savedOpen = _isOpen;
      final savedSlot = _activeSlot;
      try {
        if (version >= VaultService._currentVersion) {
          final fk = await _v4Unlock(
            slot: _Slot.primary,
            password: password,
            raw: raw,
          );
          if (fk == null) return false;
          SecretBytes.wipe(fk);
          return true;
        }
        // v3 path — derive PBKDF2 then attempt MAC check.
        final saltB64 = await VaultService._storage.read(
          key: _saltKeyFor(_Slot.primary),
        );
        if (saltB64 == null) return false;
        final salt = base64Decode(saltB64);
        final iter =
            raw['iterations'] as int? ?? VaultService._legacyIterations;
        if (iter < 1 || iter > VaultService._maxIterations) return false;
        _key = await VaultService._deriveKey(password, salt, iter);
        final ok = _decryptVaultV3(raw);
        return ok;
      } finally {
        _wipeKey();
        _key = savedKey;
        _entries = savedEntries;
        _isOpen = savedOpen;
        _activeSlot = savedSlot;
      }
    } catch (_) {
      return false;
    }
  }

  /// Tente de déverrouiller un slot précis. Retourne success ou wrongPassword.
  ///
  /// v3 path : if `version <= 3`, derive PBKDF2 key, decrypt with v3, then
  /// trigger automatic migration to v4 (atomic — backup `.bak` is created
  /// before the v3 file is overwritten).
  /// v4 path : Argon2id + Keystore unwrap + AES-GCM decrypt.
  Future<UnlockResult> _tryUnlockSlot(_Slot slot, String masterPassword) async {
    try {
      final file = await _vaultFileFor(slot);
      if (!await file.exists()) {
        // Anti-timing : si le slot (notamment decoy) n'existe pas, on doit
        // tout de même consommer ~1 Argon2id pour que le timing total de
        // unlock() soit identique au cas où le slot existe. Sinon, un
        // attaquant qui chronomètre l'unlock peut déduire l'absence du
        // coffre leurre — ce qui briserait le déni plausible.
        // Mêmes paramètres (m=19 MiB, t=2, p=1) que les vrais slots.
        // P2-fix v2.3.2 : password constant, indépendant du masterPassword.
        // Évite le timing oracle marginal sur passwords très courts (le coût
        // Argon2id varie linéairement avec la longueur du password). Le but
        // ici est juste de consommer ~1 Argon2id, pas de hasher quelque chose.
        final dummySalt = SecretBytes.randomBytes(32);
        final dummyOut = await KdfService.argon2id(
          password: 'pt_dummy_noop_v2',
          salt: dummySalt,
        );
        SecretBytes.wipe(dummyOut);
        return UnlockResult.wrongPassword;
      }

      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      if (version >= VaultService._currentVersion) {
        // ── v4 path ──
        final fk = await _v4Unlock(
          slot: slot,
          password: masterPassword,
          raw: raw,
        );
        if (fk == null) {
          _wipeKey();
          return UnlockResult.wrongPassword;
        }
        _wipeKey();
        _key = fk;
        _activeSlot = slot;
        await _onUnlockSuccess();
        return UnlockResult.success;
      }

      if (version > VaultService._v3Version || version < 1) {
        // Forged version (e.g. 99999) or invalid : refuse.
        return UnlockResult.wrongPassword;
      }

      // ── v3 path : decrypt then migrate ──
      final saltKey = _saltKeyFor(slot);
      final saltB64 = await VaultService._storage.read(key: saltKey);
      if (saltB64 == null) return UnlockResult.wrongPassword;
      final salt = base64Decode(saltB64);

      final stored = raw['iterations'] as int?;
      int iterations;
      if (stored == null) {
        iterations = VaultService._legacyIterations;
      } else {
        if (stored < 1 || stored > VaultService._maxIterations) {
          return UnlockResult.wrongPassword;
        }
        iterations = stored;
      }

      _key = await VaultService._deriveKey(masterPassword, salt, iterations);
      final ok = _decryptVaultV3(raw);
      if (!ok) {
        _wipeKey();
        return UnlockResult.wrongPassword;
      }
      // Active slot must be set before any save (migration writes the v4 file
      // into the active slot).
      _activeSlot = slot;
      await _onUnlockSuccess();

      // Migrate v3 → v4. _migrateV3ToV4 wipes the v3 64-byte key and replaces
      // it with the new 32-byte v4 finalKey. Biometric pre-v4 cache is
      // invalidated below — user must re-enrol after first v4 unlock.
      final migrated = await _migrateV3ToV4(
        slot: slot,
        password: masterPassword,
      );
      if (!migrated) {
        // The v3 read succeeded so entries are in memory — but persisting v4
        // failed. Fail closed : lock and ask user to retry.
        _wipeKey();
        _entries = [];
        _isOpen = false;
        _activeSlot = null;
        return UnlockResult.wrongPassword;
      }

      // Old v3 biometric cache is now useless (different key shape).
      if (slot == _Slot.primary) {
        await deleteBiometricKey();
      }
      return UnlockResult.success;
    } catch (_) {
      _wipeKey();
      return UnlockResult.wrongPassword;
    }
  }

  /// Try to unlock a v4 vault for `slot`. Returns the recovered finalKey on
  /// success, or `null` on failure. v2.2.0 : `_decryptVaultV4` est pur, donc
  /// cette méthode mute désormais `_entries` et `_isOpen` directement après
  /// succès — comportement attendu par les appelants (`_tryUnlockSlot`,
  /// `unlock`). Caller wipes the returned key after use.
  Future<Uint8List?> _v4Unlock({
    required _Slot slot,
    required String password,
    required Map<String, dynamic> raw,
  }) async {
    final kdf = raw['kdf'];
    final kek = raw['kek'];
    if (kdf is! Map || kek is! Map) return null;
    final salt = base64Decode(kdf['salt'] as String);
    final wrappedDek = base64Decode(kek['wrappedDek'] as String);
    final wrapNonce = base64Decode(kek['wrapNonce'] as String);
    final alias = kek['alias'] as String;

    // A3 v2.3.8 — défense en profondeur : refuse les blobs cross-slot.
    // Avant : un attaquant root copiant pt_vault_decoy.enc sur le chemin
    // pt_vault.enc faisait quand même tourner un unwrap KEK decoy (la
    // protection AEAD finale neutralisait l'attaque mais on consommait
    // un appel TEE → side channel mineur). Maintenant : refuse immédiat
    // si alias ne match pas le slot tenté.
    if (alias != _aliasFor(slot)) return null;

    Uint8List? pwHash;
    Uint8List? hwSecret;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(password: password, salt: salt);
      try {
        hwSecret = await _keystore.unwrap(alias, wrappedDek, wrapNonce);
      } catch (_) {
        return null;
      }
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );
      final entries = await _decryptVaultV4(raw, finalKey);
      if (entries == null) {
        SecretBytes.wipe(finalKey);
        return null;
      }
      // Apply side effects only on success — `_decryptVaultV4` est pur depuis
      // v2.2.0, l'assignation revient à l'appelant.
      _entries = entries;
      _isOpen = true;
      // QW2 v2.4.0 — peuple le cache méta : prochain `_saveVault` skip
      // la re-lecture du fichier (gain ~30-50 ms/CRUD).
      _cachedSalt = Uint8List.fromList(salt);
      _cachedWrappedDek = Uint8List.fromList(wrappedDek);
      _cachedWrapNonce = Uint8List.fromList(wrapNonce);
      // Caller takes ownership of the buffer.
      final out = Uint8List.fromList(finalKey);
      SecretBytes.wipe(finalKey);
      return out;
    } finally {
      if (pwHash != null) SecretBytes.wipe(pwHash);
      if (hwSecret != null) SecretBytes.wipe(hwSecret);
    }
  }

  /// Déverrouillage par biométrique : la clé 32B v4 est cachée dans
  /// biometric_storage et utilisée directement (pas d'Argon2id ni d'unwrap KEK).
  /// Le tag GCM lié à l'AAD garantit fail-closed si la clé ne correspond pas.
  Future<UnlockResult> unlockWithBiometric() async {
    // P1-27 v2.4.0 — mutex `_unlockGate` étendu à la bio. Avant : un user
    // qui tape password puis fingerprint avant que le 1er unlock complète
    // déclenchait 2 paths en parallèle (`_key`, `_entries` mutables) →
    // corruption possible. Refus immédiat si un unlock concurrent tourne.
    if (_unlockGate != null) {
      return UnlockResult.wrongPassword;
    }
    final gate = _unlockGate = Completer<void>();
    try {
      return await _unlockWithBiometricInternal();
    } finally {
      if (!gate.isCompleted) gate.complete();
      _unlockGate = null;
    }
  }

  Future<UnlockResult> _unlockWithBiometricInternal() async {
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    try {
      final store = await _bioStorage();
      final keyB64 = await store.read();
      if (keyB64 == null || keyB64.isEmpty) return UnlockResult.wrongPassword;

      // La biométrique est volontairement liée au coffre PRIMARY uniquement.
      // Permettre la bio sur le coffre leurre briserait le déni plausible.
      // P2-2 (v2.2.0) : on vérifie l'existence du fichier AVANT de poser la
      // clé en mémoire pour éviter de la wipe immédiatement après.
      final file = await _vaultFileFor(_Slot.primary);
      if (!await file.exists()) return UnlockResult.wrongPassword;

      // Wipe l'éventuelle clé résiduelle d'une session précédente AVANT
      // d'écrire la nouvelle, pour éviter une fuite mémoire transitoire.
      _wipeKey();
      _key = base64Decode(keyB64);
      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      bool ok;
      if (version >= VaultService._currentVersion) {
        // v4 : the cached _key IS the 32-byte finalKey, no Argon2id / KEK
        // unwrap needed. The GCM tag binds the AAD so wrong-key fails closed.
        if (_key == null || _key!.length != 32) {
          ok = false;
        } else {
          // v2.2.0 : `_decryptVaultV4` est pur. On assigne ici en cas de succès.
          final entries = await _decryptVaultV4(raw, _key!);
          if (entries == null) {
            ok = false;
          } else {
            _entries = entries;
            _isOpen = true;
            ok = true;
          }
        }
      } else {
        ok = _decryptVaultV3(raw);
      }

      if (ok) {
        _activeSlot = _Slot.primary;
        await _onUnlockSuccess();
        // If the stored key was a v3 64-byte cache, force re-enrol after the
        // upcoming v4 migration trip — but we only reach here if the vault is
        // already v4 (v3 unlock-with-bio still works as a safety net for the
        // first unlock after upgrade; the bio cache will be wiped on
        // _migrateV3ToV4 path triggered by password unlock).
        return UnlockResult.success;
      }
      _wipeKey();
      return UnlockResult.wrongPassword;
    } on AuthException catch (e) {
      // (v2.4.2) Discrimine les modes d'échec de biometric_storage v5.0.1
      // (enum AuthExceptionCode : userCanceled, canceled, unknown, timeout,
      //  linuxAppArmorDenied) :
      //  - userCanceled : l'utilisateur a tapé Annuler / back. Pas de
      //    cleanup, fallback silencieux vers le master password.
      //  - canceled    : annulation par l'OS (app switch, lock écran).
      //    Pas de cleanup non plus.
      //  - timeout     : le prompt a expiré sans interaction. Idem.
      //  - autre (typiquement `unknown` quand la clé Keystore a été
      //    invalidée par un ré-enrôlement d'empreinte) : auto-cleanup du
      //    wrap + `biometricInvalidated` pour que l'UI affiche un message
      //    clair plutôt que « biométrie invalide » générique sans
      //    indication de marche à suivre.
      _wipeKey();
      switch (e.code) {
        case AuthExceptionCode.userCanceled:
        case AuthExceptionCode.canceled:
        case AuthExceptionCode.timeout:
          return UnlockResult.wrongPassword;
        default:
          // Cleanup best-effort — la clé Keystore est probablement morte,
          // tenter de la réutiliser sur la prochaine tentative donnerait
          // la même erreur. On supprime le flag + le storage entry pour
          // que le bouton biométrie disparaisse au prochain build du
          // unlock screen.
          try {
            await deleteBiometricKey();
          } catch (_) {
            // ignore — le caller verra biometricInvalidated et invitera
            // l'utilisateur à réactiver depuis Réglages.
          }
          return UnlockResult.biometricInvalidated;
      }
    } catch (_) {
      _wipeKey();
      return UnlockResult.wrongPassword;
    }
  }
}
