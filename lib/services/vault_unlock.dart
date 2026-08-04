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
  ///
  /// F4 v2.4.4 — partage le mutex `_unlockGate` avec `unlock()` et
  /// `unlockWithBiometric()`. Avant : un setup decoy ou heritage déclenché
  /// pendant un unlock en cours (impossible en UI normale mais accessible
  /// via deeplinks / Back rapide) pouvait corrompre `_key`/`_entries`
  /// pendant le snapshot/restore du path v3.
  Future<bool> passwordMatchesPrimary(String password) async {
    // SEC F7 v2.5.2 — Cette fonction est un oracle oui/non sur le mot de passe
    // maître RÉEL. Avant, elle était atteignable depuis une session LEURRE
    // (`_manageHeritage` → « Mettre à jour », sans garde de slot) et ne
    // consultait NI le lockout NI le compteur d'échecs : un adversaire à qui
    // l'on avait remis le mot de passe leurre — la situation exacte pour
    // laquelle le leurre existe — disposait d'un oracle illimité sur le
    // secret principal. La frontière de déni plausible était franchie.
    //
    // Garde 1 : interdire l'appel hors du slot principal. C'est ce qui tue
    // l'oracle : depuis un leurre, on ne peut plus rien apprendre du primary.
    if (_activeSlot != _Slot.primary) return false;

    // Garde 2 : respecter le verrouillage anti-force-brute, comme `unlock()`.
    if (await getLockoutRemaining() != null) return false;

    // NB : on n'incrémente délibérément PAS `_onUnlockFail()` sur non-
    // correspondance, contrairement à ce que suggérait l'audit. Le principal
    // appelant est la création du coffre leurre, qui vérifie que le mot de
    // passe leurre DIFFÈRE du principal : la non-correspondance y est le
    // résultat ATTENDU et souhaitable. Compter ces essais verrouillerait un
    // utilisateur légitime en train de configurer son leurre. La garde de slot
    // ci-dessus supprime déjà la valeur d'oracle ; le comptage n'apporterait
    // qu'un faux positif.
    if (_unlockGate != null) return false;
    final gate = _unlockGate = Completer<void>();
    try {
      return await _passwordMatchesPrimaryInternal(password);
    } finally {
      if (!gate.isCompleted) gate.complete();
      _unlockGate = null;
    }
  }

  /// SEC F10 v2.5.2 — vérifie [password] contre l'emplacement ACTIF.
  ///
  /// Destiné à la réauthentification avant une opération sensible
  /// (changement de mot de passe maître). Contrairement à
  /// [passwordMatchesPrimary], le comptage d'échecs est ici LÉGITIME : une
  /// non-correspondance est un vrai échec d'authentification, pas un résultat
  /// attendu.
  ///
  /// Retourne `false` si le coffre est verrouillé, si aucun coffre n'est
  /// ouvert, ou si un déverrouillage est déjà en cours.
  Future<bool> verifyCurrentPassword(String password) async {
    if (_unlockGate != null) return false;
    final gate = _unlockGate = Completer<void>();
    try {
      return await verifyCurrentPasswordLocked(password);
    } finally {
      if (!gate.isCompleted) gate.complete();
      _unlockGate = null;
    }
  }

  /// Corps de [verifyCurrentPassword] SANS acquisition du mutex.
  ///
  /// SEC-R1 v2.5.2 — `changeMasterPassword` doit tenir `_unlockGate` sur TOUTE
  /// sa durée, vérification comprise. Avant, il appelait
  /// [verifyCurrentPassword], qui relâchait le mutex en sortant, puis lisait
  /// `_activeSlot` et faisait pivoter salt / hwSecret / finalKey SANS le
  /// reprendre. Un `unlock()` concurrent — atteignable par double-appui rapide,
  /// deeplink ou Retour rapide, le même scénario que la garde F4 v2.4.4
  /// documente déjà — pouvait s'intercaler dans cette fenêtre et réassigner
  /// `_activeSlot` / `_key`. La rotation portait alors sur le MAUVAIS
  /// emplacement, sans que rien ne le signale : perte d'accès définitive au
  /// coffre visé.
  ///
  /// L'appelant DOIT détenir `_unlockGate`.
  Future<bool> verifyCurrentPasswordLocked(String password) async {
    if (!_isOpen || _activeSlot == null) return false;
    if (await getLockoutRemaining() != null) return false;
    final ok = await _passwordMatchesPrimaryInternal(
      password,
      slot: _activeSlot!,
    );
    if (!ok) await _onUnlockFail();
    return ok;
  }

  Future<bool> _passwordMatchesPrimaryInternal(
    String password, {
    _Slot slot = _Slot.primary,
  }) async {
    try {
      final file = await _vaultFileFor(slot);
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
      // AUDIT 2026-08-03 (Gemini PT-002) — voir `VaultService._lockGeneration`.
      final genAvant = _lockGeneration;
      // AUDIT 2026-08-03 — le résultat transite par une variable au lieu de
      // sortir par des `return` depuis l'intérieur du `try`. Un `finally` ne
      // peut pas modifier une valeur déjà rendue : sans cela, l'appelant
      // recevait `true` alors que le coffre venait d'être verrouillé sous lui.
      var result = false;
      try {
        if (version >= VaultService._currentVersion) {
          final r = await _v4Unlock(slot: slot, password: password, raw: raw);
          if (r != null) {
            // F3 v2.4.4 — `_v4Unlock` est désormais pur. On wipe tous les
            // buffers retournés ; `r.entries` est juste une `List<Entry>`
            // référencée localement (GC). Pas de mutation du state global.
            SecretBytes.wipe(r.finalKey);
            SecretBytes.wipe(r.salt);
            SecretBytes.wipe(r.wrappedDek);
            SecretBytes.wipe(r.wrapNonce);
            result = true;
          }
        } else {
          // v3 path — derive PBKDF2 then attempt MAC check.
          final saltB64 = await VaultService._storage.read(
            key: _saltKeyFor(slot),
          );
          final iter =
              raw['iterations'] as int? ?? VaultService._legacyIterations;
          if (saltB64 != null &&
              iter >= 1 &&
              iter <= VaultService._maxIterations) {
            final salt = base64Decode(saltB64);
            _key = await VaultService._deriveKey(password, salt, iter);
            result = _decryptVaultV3(raw);
          }
        }
      } finally {
        _wipeKey();
        if (_lockGeneration != genAvant) {
          // Un verrouillage est survenu pendant la vérification — mise en
          // arrière-plan, minuterie d'inactivité, « Verrouiller maintenant »,
          // ou mode panique. Restaurer l'instantané REVIENDRAIT À ANNULER CE
          // VERROUILLAGE et à laisser le coffre déchiffré en mémoire.
          //
          // La décision de l'utilisateur (ou de la panique) prime sur une
          // vérification en cours : on efface l'instantané et on laisse le
          // coffre fermé. L'appelant recevra `false`, ce qu'il traite déjà
          // comme un échec de vérification.
          if (savedKey != null) SecretBytes.wipe(savedKey);
          savedEntries.clear();
          _key = null;
          _entries = [];
          _isOpen = false;
          _activeSlot = null;
        } else {
          _key = savedKey;
          _entries = savedEntries;
          _isOpen = savedOpen;
          _activeSlot = savedSlot;
        }
      }
      // Un verrouillage survenu pendant la vérification invalide le verdict :
      // l'appelant enchaînerait sur une opération sensible (rotation du mot de
      // passe maître, écriture de l'instantané d'héritage) alors que le coffre
      // vient d'être fermé, volontairement ou par la panique.
      if (_lockGeneration != genAvant) return false;
      return result;
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
        // AUDIT 2026-08-03 — corps factorisé dans `_consumeDummyArgon2`
        // (il en existait trois copies).
        await _consumeDummyArgon2();
        return UnlockResult.wrongPassword;
      }

      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      if (version >= VaultService._currentVersion) {
        // ── v4 path ──
        // F3 v2.4.4 — `_v4Unlock` est pur depuis v2.4.4 ; on assigne ici
        // les side effects (state global + cache méta). Ce path n'est plus
        // utilisé par `_unlockInternal` (qui appelle `_v4Unlock` directement
        // et applique le winner après le loop, cf. F3). Il reste utilisé
        // pour les contextes où une seule tentative de slot est faite (rare,
        // appels de tests ou futurs callers).
        final r = await _v4Unlock(
          slot: slot,
          password: masterPassword,
          raw: raw,
        );
        if (r == null) {
          _wipeKey();
          return UnlockResult.wrongPassword;
        }
        _wipeKey();
        _key = r.finalKey;
        _entries = r.entries;
        _isOpen = true;
        _activeSlot = slot;
        // QW2 v2.4.0 — peuple le cache méta : prochain `_saveVault` skip
        // la re-lecture du fichier (gain ~30-50 ms/CRUD). Pour cohérence
        // avec F5 v2.4.3 (wipe du cache à lock), on wipe d'abord l'ancien.
        if (_cachedSalt != null) SecretBytes.wipe(_cachedSalt!);
        if (_cachedWrappedDek != null) SecretBytes.wipe(_cachedWrappedDek!);
        if (_cachedWrapNonce != null) SecretBytes.wipe(_cachedWrapNonce!);
        _cachedSalt = r.salt;
        _cachedWrappedDek = r.wrappedDek;
        _cachedWrapNonce = r.wrapNonce;
        await _onUnlockSuccess();
        // SEC F18 v2.5.4 — voir `_migrateFileLabelIfLegacy`. Ce chemin est
        // secondaire (`_unlockInternal` est le principal) mais doit migrer
        // aussi, sinon un coffre ouvert par ici garderait son étiquette héritée.
        await _migrateFileLabelIfLegacy(slot);
        // AUDIT 2026-08-03 — même raison : ce chemin doit réaligner le
        // rembourrage comme le fait le chemin principal, sinon un coffre ouvert
        // par ici resterait sur un barreau inférieur à celui de son voisin.
        await _realignPaddingIfNeeded(slot);
        return UnlockResult.success;
      }

      if (version > VaultService._v3Version || version < 1) {
        // Forged version (e.g. 99999) or invalid : refuse.
        //
        // SEC 2026-08-04 (audit GPT, point douteux) — précision sur la portée
        // réelle de cette garde, qui prêtait à confusion.
        //
        // Une version SUPÉRIEURE à 4 n'arrive jamais ici : la branche
        // `version >= _currentVersion` ci-dessus l'a déjà captée. Le rejet
        // effectif se fait alors dans `_decryptVaultV4`, qui teste l'égalité
        // STRICTE (`raw['version'] != _currentVersion`) et rend `null`.
        //
        // Ce test-ci ne couvre donc en pratique que `version < 1`. La
        // protection existe bien — à un autre endroit que ne le laissait
        // croire ce commentaire.
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
      // SEC 2026-08-04 (audit GPT F5) — nettoyage COMPLET, pas seulement la
      // cle.
      //
      // Ce chemin publie `_entries` et `_isOpen = true` AVANT d'avoir fini
      // toutes les operations susceptibles de lever : decodage des metadonnees
      // en cache, `_onUnlockSuccess()` qui ecrit en stockage securise. Si l'une
      // echoue, ce `catch` n'effacait que `_key` et rendait `wrongPassword`.
      //
      // Le singleton restait alors dans un etat qui se contredit lui-meme :
      // resultat « mot de passe incorrect », `_isOpen` a vrai, les entrees
      // DECHIFFREES toujours en memoire, et plus aucune cle. Le coffre etait
      // ferme du point de vue de l'appelant et ouvert du point de vue de
      // l'application.
      //
      // Le chemin principal (`_unlockInternal`) possedait deja cette parade
      // complete ; elle n'avait pas ete propagee a ses deux jumeaux.
      _wipeKey();
      _entries = [];
      _isOpen = false;
      _activeSlot = null;
      return UnlockResult.wrongPassword;
    }
  }

  /// Try to unlock a v4 vault for `slot`. Returns a record containing the
  /// recovered finalKey, the decrypted entries, and the cache metadata, or
  /// `null` on failure.
  ///
  /// F3 v2.4.4 — **PURE depuis v2.4.4**. Avant : mutation directe de
  /// `_entries`/`_isOpen`/`_cachedSalt`/`_cachedWrappedDek`/`_cachedWrapNonce`
  /// dès qu'un slot déchiffrait. Conséquence : dans `_unlockInternal` (qui
  /// itère les 2 slots pour le déni plausible anti-timing), si l'utilisateur
  /// avait choisi le MÊME mot de passe pour le coffre primary ET decoy
  /// (config erronée), la 2ᵉ itération écrivait brièvement les entries du
  /// decoy en RAM avant que `_unlockInternal` ne les écrase par le winner.
  /// Une instrumentation pouvait alors lire `_entries` pendant cette fenêtre
  /// ~10ms. Désormais : aucune mutation latente — le caller assigne UNIQUEMENT
  /// pour le slot gagnant, à la fin du loop.
  ///
  /// Caller responsibility :
  ///  - wipe `finalKey` après usage (clé 32B AES-GCM v4).
  ///  - wipe `salt` / `wrappedDek` / `wrapNonce` si non promus dans le cache
  ///    méta (pour les slots perdants du loop déni plausible).
  Future<
    ({
      Uint8List finalKey,
      List<Entry> entries,
      Uint8List salt,
      Uint8List wrappedDek,
      Uint8List wrapNonce,
      KdfParams params,
    })?
  >
  _v4Unlock({
    required _Slot slot,
    required String password,
    required Map<String, dynamic> raw,
  }) async {
    // AUDIT 2026-08-03 — l'en-tête est décodé sous garde.
    //
    // Ces `base64Decode` et ces transtypages étaient placés AVANT le `try`
    // ci-dessous : sur un fichier malformé (champ absent, base64 invalide,
    // type inattendu) ils levaient une exception qui remontait jusqu'à
    // `unlock()`, laquelle ne comporte qu'un `finally`. Deux conséquences :
    //   • côté écran, l'indicateur de progression restait affiché
    //     indéfiniment, sans message ni issue ;
    //   • pire, comme les deux emplacements sont parcourus dans la même
    //     boucle, un LEURRE corrompu faisait échouer l'ouverture du coffre
    //     PRINCIPAL, pourtant parfaitement lisible.
    // Le contrat de cette fonction est déjà « rend `null` en cas d'échec » :
    // on l'honore aussi pour un en-tête illisible.
    final Uint8List salt;
    final Uint8List wrappedDek;
    final Uint8List wrapNonce;
    final String fileLabel;
    final KdfParams params;
    try {
      final kdf = raw['kdf'];
      final kek = raw['kek'];
      if (kdf is! Map || kek is! Map) return null;
      // AUDIT 2026-08-03 — les paramètres Argon2id viennent du FICHIER.
      // Ils y étaient écrits depuis la v4 mais n'avaient jamais été relus :
      // la dérivation prenait les constantes de compilation. Voir
      // `KdfParams.fromFileOrNull` pour ce que cela impliquait le jour où
      // ces constantes bougeraient. `null` = valeurs hors bornes → on refuse.
      final p = KdfParams.fromFileOrNull(kdf);
      if (p == null) return null;
      params = p;
      salt = base64Decode(kdf['salt'] as String);
      wrappedDek = base64Decode(kek['wrappedDek'] as String);
      wrapNonce = base64Decode(kek['wrapNonce'] as String);
      fileLabel = kek['alias'] as String;
    } catch (_) {
      return null;
    }
    // SEC F18 v2.5.4 — `fileLabel` est l'étiquette NEUTRE lue dans le fichier.
    // Elle sert à l'AAD et au contrôle anti-copie, JAMAIS à adresser le
    // Keystore : cet adressage passe par `_aliasFor(slot)`, qui ne quitte
    // jamais la RAM. Confondre les deux réintroduirait le mot « decoy » dans
    // le fichier. (Lu ci-dessus, sous la garde d'en-tête.)

    // A3 v2.3.8 — défense en profondeur : refuse les blobs cross-slot.
    // Avant : un attaquant root copiant pt_vault_decoy.enc sur le chemin
    // pt_vault.enc faisait quand même tourner un unwrap KEK decoy (la
    // protection AEAD finale neutralisait l'attaque mais on consommait
    // un appel TEE → side channel mineur). Maintenant : refuse immédiat
    // si l'étiquette ne correspond pas au slot tenté.
    //
    // L'étiquette de fichier reste DISTINCTE par emplacement précisément pour
    // conserver ce contrôle. On accepte aussi l'ancien alias d'adressage, le
    // temps que les coffres antérieurs à SEC F18 soient réécrits au premier
    // déverrouillage réussi (voir `_needsFileLabelMigration`).
    if (fileLabel != _fileLabelFor(slot) && fileLabel != _aliasFor(slot)) {
      return null;
    }

    Uint8List? pwHash;
    Uint8List? hwSecret;
    Uint8List? finalKey;
    try {
      pwHash = await KdfService.argon2id(
        password: password,
        salt: salt,
        params: params,
      );
      try {
        // SEC F18 v2.5.4 — adressage du Keystore par `_aliasFor(slot)` et NON
        // par l'étiquette lue dans le fichier. C'est ce découplage qui permet
        // de neutraliser l'étiquette écrite sur disque sans renommer la clé
        // matérielle (une clé AndroidKeyStore ne se renomme pas).
        hwSecret = await _keystore.unwrap(
          _aliasFor(slot),
          wrappedDek,
          wrapNonce,
        );
      } catch (_) {
        return null;
      }
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );
      final entries = await _decryptVaultV4(raw, finalKey, params);
      if (entries == null) {
        SecretBytes.wipe(finalKey);
        return null;
      }
      // F10 v2.4.3 — caller prend la clé d'abord (`out` cloné), puis
      // `finalKey` est wipé. Si une exception (OOM, GC) survenait entre
      // l'assignation et le wipe, la clé brute survivait dans `finalKey`
      // jusqu'au GC.
      final out = Uint8List.fromList(finalKey);
      SecretBytes.wipe(finalKey);
      return (
        finalKey: out,
        entries: entries,
        // F3 v2.4.4 — clones de salt/wrappedDek/wrapNonce retournés au
        // caller, qui décidera de les promouvoir dans le cache méta
        // (uniquement pour le slot winner) ou de les wiper.
        salt: Uint8List.fromList(salt),
        wrappedDek: Uint8List.fromList(wrappedDek),
        wrapNonce: Uint8List.fromList(wrapNonce),
        // Les paramètres qui ont produit cette clé. L'appelant DOIT les
        // conserver auprès du sel : toute réécriture du coffre les reporte.
        params: params,
      );
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
    //
    // AUDIT 2026-08-03 — rend `busy` et non plus `wrongPassword` : le cas
    // nominal ici est justement « l'utilisateur a saisi son mot de passe puis
    // posé son doigt », et lui répondre « échec biométrique » était faux.
    if (_unlockGate != null) {
      return UnlockResult.busy;
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
    // SEC 2026-08-04 — dernier chemin long dépourvu de la garde de
    // verrouillage, et paradoxalement le plus exposé des quatre.
    //
    // Les trois autres — ouverture par mot de passe, création d'emplacement,
    // rotation — durent un ou deux Argon2id, soit une à deux secondes. Ici la
    // fenêtre est ouverte tant que l'invite biométrique attend un doigt :
    // plusieurs dizaines de secondes, sans borne. C'est la plus grande fenêtre
    // de toute l'application pour qu'un `lock()` s'intercale, et notamment un
    // MODE PANIQUE déclenché pendant que l'invite est à l'écran.
    //
    // Sans cette garde, le déverrouillage reprenait après la panique et
    // rouvrait le coffre en mémoire. Relevé ici, comparé avant l'application du
    // résultat.
    final genAvantBio = _lockGeneration;
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    try {
      final store = await _bioStorage();
      final keyB64 = await store.read();
      if (keyB64 == null || keyB64.isEmpty) {
        // SEC F20 v2.5.4 — une lecture VIDE alors que la biométrie est
        // marquée active est anormale : l'entrée devrait exister. C'est ce que
        // produit `biometric_storage` sur ce chemin quand la clé Keystore a
        // été invalidée par un réenrôlement d'empreinte — il rend `null`
        // SANS lever d'AuthException (vérifié sur émulateur : le diagnostic
        // posé dans le `catch on AuthException` n'a JAMAIS été atteint).
        //
        // Avant, on retournait `wrongPassword` : l'UI affichait « Échec
        // biométrique », le drapeau `pt_biometric_enabled` survivait, et le
        // bouton « Empreinte / Face ID » restait proposé en échouant
        // indéfiniment. L'utilisateur bouclait sans explication ni marche à
        // suivre.
        try {
          await deleteBiometricKey();
        } catch (_) {}
        return UnlockResult.biometricInvalidated;
      }

      // La biométrique est volontairement liée au coffre PRIMARY uniquement.
      // Permettre la bio sur le coffre leurre briserait le déni plausible.
      // P2-2 (v2.2.0) : on vérifie l'existence du fichier AVANT de poser la
      // clé en mémoire pour éviter de la wipe immédiatement après.
      final file = await _vaultFileFor(_Slot.primary);
      if (!await file.exists()) return UnlockResult.wrongPassword;

      // F2 v2.4.4 — Décode et VALIDE la longueur AVANT de poser `_key`.
      // Avant : `_key = base64Decode(keyB64)` directement, puis check
      // length plus bas. Si le storage avait été corrompu (downgrade,
      // disk error, attaque ciblée), la clé non-32B se retrouvait
      // brièvement en RAM dans `_key` jusqu'à l'invalidation. Le wipe
      // était correct mais inutilement étalé. Désormais : si la longueur
      // diverge, on supprime le wrap (clé manifestement corrompue) et
      // on signale `biometricInvalidated` à l'UI.
      final candidate = base64Decode(keyB64);
      if (candidate.length != 32) {
        SecretBytes.wipe(candidate);
        try {
          await deleteBiometricKey();
        } catch (_) {}
        return UnlockResult.biometricInvalidated;
      }
      // Wipe l'éventuelle clé résiduelle d'une session précédente AVANT
      // d'écrire la nouvelle, pour éviter une fuite mémoire transitoire.
      _wipeKey();
      _key = candidate;
      final raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      final version = raw['version'] as int? ?? 1;

      bool ok;
      if (version >= VaultService._currentVersion) {
        // v4 : the cached _key IS the 32-byte finalKey, no Argon2id / KEK
        // unwrap needed. The GCM tag binds the AAD so wrong-key fails closed.
        // v2.2.0 : `_decryptVaultV4` est pur. On assigne ici en cas de succès.
        //
        // AUDIT 2026-08-03 — les paramètres viennent du fichier ici aussi.
        // Ce chemin ne dérive rien (la clé est déjà en cache), mais il en a
        // besoin pour reconstruire l'AAD à l'identique, sans quoi l'étiquette
        // GCM ne se vérifierait pas.
        final kdf = raw['kdf'];
        final params = kdf is Map ? KdfParams.fromFileOrNull(kdf) : null;
        if (params == null) {
          ok = false;
        } else {
          final entries = await _decryptVaultV4(raw, _key!, params);
          if (entries == null) {
            ok = false;
          } else {
            _entries = entries;
            _isOpen = true;
            // AUDIT 2026-08-03 — le cache méta est peuplé ICI AUSSI.
            //
            // Ce chemin ne le remplissait pas, et c'est ce qui le privait
            // silencieusement de deux traitements de fin de déverrouillage :
            //   • `_migrateFileLabelIfLegacy` (SEC F18), qui renonce quand le
            //     cache est vide — un coffre antérieur à la v2.5.4 gardait donc
            //     le mot « decoy » en clair sur le disque INDÉFINIMENT dès lors
            //     que son propriétaire n'ouvre qu'à l'empreinte, ce qui est
            //     précisément l'usage courant ;
            //   • le réalignement du rembourrage ajouté par cet audit.
            // Chaque modification d'entrée repassait en outre par la relecture
            // complète du fichier, que QW2 v2.4.0 avait justement supprimée.
            _cachedKdfParams = params;
            final kek = raw['kek'];
            if (kek is Map) {
              final saltB64 = kdf['salt'];
              final wrappedB64 = kek['wrappedDek'];
              final nonceB64 = kek['wrapNonce'];
              if (saltB64 is String &&
                  wrappedB64 is String &&
                  nonceB64 is String) {
                if (_cachedSalt != null) {
                  SecretBytes.wipe(_cachedSalt!);
                }
                if (_cachedWrappedDek != null) {
                  SecretBytes.wipe(_cachedWrappedDek!);
                }
                if (_cachedWrapNonce != null) {
                  SecretBytes.wipe(_cachedWrapNonce!);
                }
                _cachedSalt = base64Decode(saltB64);
                _cachedWrappedDek = base64Decode(wrappedB64);
                _cachedWrapNonce = base64Decode(nonceB64);
              }
            }
            ok = true;
          }
        }
      } else {
        // AUDIT 2026-08-03 — cette branche appelait `_decryptVaultV3(raw)`.
        // Elle ne pouvait PAS aboutir, et le commentaire qui l'accompagnait
        // décrivait un mécanisme inexistant (« filet de sécurité pour le
        // premier déverrouillage après mise à jour »).
        //
        // Démonstration : `_key` vient d'être validée à EXACTEMENT 32 octets
        // une trentaine de lignes plus haut (F2 v2.4.4), tandis que
        // `_decryptVaultV3` exige une clé de 64 octets — c'est la garde
        // SEC F13 v2.5.2, qui existe précisément pour qu'une clé v4 de 32
        // octets ne puisse jamais ouvrir un fichier v3. L'appel rendait donc
        // invariablement `false`.
        //
        // Le vrai filet de sécurité est ailleurs, et il fonctionne : une
        // enveloppe biométrique d'avant la v4 fait 64 octets, elle est donc
        // rejetée par le contrôle de longueur, l'enveloppe est supprimée et
        // l'utilisateur reçoit `biometricInvalidated` avec la marche à suivre.
        ok = false;
      }

      if (ok) {
        // SEC 2026-08-04 — un verrouillage pendant l'invite biométrique prime.
        // `ok` a été calculé avec `_entries` et `_isOpen` déjà posés plus haut ;
        // on les annule ici plutôt que de rendre un succès sur un coffre que
        // l'utilisateur vient de demander à fermer.
        if (_lockGeneration != genAvantBio) {
          _wipeKey();
          _entries = [];
          _isOpen = false;
          _activeSlot = null;
          return UnlockResult.biometricCanceled;
        }
        _activeSlot = _Slot.primary;
        await _onUnlockSuccess();
        // AUDIT 2026-08-03 — mêmes traitements de fin d'ouverture que sur le
        // chemin par mot de passe. Ils manquaient tous les deux ici, ce qui
        // rendait SEC F18 inopérant pour quiconque n'ouvre qu'à l'empreinte.
        await _migrateFileLabelIfLegacy(_Slot.primary);
        await _realignPaddingIfNeeded(_Slot.primary);
        // On n'arrive ici que sur un coffre déjà en v4 (cf. la branche `else`
        // ci-dessus). Un coffre encore en v3 se migre au premier
        // déverrouillage par mot de passe, qui invalide au passage l'enveloppe
        // biométrique — l'utilisateur la réactive ensuite depuis Réglages.
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
          // UX 2026-08-04 — rend `biometricCanceled` et non plus
          // `wrongPassword`. Le « repli silencieux » que ce commentaire
          // promettait depuis la v2.4.2 n'existait pas : la distinction était
          // calculée ici, puis perdue en rendant la valeur d'un vrai échec.
          // L'écran affichait « Échec biométrique » à quelqu'un qui venait
          // simplement de choisir de taper son mot de passe.
          return UnlockResult.biometricCanceled;
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
      // SEC 2026-08-04 (audit GPT F5) — nettoyage COMPLET, pas seulement la
      // cle.
      //
      // Ce chemin publie `_entries` et `_isOpen = true` AVANT d'avoir fini
      // toutes les operations susceptibles de lever : decodage des metadonnees
      // en cache, `_onUnlockSuccess()` qui ecrit en stockage securise. Si l'une
      // echoue, ce `catch` n'effacait que `_key` et rendait `wrongPassword`.
      //
      // Le singleton restait alors dans un etat qui se contredit lui-meme :
      // resultat « mot de passe incorrect », `_isOpen` a vrai, les entrees
      // DECHIFFREES toujours en memoire, et plus aucune cle. Le coffre etait
      // ferme du point de vue de l'appelant et ouvert du point de vue de
      // l'application.
      //
      // Le chemin principal (`_unlockInternal`) possedait deja cette parade
      // complete ; elle n'avait pas ete propagee a ses deux jumeaux.
      _wipeKey();
      _entries = [];
      _isOpen = false;
      _activeSlot = null;
      return UnlockResult.wrongPassword;
    }
  }
}
