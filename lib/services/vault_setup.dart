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

    // Derive pwHash with Argon2id (isolate).
    final pwHash = await KdfService.argon2id(password: password, salt: salt);

    // Generate hwSecret (32 random bytes), wrap with KEK.
    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? finalKey;
    // SEC 2026-08-04 (audit GPT F3) — instantané de la session AVANT mutation.
    //
    // Jumeau exact du défaut corrigé la veille dans `changeMasterPassword` : la
    // parade avait été posée là-bas et pas ici. Cette fonction remplaçait
    // l'état global — clé, entrées, emplacement actif — AVANT l'écriture, et
    // son `finally` n'effaçait que des tampons.
    //
    // Scénario : on configure un coffre leurre depuis une session PRINCIPALE
    // ouverte. `_createSlot` bascule aussitôt la session sur un leurre vide,
    // puis `_saveVaultV4` échoue — disque plein, erreur d'E/S. L'utilisateur
    // lit « échec », mais le service prétend désormais qu'un AUTRE coffre est
    // ouvert, vide, avec une clé qui ne correspond pas au disque, tandis que
    // les caches décrivent encore le principal. La session principale a été
    // écrasée en mémoire par une opération qui a échoué.
    // SEC 2026-08-04 — relevé avant l'Argon2id, comparé dans le `finally`.
    // Voir le raisonnement au point de comparaison.
    final genAvantCreation = _lockGeneration;
    final sessionKey = _key == null ? null : Uint8List.fromList(_key!);
    final sessionEntries = List<Entry>.from(_entries);
    final sessionOpen = _isOpen;
    final sessionSlot = _activeSlot;
    // SEC 2026-08-04 (relecture Codex) — le cache méta fait partie de la
    // session, et il manquait à l'instantané. C'est le défaut CRITIQUE de la
    // version précédente de ce correctif.
    //
    // `_saveVaultV4` termine par `_cachedSalt/_cachedWrappedDek/`
    // `_cachedWrapNonce/_cachedKdfParams = <métadonnées du slot écrit>`. La
    // restauration ci-dessous ne remettait que la clé, les entrées et
    // l'emplacement actif : le cache continuait de décrire le LEURRE.
    //
    // Chaîne complète de la perte, depuis une session PRINCIPALE ouverte :
    //   1. `setupDecoyVault` → `_createSlot(decoy)` ;
    //   2. `_saveVaultV4` réussit → le cache passe sur le leurre ;
    //   3. l'écriture du sel (ou `_onUnlockSuccess`) lève — stockage sécurisé
    //      indisponible, Keystore saturé ;
    //   4. le `finally` remet la session sur le principal, cache resté leurre ;
    //   5. la moindre modification d'entrée appelle `_saveVault`, dont le
    //      chemin rapide n'exige que quatre valeurs non nulles : il réécrit le
    //      fichier PRINCIPAL en y annonçant le sel et l'enveloppe du LEURRE,
    //      alors que le contenu est chiffré sous la clé du principal ;
    //   6. au déverrouillage suivant, la dérivation repart du sel du leurre et
    //      l'enveloppe est déballée avec la mauvaise KEK : **le coffre
    //      principal ne s'ouvre plus jamais**, avec aucun des deux mots de
    //      passe.
    //
    // Ces quatre champs sont des RÉFÉRENCES, pas des copies : `_saveVaultV4`
    // réaffecte, il ne modifie jamais les tampons en place. L'instantané reste
    // donc valide sans duplication de matériel sensible.
    final cacheSalt = _cachedSalt;
    final cacheWrappedDek = _cachedWrappedDek;
    final cacheWrapNonce = _cachedWrapNonce;
    final cacheParams = _cachedKdfParams;
    var creationCommitted = false;
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
        // Coffre neuf : on adopte la recommandation courante, et le fichier la
        // portera pour toute sa vie.
        params: KdfParams.owaspMobile2024,
      );
      // V1 v2.4.0 — salt en storage écrit APRÈS save vault réussi : un kill
      // mid-flight ne laisse plus de salt orphelin pointant vers un vault
      // chiffré avec une ancienne clé (race path v3 legacy).
      await VaultService._storage.write(
        key: _saltKeyFor(slot),
        value: base64Encode(salt),
      );
      await _onUnlockSuccess();
      // Le coffre est écrit ET son sel persisté : la session en mémoire décrit
      // désormais fidèlement le disque. Plus rien à annuler.
      creationCommitted = true;
    } finally {
      if (!creationCommitted) {
        // Échec en cours de route : on remet la session dans l'état d'avant
        // l'appel, au lieu de laisser un emplacement à demi ouvert.
        //
        // ⚠️ SEC 2026-08-04 — SAUF si un verrouillage est survenu entre-temps.
        //
        // Ce `finally` a été ajouté une heure après le correctif jumeau de
        // `changeMasterPassword`, et reproduisait le défaut que celui-ci
        // corrigeait : `_createSlot` dure un Argon2id complet, pendant lequel
        // `lock()` peut survenir — mise en arrière-plan, minuterie, ou MODE
        // PANIQUE. Restaurer sans condition remettait alors le coffre principal
        // OUVERT en mémoire, APRÈS la panique. La décision de verrouiller doit
        // primer sur la restauration d'une opération qui a échoué.
        //
        // Dans ce cas on efface l'instantané et on laisse le coffre fermé :
        // l'appelant verra l'exception, et l'utilisateur retrouvera son écran
        // de déverrouillage — ce qu'il a demandé.
        if (_lockGeneration != genAvantCreation) {
          if (sessionKey != null) SecretBytes.wipe(sessionKey);
          sessionEntries.clear();
          _wipeKey();
          _entries = [];
          _isOpen = false;
          _activeSlot = null;
          // Le verrouillage a déjà vidé le cache méta, mais `_saveVaultV4` a pu
          // le repeupler APRÈS lui : on le revide, sinon il survivrait au
          // verrouillage en décrivant l'emplacement qui vient d'être écrit.
          VaultService._wipeUnlessSame(_cachedSalt, cacheSalt);
          VaultService._wipeUnlessSame(_cachedWrappedDek, cacheWrappedDek);
          VaultService._wipeUnlessSame(_cachedWrapNonce, cacheWrapNonce);
          if (cacheSalt != null) SecretBytes.wipe(cacheSalt);
          if (cacheWrappedDek != null) SecretBytes.wipe(cacheWrappedDek);
          if (cacheWrapNonce != null) SecretBytes.wipe(cacheWrapNonce);
          _cachedSalt = null;
          _cachedWrappedDek = null;
          _cachedWrapNonce = null;
          _cachedKdfParams = null;
        } else {
          _wipeKey();
          _key = sessionKey;
          _entries = sessionEntries;
          _isOpen = sessionOpen;
          _activeSlot = sessionSlot;
          // Le cache méta revient avec le reste de la session. L'effacement des
          // tampons sortants passe par `_wipeUnlessSame` : si l'échec est
          // survenu AVANT `_saveVaultV4`, le cache courant et l'instantané sont
          // le MÊME objet, et un effacement direct remettrait un sel nul en
          // place — exactement la corruption que ce bloc existe pour empêcher.
          VaultService._wipeUnlessSame(_cachedSalt, cacheSalt);
          VaultService._wipeUnlessSame(_cachedWrappedDek, cacheWrappedDek);
          VaultService._wipeUnlessSame(_cachedWrapNonce, cacheWrapNonce);
          _cachedSalt = cacheSalt;
          _cachedWrappedDek = cacheWrappedDek;
          _cachedWrapNonce = cacheWrapNonce;
          _cachedKdfParams = cacheParams;
        }
      } else if (sessionKey != null) {
        // Création réussie : l'instantané est du matériel de clé, il ne doit
        // pas attendre le ramasse-miettes.
        SecretBytes.wipe(sessionKey);
      }
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
  /// SEC F10 v2.5.2 — [currentPassword] est désormais OBLIGATOIRE et vérifié.
  ///
  /// Avant, la fonction ne prenait que `newPassword` : elle générait un sel et
  /// un `hwSecret` neufs, réenveloppait, dérivait un nouveau `finalKey` et
  /// réécrivait le coffre — sans jamais vérifier le mot de passe courant ni
  /// consulter le verrouillage. Le dialogue appelant ne comportait que deux
  /// champs (« nouveau » / « confirmer »).
  ///
  /// Quiconque disposait d'un accès momentané à une session déverrouillée
  /// pouvait donc pivoter le secret et verrouiller DÉFINITIVEMENT le
  /// propriétaire hors de son coffre : la rotation invalide en prime
  /// l'enveloppe biométrique, et `allowBackup="false"` élimine toute
  /// restauration système. Le verrouillage automatique par défaut est à 300 s
  /// et n'est évalué qu'au retour au premier plan, donc une app laissée
  /// ouverte au premier plan ne se reverrouille jamais.
  ///
  /// Lève [StateError] avec [VaultService.wrongCurrentPassword] si la
  /// vérification échoue.
  Future<void> changeMasterPassword(
    String newPassword, {
    required String currentPassword,
  }) async {
    // SEC-R1 v2.5.2 — le mutex est tenu sur TOUTE l'opération, vérification
    // ET rotation. Avant, `verifyCurrentPassword` le relâchait en sortant et
    // la rotation lisait `_activeSlot` sans protection : un `unlock()`
    // concurrent pouvait s'intercaler et faire pivoter la clé du MAUVAIS
    // emplacement.
    if (_unlockGate != null) {
      throw StateError(VaultService.vaultBusy);
    }
    final gate = _unlockGate = Completer<void>();
    try {
      await _changeMasterPasswordLocked(
        newPassword,
        currentPassword: currentPassword,
      );
    } finally {
      if (!gate.isCompleted) gate.complete();
      _unlockGate = null;
    }
  }

  Future<void> _changeMasterPasswordLocked(
    String newPassword, {
    required String currentPassword,
  }) async {
    if (!await verifyCurrentPasswordLocked(currentPassword)) {
      throw StateError(VaultService.wrongCurrentPassword);
    }
    // v4 : Argon2id + re-wrap fresh hwSecret. Le slot opposé n'est pas affecté.
    final slot = _activeSlot ?? _Slot.primary;
    final salt = SecretBytes.randomBytes(32);

    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? pwHash;
    Uint8List? finalKey;
    // AUDIT 2026-08-03 (Gemini PT-001, CRITIQUE) — sauvegarde de la clé en
    // cours AVANT toute mutation, pour pouvoir revenir en arrière.
    //
    // Défaut corrigé : `_key` était remplacée par la NOUVELLE clé juste avant
    // `_saveVaultV4`. Si cette écriture échouait — disque plein, erreur d'E/S —
    // l'exception remontait jusqu'à l'écran, qui affichait « échec », mais la
    // session restait avec :
    //   • `_key`      = la clé NEUVE,
    //   • le fichier  = chiffré sous l'ANCIENNE clé,
    //   • `_cached*`  = l'ancien sel et l'ancienne enveloppe (`_saveVaultV4` ne
    //                   les rafraîchit qu'APRÈS une écriture réussie).
    // La moindre modification d'entrée ensuite réécrivait le coffre chiffré
    // sous la clé NEUVE en annonçant les métadonnées ANCIENNES. Au
    // déverrouillage suivant, la dérivation repartait de l'ancien sel et
    // produisait l'ancienne clé : l'étiquette AES-GCM ne pouvait plus se
    // vérifier. **Ni l'ancien ni le nouveau mot de passe n'ouvraient plus le
    // coffre** — perte définitive, à partir d'un simple disque plein.
    // SEC 2026-08-04 (audit GPT F1) — relevé AVANT la dérivation, comparé juste
    // avant l'écriture. Voir le raisonnement complet au point de comparaison.
    final genAvantRotation = _lockGeneration;
    final Uint8List? previousKey = _key == null
        ? null
        : Uint8List.fromList(_key!);
    var rotationCommitted = false;
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

      // SEC 2026-08-04 (audit GPT F1) — CRITIQUE : un verrouillage survenu
      // pendant la rotation ferait écrire un coffre VIDE par-dessus le vrai.
      //
      // Entre la vérification du mot de passe et ici, il s'écoule un Argon2id
      // complet — près d'une seconde. Si `lock()` survient dans cet intervalle
      // (mise en arrière-plan avec verrouillage immédiat, minuterie
      // d'inactivité, ou MODE PANIQUE), il pose `_entries = []`. Or
      // `_saveVaultV4` sérialise `_entries` : la rotation, qui ne consultait
      // rien, réécrivait alors le coffre AVEC UNE LISTE VIDE, chiffrée sous le
      // nouveau mot de passe. Toutes les entrées perdues, sans erreur, sans
      // trace, et le nouveau mot de passe ouvrant un coffre vide.
      //
      // La panique est le pire déclencheur : elle appelle `lock()` précisément
      // quand l'utilisateur est sous contrainte.
      //
      // J'avais ajouté `_lockGeneration` pour la VÉRIFICATION du mot de passe
      // et son commentaire annonce « toute opération longue [...] doit relever
      // ce compteur ». La rotation ne le faisait pas : le commentaire décrivait
      // une règle que son propre fichier n'appliquait pas.
      //
      // On abandonne, sans rien écrire. La rotation n'a pas eu lieu, l'ancien
      // mot de passe reste valide, et le verrouillage demandé est respecté.
      if (_lockGeneration != genAvantRotation) {
        throw StateError(VaultService.vaultBusy);
      }
      _wipeKey();
      _key = Uint8List.fromList(finalKey);

      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrap.ciphertext,
        wrapNonce: wrap.nonce,
        // Changement de mot de passe maître : la clé est intégralement
        // redérivée, c'est donc le bon moment pour adopter la recommandation
        // courante. Un coffre créé sous d'anciens paramètres se met ainsi à
        // niveau tout seul le jour où son propriétaire change de mot de passe.
        params: KdfParams.owaspMobile2024,
      );
      // ⚠️ POINT DE BASCULE — ici, et pas plus bas.
      //
      // `_saveVaultV4` se termine par un renommage atomique : dès qu'il rend la
      // main, le coffre sur disque EST chiffré sous la nouvelle clé. Annuler
      // au-delà de cette ligne produirait l'incohérence exactement inverse de
      // celle qu'on corrige — une session revenue à l'ancienne clé face à un
      // fichier neuf.
      //
      // L'écriture du sel en stockage qui suit n'est PAS porteuse pour le
      // format v4 : le déverrouillage lit `kdf.salt` DANS LE FICHIER
      // (cf. `_v4Unlock`). Elle ne sert qu'à périmer l'ancien sel v3, donc son
      // échec ne remet pas la rotation en cause.
      rotationCommitted = true;
      // V1 v2.4.0 — salt en storage écrit APRÈS save vault réussi : si le
      // process est tué entre les deux, on garde l'ancien salt cohérent
      // avec l'ancien vault au lieu d'un salt orphelin.
      //
      // SEC 2026-08-04 (audit GPT F9) — cette écriture ne peut plus faire
      // échouer une rotation DÉJÀ ACQUISE.
      //
      // Elle n'est pas porteuse en v4 : le déverrouillage lit `kdf.salt` DANS
      // LE FICHIER. Mais si elle levait, l'exception remontait jusqu'à l'écran,
      // qui annonçait un échec — alors que le coffre était bel et bien passé
      // sous le nouveau mot de passe. Pire, elle court-circuitait les deux
      // nettoyages qui suivent la sortie du `try` : l'invalidation de
      // l'enveloppe biométrique, et la suppression du `.bak` v3.
      //
      // Ce dernier point est le plus gênant : si l'on change de mot de passe
      // PARCE QUE l'ancien est compromis, laisser derrière soi une sauvegarde
      // v3 déchiffrable avec cet ancien mot de passe annule tout le bénéfice
      // de la rotation.
      try {
        await VaultService._storage.write(
          key: _saltKeyFor(slot),
          value: base64Encode(salt),
        );
      } catch (_) {
        // Vestige v3 uniquement : son absence n'empêche aucun déverrouillage.
      }
    } finally {
      if (!rotationCommitted) {
        // Échec en cours de route : on remet la session dans l'état EXACT
        // d'avant l'appel. Le coffre sur disque n'a pas bougé (ou a été
        // réécrit à l'identique), donc l'ancienne clé reste la bonne.
        //
        // Sans cette restauration, `_key` gardait la clé neuve alors que le
        // fichier était resté sous l'ancienne : le premier ajout d'entrée
        // scellait l'incohérence et rendait le coffre définitivement illisible.
        //
        // ⚠️ INVARIANT À NE PAS ROMPRE — SEC 2026-08-04 (relecture Codex).
        //
        // Le cache méta (`_cachedSalt` / `_cachedWrappedDek` /
        // `_cachedWrapNonce` / `_cachedKdfParams`) n'a PAS besoin d'être
        // restauré ici, contrairement à `_createSlot` où son omission ouvrait
        // une perte définitive du coffre principal. Raison : `_saveVaultV4` ne
        // met ce cache à jour qu'APRÈS le renommage atomique, et
        // `rotationCommitted = true` suit cet appel SANS aucun `await`
        // intermédiaire. Il n'existe donc aucun état où le cache décrit la
        // nouvelle dérivation alors que la rotation est annulée.
        //
        // Insérer un `await` entre `_saveVaultV4` et `rotationCommitted = true`
        // ROMPRAIT cet invariant et rouvrirait exactement le défaut corrigé
        // dans `_createSlot` : il faudrait alors instantané et restauration des
        // quatre champs, comme là-bas.
        _wipeKey();
        _key = previousKey;
      } else if (previousKey != null) {
        // Rotation réussie : la copie de secours est du matériel de clé, elle
        // ne doit pas traîner en mémoire en attendant le ramasse-miettes.
        SecretBytes.wipe(previousKey);
      }
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

    // F27 v2.3.7 — purge du `.bak` v3 orphelin créé par `_migrateV3ToV4`.
    // Reste sur disque chiffré avec l'ANCIEN password ; si l'utilisateur
    // change le master parce qu'il pense l'ancien compromis, le .bak
    // serait toujours brute-forçable avec ce password leaké.
    try {
      final src = await _vaultFileFor(slot);
      final bak = File('${src.path}_v3.enc.bak');
      if (await bak.exists()) await bak.delete();
    } catch (_) {
      // Best-effort : le bak n'existe peut-être plus (jamais migré v3).
    }
  }

  /// v2.5.x (H1) — migration du schéma de fichiers vers des noms neutres
  /// indistinguables + leurre factice toujours présent (déni plausible au
  /// repos). À appeler au démarrage AVANT tout accès au vault (`vaultExists`,
  /// `unlock`).
  ///
  /// Idempotent + crash-safe : le rename est atomique (même FS) et n'est tenté
  /// que si la cible est absente ; aucun coffre RÉEL n'est jamais
  /// supprimé/écrasé de façon destructive — on ne fait que renommer, puis
  /// éventuellement CRÉER un leurre factice là où il manque.
  Future<void> ensureVaultLayout() async {
    final dir = await getApplicationDocumentsDirectory();
    final oldPrimary = File('${dir.path}/pt_vault.enc');
    final oldDecoy = File('${dir.path}/pt_vault_decoy.enc');
    final newPrimary = File('${dir.path}/pt_vault_a.enc');
    final newDecoy = File('${dir.path}/pt_vault_b.enc');

    // 1. Rename atomique ancien → nouveau (seulement si cible absente ET source
    //    présente). Le rename POSIX same-FS est atomique : un crash laisse soit
    //    l'ancien, soit le nouveau, jamais rien.
    if (!newPrimary.existsSync() && oldPrimary.existsSync()) {
      await oldPrimary.rename(newPrimary.path);
    }
    if (!newDecoy.existsSync() && oldDecoy.existsSync()) {
      // Un VRAI decoy existait (ancien schéma) → on le porte + marque le flag.
      //
      // SEC 2026-08-04 (audit GPT F2) — le drapeau est écrit AVANT le
      // renommage, pour la même raison que dans `setupDecoyVault`.
      //
      // Dans l'ordre inverse, un processus tué entre les deux laissait le VRAI
      // leurre en place sous son nouveau nom, avec un drapeau absent. Au
      // démarrage suivant, `read(...) == 'true'` rendait `false` — l'absence
      // devenant indistinguable d'un « pas de vrai leurre » — et l'étiquette du
      // fichier, encore l'ancienne, déclenchait `_createDummyDecoy()`. Le vrai
      // coffre leurre était écrasé sans recours.
      //
      // Écrit d'abord, le pire cas devient un drapeau `'true'` sans vrai leurre
      // derrière : l'application s'abstient de toucher à `_b`. Incohérence
      // bénigne au lieu d'une perte définitive.
      await VaultService._storage.write(
        key: VaultService._decoyConfiguredKey,
        value: 'true',
      );
      await oldDecoy.rename(newDecoy.path);
    }

    // 2. Pas de coffre principal (fresh install pré-createVault) : rien à faire,
    //    createVault posera `_a` + le leurre `_b`.
    if (!newPrimary.existsSync()) return;

    // 3. Coffre principal présent mais `_b` absent (utilisateur sans decoy,
    //    ancien schéma 1 fichier) → créer un leurre factice pour rendre le
    //    profil constant. Best-effort : re-tenté au prochain boot si KEK/IO KO.
    if (!newDecoy.existsSync()) {
      try {
        await _createDummyDecoy();
      } catch (_) {
        return;
      }
    } else {
      // SEC F18 v2.5.4 — un leurre FACTICE écrit avant ce correctif porte
      // encore `pt_vault_kek_decoy_v1` dans son enveloppe : le mot « decoy »
      // en clair sur disque, plus 6 octets d'écart de taille avec le coffre
      // principal. C'est précisément le fichier que l'utilisateur n'ouvre
      // JAMAIS, donc aucun déverrouillage ne viendra le réécrire.
      //
      // Le leurre factice est chiffré sous un mot de passe aléatoire de 32
      // octets qui n'est écrit nulle part : il est irrécupérable par
      // conception, donc le régénérer ne perd RIEN.
      //
      // ⚠️ Ne JAMAIS régénérer quand un VRAI leurre est configuré : on
      // détruirait le second coffre de l'utilisateur. Ce cas-là migre au
      // premier déverrouillage du leurre, via `_migrateFileLabelIfLegacy`.
      try {
        final decoyConfigured =
            await VaultService._storage.read(
              key: VaultService._decoyConfiguredKey,
            ) ==
            'true';
        if (!decoyConfigured) {
          final onDisk = await _fileLabelOnDisk(_Slot.decoy);
          if (onDisk != null && onDisk != _fileLabelFor(_Slot.decoy)) {
            await _createDummyDecoy();
          }
        }
      } catch (_) {
        /* best-effort : re-tenté au prochain boot */
      }
    }

    // 4. Le flag a toujours une valeur explicite (profil de storage constant).
    //    Ne l'écrase PAS s'il vaut déjà 'true'.
    final cur = await VaultService._storage.read(
      key: VaultService._decoyConfiguredKey,
    );
    if (cur == null) {
      await VaultService._storage.write(
        key: VaultService._decoyConfiguredKey,
        value: 'false',
      );
    }

    // 5. Nettoyage défensif : aucun ancien fichier au nom révélateur ne doit
    //    subsister (un `pt_vault_decoy.enc` résiduel trahirait le decoy au
    //    repos). Après un rename atomique réussi ces chemins sont déjà vides ;
    //    ce garde-fou couvre un FS non-atomique / une copie partielle.
    if (newPrimary.existsSync() && oldPrimary.existsSync()) {
      try {
        await oldPrimary.delete();
      } catch (_) {}
    }
    if (newDecoy.existsSync() && oldDecoy.existsSync()) {
      try {
        await oldDecoy.delete();
      } catch (_) {}
    }
  }

  /// v2.5.x (H1) — écrit un coffre LEURRE FACTICE (0 entrée) sous un mot de
  /// passe aléatoire JAMAIS persisté (donc jamais déverrouillable). Sert
  /// uniquement à rendre le profil de fichiers constant (déni plausible au
  /// repos) : personne ne peut l'ouvrir, son contenu (vide) n'est jamais montré.
  ///
  /// N'altère PAS l'état courant (`_key` / `_entries` / `_activeSlot` /
  /// `_cached*`) — contrairement à `_createSlot` / `_saveVaultV4`. Réutilise les
  /// primitives crypto (Argon2id, KEK wrap, HKDF, AES-GCM) mais duplique
  /// volontairement l'assemblage d'enveloppe pour NE PAS toucher au chemin de
  /// sauvegarde audité.
  Future<void> _createDummyDecoy() async {
    await _keystore.ensureBothKeksExist();
    final salt = SecretBytes.randomBytes(32);
    // Mot de passe aléatoire 32 octets — JAMAIS écrit nulle part.
    final randomPw = base64Encode(SecretBytes.randomBytes(32));
    final pwHash = await KdfService.argon2id(password: randomPw, salt: salt);
    final hwSecret = SecretBytes.randomBytes(32);
    Uint8List? finalKey;
    Uint8List? ptBytes;
    try {
      // SEC F18 v2.5.4 — DEUX identifiants distincts, à ne jamais confondre :
      //   `keystoreAlias` adresse la clé matérielle et ne quitte JAMAIS la RAM ;
      //   `fileLabel`     part dans le fichier et sert d'AAD, neutre et de
      //                   longueur égale à celle de l'autre emplacement.
      final keystoreAlias = _aliasFor(_Slot.decoy);
      final fileLabel = _fileLabelFor(_Slot.decoy);
      final wrap = await _keystore.wrap(keystoreAlias, hwSecret);
      finalKey = await _hkdfFinalKey(
        salt: salt,
        pwHash: pwHash,
        hwSecret: hwSecret,
      );

      // Liste d'entrées VIDE chiffrée en AES-GCM, AAD identique au format v4.
      //
      // SEC F6 v2.5.2 — le clair est rembourré sur le MÊME barreau que l'autre
      // emplacement. Avant, `jsonEncode([])` produisait 2 octets, donc 18
      // octets de sortie GCM et 24 caractères base64 dans `cipher.data`, alors
      // qu'un coffre réel produit un chiffré proportionnel au nombre d'entrées.
      // AES-GCM préservant la longueur et aucun rembourrage n'étant appliqué,
      // la longueur du chiffré était le SEUL discriminant entre les deux
      // emplacements — et il était décisif. Le code étant public sous
      // Apache 2.0, la taille exacte du leurre était même calculable à l'avance.
      //
      // L'appariement sur l'autre emplacement (et non un tirage aléatoire)
      // donne l'équivalence STRICTE recherchée : le leurre factice fait
      // exactement la taille du vrai coffre.
      ptBytes = VaultService._padToLadder(
        utf8.encode(jsonEncode(const <dynamic>[])),
        minPlainBytes: await _otherSlotPlainLength(_Slot.decoy),
      );
      final aead = await AeadService.encryptGcm(
        key: finalKey,
        plaintext: ptBytes,
        aad: _aadV4(fileLabel, KdfParams.owaspMobile2024),
      );

      final out = <String, dynamic>{
        'magic': VaultService._vaultMagic,
        'version': VaultService._currentVersion,
        'kdf': <String, dynamic>{
          'algo': 'argon2id',
          // Leurre factice : `pwHash` ci-dessus a été dérivé avec les
          // paramètres par défaut, on annonce donc exactement ceux-là.
          'm': KdfParams.owaspMobile2024.memoryKiB,
          't': KdfParams.owaspMobile2024.iterations,
          'p': KdfParams.owaspMobile2024.parallelism,
          'salt': base64Encode(salt),
        },
        'kek': <String, dynamic>{
          'algo': 'AES-GCM-256',
          'alias': fileLabel,
          'wrappedDek': base64Encode(wrap.ciphertext),
          'wrapNonce': base64Encode(wrap.nonce),
        },
        'cipher': <String, dynamic>{
          'algo': 'AES-GCM-256',
          'nonce': base64Encode(aead.nonce),
          'data': base64Encode(aead.cipherAndTag),
        },
      };

      // Cible EXPLICITE au nouveau nom canonique `pt_vault_b.enc` — jamais via
      // `_vaultFileFor` (dont le fallback rétro-compat pourrait viser l'ancien
      // nom `pt_vault_decoy.enc` et RAVIVER la fuite H1).
      final dir = await getApplicationDocumentsDirectory();
      final target = File('${dir.path}/pt_vault_b.enc');
      final tmp = File('${target.path}.tmp');
      await tmp.writeAsString(jsonEncode(out), flush: true);
      await tmp.rename(target.path); // atomique
      await VaultService._storage.write(
        key: _saltKeyFor(_Slot.decoy),
        value: base64Encode(salt),
      );
    } finally {
      SecretBytes.wipe(pwHash);
      SecretBytes.wipe(hwSecret);
      if (finalKey != null) SecretBytes.wipe(finalKey);
      if (ptBytes != null) {
        try {
          ptBytes.fillRange(0, ptBytes.length, 0);
        } catch (_) {}
      }
    }
  }
}
