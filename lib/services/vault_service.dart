// VaultService — orchestrateur du coffre-fort Pass Tech.
//
// Cette library a été splittée (v2.1.2) pour rendre la crypto critique plus
// auditable :
//
//   vault_service.dart        ← orchestration + état + API publique
//   vault_crypto.dart         ← AAD v4, HKDF, decrypt v3/v4 (legacy muteur)
//   vault_storage.dart        ← saveVault / saveVaultV4 (atomic write)
//   vault_unlock.dart         ← passwordMatchesPrimary, _tryUnlockSlot,
//                                _v4Unlock, unlockWithBiometric
//   vault_brute_force.dart    ← compteur d'échecs + lockout exponentiel
//   vault_migration.dart      ← migration v3 → v4
//
// Tous les fichiers ci-dessus sont des `part of 'vault_service.dart';`. Ils
// partagent la même library et accèdent donc librement aux membres privés
// (_key, _entries, _activeSlot, _keystore, _bioStorage, _wipeKey, etc.).
//
// L'API publique (createVault, unlock, addEntry, …) reste exposée par cette
// classe. Tout le reste de l'app n'a JAMAIS besoin d'importer les parts —
// l'unique import autorisé reste `package:pass_tech/services/vault_service.dart`.

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:biometric_storage/biometric_storage.dart';
import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart' as cg;
import 'package:encrypt/encrypt.dart' as enc;
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

import '../models/entry.dart';
import 'aead_service.dart';
import 'anti_phishing_service.dart';
import 'clipboard_service.dart';
import 'kdf_service.dart';
import 'keystore_service.dart';
import 'monotonic_clock.dart';

part 'vault_brute_force.dart';
part 'vault_crypto.dart';
part 'vault_migration.dart';
part 'vault_setup.dart';
part 'vault_storage.dart';
part 'vault_unlock.dart';

// Top-level function required by compute()
Uint8List pbkdf2Worker(List<dynamic> args) {
  final password = args[0] as List<int>;
  final salt = args[1] as List<int>;
  final iterations = args[2] as int;
  final keyLen = args[3] as int;

  final hmacGen = Hmac(sha256, password);
  const hLen = 32;
  final blocks = (keyLen / hLen).ceil();
  final dk = BytesBuilder();

  try {
    for (int i = 1; i <= blocks; i++) {
      final saltI = Uint8List(salt.length + 4);
      saltI.setRange(0, salt.length, salt);
      saltI[salt.length] = (i >> 24) & 0xFF;
      saltI[salt.length + 1] = (i >> 16) & 0xFF;
      saltI[salt.length + 2] = (i >> 8) & 0xFF;
      saltI[salt.length + 3] = i & 0xFF;

      var u = Uint8List.fromList(hmacGen.convert(saltI).bytes);
      final t = Uint8List.fromList(u);

      for (int j = 1; j < iterations; j++) {
        u = Uint8List.fromList(hmacGen.convert(u).bytes);
        for (int k = 0; k < t.length; k++) {
          t[k] ^= u[k];
        }
      }
      dk.add(t);
    }
    return dk.toBytes().sublist(0, keyLen);
  } finally {
    // F9 v2.3.7 — wipe le password reçu côté worker isolate (la copie
    // transférée par compute() restait en RAM jusqu'à GC sinon).
    // A8 v2.3.8 — pattern aligné `SecretBytes.wipe` (handle Uint8List
    // non-modifiable retourné par certains FFI). Pour les List<int>
    // autres que Uint8List, on tente un overwrite via setRange best-effort.
    if (password is Uint8List) {
      SecretBytes.wipe(password);
    } else {
      try {
        for (var i = 0; i < password.length; i++) {
          password[i] = 0;
        }
      } catch (_) {
        // List<int> immutable (rare via compute) : best-effort, GC nettoiera.
      }
    }
    // Wipe aussi le buffer dérivé intermédiaire (dk.toBytes() retourne une
    // copie ; le sublist final est seul retenu). On n'a pas accès au
    // buffer du BytesBuilder, mais return + immutable view minimise.
  }
}

enum UnlockResult {
  success,
  wrongPassword,
  lockedOut,

  /// (v2.4.2) Tentative biométrique sur une clé Keystore invalidée par
  /// Android — typiquement après un ré-enrôlement d'empreinte. Le wrap
  /// biométrique a été auto-nettoyé par le caller ; l'utilisateur doit
  /// passer par le master password puis réactiver la biométrie depuis
  /// Réglages. À distinguer de [wrongPassword] (annulation utilisateur
  /// silencieuse) pour donner un message clair au lieu d'un échec opaque.
  biometricInvalidated,

  /// AUDIT 2026-08-03 — une autre opération détient déjà `_unlockGate`.
  ///
  /// Avant, ce refus de concurrence empruntait [wrongPassword] : un double-appui
  /// rapide sur « Déverrouiller » affichait « mot de passe incorrect » alors que
  /// la saisie était bonne et qu'AUCUN essai n'avait été consommé. Message
  /// alarmant et trompeur sur l'écran le plus sensible de l'app.
  ///
  /// `changeMasterPassword` disposait déjà d'une sentinelle dédiée
  /// ([VaultService.vaultBusy]) pour exactement ce cas — c'est le même
  /// raisonnement, simplement jamais propagé à `unlock()`.
  ///
  /// ⚠️ Ne déclenche AUCUN comptage d'échec : rien n'a été tenté.
  busy,
}

/// Identifie quel slot du vault est en cours d'utilisation.
/// - primary : coffre historique (file `pt_vault_a.enc`, salt pt_salt)
/// - decoy   : coffre leurre (file `pt_vault_b.enc`, salt pt_salt_decoy) —
///   TOUJOURS présent depuis H1 (leurre factice si pas de vrai decoy).
/// Noms de fichiers neutres (ex-`pt_vault.enc` / `pt_vault_decoy.enc`) migrés
/// par `ensureVaultLayout`. Voir aussi le flag `pt_decoy_configured`.
///
/// Le code ne fait JAMAIS de différence fonctionnelle entre primary et decoy.
/// Les deux ont les mêmes capacités (CRUD entries, biométrique optionnelle…).
/// Le slot actif est juste celui dont le master password a déchiffré.
/// L'attaquant qui voit le device ne peut pas savoir lequel est "réel".
enum _Slot { primary, decoy }

/// Ce que [VaultService.deleteVault] a réellement supprimé.
///
/// L'appelant DOIT s'en servir pour choisir l'écran suivant : après un
/// [fullWipe] plus aucun coffre n'existe (écran de création), tandis qu'après
/// un [decoyOnly] le coffre principal est toujours là (écran de
/// déverrouillage). Pousser l'écran de création après un [decoyOnly] serait
/// dangereux : y créer un coffre écraserait le principal.
/// Ce que [VaultService.deleteDecoyVault] a fait de la session en cours.
///
/// L'appelant DOIT s'en servir pour choisir l'écran suivant : après un
/// [sessionLocked] le coffre ouvert vient d'être écrasé, donc plus rien n'est
/// déverrouillé et il faut revenir à l'écran de déverrouillage.
enum DecoyDeleteOutcome {
  /// L'appel venait du coffre principal : le leurre a été remplacé par un
  /// leurre factice, la session en cours n'est pas touchée.
  keptSession,

  /// L'appel venait de la session LEURRE elle-même : cette session a été
  /// verrouillée AVANT l'écrasement, comme le fait `deleteVault`. Le coffre
  /// principal, lui, est intact et toujours invisible.
  sessionLocked,
}

enum VaultDeleteOutcome {
  /// Session principale : tout est détruit, coffres et KEK Keystore compris.
  fullWipe,

  /// Session leurre : seul l'emplacement leurre est écrasé par un leurre
  /// factice non déverrouillable. Le principal n'est PAS touché, et le profil
  /// de fichiers reste constant (deux fichiers, tailles identiques).
  decoyOnly,
}

class VaultService {
  static final VaultService _instance = VaultService._();
  factory VaultService() => _instance;
  VaultService._();

  /// Keystore backend used for KEK operations (H-3). Defaults to the real
  /// AndroidKeyStore channel; tests inject `InMemoryKeystoreBackend` via
  /// [setKeystoreForTesting] before exercising migration / unlock paths.
  KeystoreService _keystore = const KeystoreService(
    backend: ChannelKeystoreBackend(),
  );

  @visibleForTesting
  void setKeystoreForTesting(KeystoreService ks) {
    _keystore = ks;
  }

  // flutter_secure_storage v10+ : EncryptedSharedPreferences (Jetpack Security)
  // est déprécié. La lib v10 utilise désormais ses propres ciphers en
  // interne. Migration automatique des données existantes au 1er accès.
  static const _storage = FlutterSecureStorage();

  // Secure storage keys
  // Le slot "primary" est le coffre historique (existait avant le decoy).
  // Le slot "decoy" est le coffre leurre optionnel — déni plausible.
  // L'unlock teste le password contre les deux slots successivement.
  static const _saltKey = 'pt_salt'; // = primary (rétro-compat)
  static const _decoySaltKey = 'pt_salt_decoy';
  static const _biometricStorageName = 'pt_biometric_key_v2';
  static const _biometricFlagKey = 'pt_biometric_enabled';
  static const _failCountKey = 'pt_fail_count';

  /// Ancien format du verrouillage : horodatage ABSOLU sur horloge murale.
  /// Conservé en lecture seule pour les installations verrouillées au moment
  /// de la mise à jour — plus jamais écrit (cf. SEC F5/F17).
  static const _lockoutKey = 'pt_lockout_until';

  /// SEC F5/F17 v2.5.2 — nouveau format : durée restante en millisecondes,
  /// plus l'ancre `SystemClock.elapsedRealtime()` à laquelle elle a été
  /// écrite. Insensible aux manipulations de l'horloge murale.
  static const _lockoutRemainingKey = 'pt_lockout_remaining_ms';
  static const _lockoutAnchorKey = 'pt_lockout_anchor_ms';

  // v2.5.x (H1) — flag « un VRAI coffre leurre est configuré ». Stocké chiffré
  // (EncryptedSharedPreferences, clé TEE non-extractible → illisible au
  // forensic) et TOUJOURS présent (défaut 'false') pour un profil de storage
  // constant. Remplace l'ancien signal « le fichier decoy existe » — lequel,
  // combiné au nom `_decoy`, révélait au repos l'existence du 2ᵉ coffre. Voir
  // `ensureVaultLayout` (leurre factice toujours présent + noms neutres).
  static const _decoyConfiguredKey = 'pt_decoy_configured';

  /// Slot du vault actuellement ouvert (pour les écritures ultérieures).
  /// null si aucun vault ouvert.
  _Slot? _activeSlot;

  BiometricStorageFile? _bioFile;
  Future<BiometricStorageFile> _bioStorage() async {
    // M-6 : `biometric_storage` v5.0.x utilise déjà côté natif un Cipher AES
    // KeyGenParameterSpec avec setUserAuthenticationRequired(true). En
    // revanche, le paramètre setInvalidatedByBiometricEnrollment(true) n'est
    // PAS exposé par l'API publique du package (il faudrait un fork ou un
    // MethodChannel maison pour le forcer). Conséquence : si l'utilisateur
    // ajoute/retire une empreinte, la clé Keystore liée ne sera PAS invalidée
    // automatiquement → l'attaquant qui ajoute son empreinte avant
    // déverrouillage device pourrait théoriquement déverrouiller la bio.
    // Mitigations en place :
    //  - L'ajout d'empreinte requiert le PIN/pattern device (Android impose
    //    une auth strong avant enrollment).
    //  - L'écran d'unlock conserve toujours l'option master password.
    //  - À documenter dans SECURITY.md / Réglages : « si vous ajoutez une
    //    empreinte, désactivez puis réactivez le déverrouillage biométrique
    //    pour régénérer la clé liée ».
    // TODO M-6 (suite) : envisager un MethodChannel custom AndroidKeyStore
    // pour forcer setInvalidatedByBiometricEnrollment(true) — voir
    // ROADMAP_HARDENING.md.
    return _bioFile ??= await BiometricStorage().getStorage(
      _biometricStorageName,
      options: StorageFileInitOptions(
        authenticationRequired: true,
        authenticationValidityDurationSeconds: -1,
        androidBiometricOnly: true,
      ),
      promptInfo: const PromptInfo(
        androidPromptInfo: AndroidPromptInfo(
          title: 'Pass Tech',
          subtitle: 'Déverrouiller votre coffre-fort',
          negativeButton: 'Annuler',
          confirmationRequired: false,
        ),
      ),
    );
  }

  // Crypto parameters
  // v3 (legacy, read-only): PBKDF2-HMAC-SHA256 600 000 iter, AES-CBC + HMAC.
  //                         (Référence historique — plus utilisé : tous les
  //                         vaults sont migrés en v4 au premier unlock.)
  // v4 (current)          : Argon2id (m=19 MiB, t=2, p=1) → HKDF, AES-GCM-256,
  //                         hwSecret 32B wrapped by an AndroidKeyStore KEK.
  static const _legacyIterations =
      100000; // for v1.0.0 vaults without iterations field
  static const _maxIterations =
      2000000; // hard cap to prevent DoS via tampered file
  static const _v3Version = 3;
  static const _currentVersion = 4;
  static const _vaultMagic = 'PTVAULT';

  // AUDIT 2026-08-03 — les alias `_argon2M` / `_argon2T` / `_argon2P` ont été
  // supprimés. Ils recopiaient `KdfParams.owaspMobile2024` et servaient à la
  // fois à l'écriture du fichier ET à la construction de l'AAD, ce qui figeait
  // la lecture sur une constante de compilation.
  //
  // Désormais : la valeur d'écriture est `KdfParams.owaspMobile2024`, citée
  // explicitement là où l'on crée ou redérive une clé ; la valeur de lecture
  // vient du fichier, via `KdfParams.fromFileOrNull`. Un alias intermédiaire
  // ne ferait que brouiller cette distinction, qui est tout l'enjeu.

  // Brute-force protection: progressive lockout after 5 fails
  static const _failThreshold = 5;
  static const _lockoutSteps = [30, 60, 300, 900, 1800]; // seconds

  // Vault key cache.
  //  - v3 path: 64 bytes (0-31 enc key, 32-63 HMAC key) — kept for read-only.
  //  - v4 path: 32 bytes (finalKey = HKDF(pwHash || hwSecret, info=pt:v4)).
  // Length disambiguates the two; never mixed within one open session.
  Uint8List? _key;
  List<Entry> _entries = [];
  bool _isOpen = false;

  // QW2 v2.4.0 — cache des méta v4 (salt + KEK wrap) post-unlock. Sans ce
  // cache, chaque CRUD relisait le fichier complet pour récupérer ces 3
  // champs stables → ~30-50 ms d'I/O+parse JSON par save sur S9. Wipé au
  // lock() avec le reste.
  Uint8List? _cachedSalt;
  Uint8List? _cachedWrappedDek;
  Uint8List? _cachedWrapNonce;

  /// AUDIT 2026-08-03 — paramètres Argon2id de l'emplacement ACTUELLEMENT
  /// ouvert, tels que lus dans son fichier.
  ///
  /// Ils appartiennent au même lot que `_cachedSalt` : ce sont les entrées qui
  /// ont produit la clé en cours d'utilisation. Toute réécriture du coffre doit
  /// donc les REPORTER tels quels, exactement comme elle reporte le sel.
  ///
  /// Sans ce report, la relecture des paramètres introduirait elle-même une
  /// perte de données : un coffre portant des paramètres différents des
  /// constantes serait ouvert avec les siens, puis la première modification
  /// d'entrée le réécrirait en annonçant les constantes — alors que son contenu
  /// resterait chiffré sous l'ancienne clé. Le déverrouillage suivant
  /// dériverait à partir des constantes annoncées et échouerait définitivement.
  ///
  /// Changer de paramètres est donc, par construction, réservé aux opérations
  /// qui redérivent la clé : création du coffre, migration v3→v4, changement de
  /// mot de passe maître.
  KdfParams? _cachedKdfParams;

  bool get isOpen => _isOpen;
  List<Entry> get entries => List.unmodifiable(_entries);

  Future<bool> get vaultExists async =>
      (await _vaultFileFor(_Slot.primary)).existsSync();

  /// v2.5.x (H1) — « un VRAI coffre leurre est-il configuré ? ». Lit le flag
  /// chiffré (secure storage), PAS l'existence du fichier `_b` : depuis H1 ce
  /// fichier existe TOUJOURS (leurre factice si pas de vrai decoy), donc son
  /// existence ne dit plus rien. Le flag est illisible au repos (clé TEE).
  Future<bool> get hasDecoyVault async =>
      (await _storage.read(key: _decoyConfiguredKey)) == 'true';

  /// True si le slot actuellement déverrouillé est le coffre leurre.
  /// L'app peut s'en servir pour adapter discrètement l'UX, mais ne doit
  /// JAMAIS l'afficher visuellement (ce serait briser le déni plausible).
  bool get isDecoyActive => _activeSlot == _Slot.decoy;

  // ── Setup ───────────────────────────────────────────────────────────────────

  Future<void> createVault(String masterPassword) async {
    await _createSlot(_Slot.primary, masterPassword);
    // v2.5.x (H1) — leurre factice créé DÈS la création du coffre (profil de
    // fichiers constant : tout install a `_a` + `_b`). Flag 'false' = pas de
    // VRAI decoy. `setupDecoyVault` écrasera ce factice si l'utilisateur en
    // configure un. Best-effort : un échec ici ne doit pas bloquer la création
    // du coffre principal (déjà écrit + déverrouillé).
    try {
      await _createDummyDecoy();
      await _storage.write(key: _decoyConfiguredKey, value: 'false');
    } catch (_) {
      /* le leurre sera re-tenté au prochain boot par ensureVaultLayout */
    }
  }

  /// Crée le coffre LEURRE (decoy). Appelé depuis Settings quand l'utilisateur
  /// configure son coffre anti-coercition. Le coffre leurre est totalement
  /// distinct du primary (autre salt, autre fichier, autres entrées).
  ///
  /// **IMPORTANT** : le decoyPassword DOIT être différent du master password
  /// du primary. Sinon les 2 slots déchiffreraient avec le même mot de passe
  /// et l'unlock retournerait toujours le même (le primary qui est testé
  /// avant). L'appelant doit valider en amont que le 2 mots de passe diffèrent.
  Future<void> setupDecoyVault(String decoyPassword) async {
    // `_createSlot(decoy)` écrase le fichier `_b` (leurre factice) par un VRAI
    // coffre leurre (contenu + mot de passe choisis par l'utilisateur).
    await _createSlot(_Slot.decoy, decoyPassword);
    // v2.5.x (H1) — marque qu'un VRAI decoy existe désormais (pour l'UI).
    await _storage.write(key: _decoyConfiguredKey, value: 'true');
  }

  /// SEC F18 v2.5.4 — étiquette NEUTRE écrite dans le fichier et utilisée comme
  /// AAD, à ne pas confondre avec [_aliasFor] qui adresse la clé matérielle.
  /// Voir [KeystoreAliases.fileLabelPrimary] pour le raisonnement complet.
  String _fileLabelFor(_Slot slot) => slot == _Slot.primary
      ? KeystoreAliases.fileLabelPrimary
      : KeystoreAliases.fileLabelDecoy;

  String _aliasFor(_Slot slot) =>
      slot == _Slot.primary ? KeystoreAliases.primary : KeystoreAliases.decoy;

  // ── Unlock ──────────────────────────────────────────────────────────────────

  /// F5 v2.3.7 — mutex re-entrant : `unlock()` est lourd (2× PBKDF2 +
  /// 2× Argon2id pour le déni plausible). Un double-tap UI rapide pouvait
  /// lancer 2 unlocks en parallèle, doublant la conso CPU/RAM (OOM
  /// possible sur Redmi 9C 3GB) et créant des races sur `_key`/`_entries`
  /// pendant l'itération du déni plausible.
  Completer<void>? _unlockGate;

  Future<UnlockResult> unlock(String masterPassword) async {
    // F5 — refus immédiat si un unlock concurrent tourne déjà.
    if (_unlockGate != null) {
      return UnlockResult.busy;
    }
    final gate = _unlockGate = Completer<void>();
    try {
      return await _unlockInternal(masterPassword);
    } finally {
      if (!gate.isCompleted) gate.complete();
      _unlockGate = null;
    }
  }

  /// AUDIT 2026-08-03 — enveloppe fail-closed de [_unlockInternalUnguarded].
  ///
  /// `_unlockInternal` était la SEULE des trois fonctions d'ouverture à ne pas
  /// être protégée : `_tryUnlockSlot` et `_passwordMatchesPrimaryInternal`
  /// enveloppent toutes deux leur corps dans un `try/catch` qui rend un échec.
  /// L'asymétrie vient du refactor F3 v2.4.4, qui a déplacé le chemin
  /// d'ouverture PRINCIPAL de `_tryUnlockSlot` (protégée) vers ici.
  ///
  /// Ce qui n'était protégé nulle part :
  ///   • `jsonDecode(await file.readAsString())` et son cast en Map ;
  ///   • dans `_v4Unlock`, les `base64Decode` et les casts d'en-tête, placés
  ///     AVANT son propre `try`.
  ///
  /// L'exception traversait `unlock()` (`try`/`finally`, sans `catch`) puis
  /// `_unlock()` côté écran, qui ne l'attrape pas davantage : `_loading`
  /// restait à `true` et l'utilisateur se retrouvait devant un indicateur de
  /// progression PERMANENT, sans message ni sortie, à chaque tentative. Même
  /// famille que SEC F19 (l'impasse dont on ne sort qu'en tuant le processus).
  ///
  /// Atteignabilité : l'écriture du coffre est atomique (`tmp` + `rename`), il
  /// faut donc une corruption du système de fichiers ou un accès root pour
  /// produire un fichier malformé. Probabilité faible, conséquence sévère.
  Future<UnlockResult> _unlockInternal(String masterPassword) async {
    try {
      return await _unlockInternalUnguarded(masterPassword);
    } catch (_) {
      // Fail-closed, aligné sur `_tryUnlockSlot` : on n'ouvre rien et on ne
      // laisse aucun matériel de clé derrière soi.
      _wipeKey();
      _entries = [];
      _isOpen = false;
      _activeSlot = null;
      return UnlockResult.wrongPassword;
    }
  }

  /// Consomme ~1 Argon2id « pour rien », afin que le temps total d'une
  /// tentative d'ouverture ne dépende pas du nombre d'emplacements réellement
  /// présents et lisibles. Sans cela, un chronomètre distingue un appareil qui
  /// porte un coffre leurre d'un appareil qui n'en porte pas — et le déni
  /// plausible tombe sans qu'on ait eu à déchiffrer quoi que ce soit.
  ///
  /// Le mot de passe est CONSTANT et sans rapport avec la saisie : le coût
  /// d'Argon2id croît avec la longueur de l'entrée, donc hacher le vrai mot de
  /// passe maître rouvrirait un canal de fuite (marginal) sur sa longueur.
  ///
  /// AUDIT 2026-08-03 — ce bloc était recopié à l'identique à trois endroits
  /// (deux dans `_unlockInternal`, un dans `_tryUnlockSlot`) et un quatrième
  /// site en avait désormais besoin. Factorisé ici plutôt que dupliqué une
  /// fois de plus : c'est une garantie de sécurité, elle doit avoir une seule
  /// définition.
  Future<void> _consumeDummyArgon2() async {
    final dummySalt = SecretBytes.randomBytes(32);
    final dummyOut = await KdfService.argon2id(
      password: 'pt_dummy_noop_v2',
      salt: dummySalt,
    );
    SecretBytes.wipe(dummyOut);
  }

  Future<UnlockResult> _unlockInternalUnguarded(String masterPassword) async {
    if (await getLockoutRemaining() != null) return UnlockResult.lockedOut;
    // Déni plausible : on tente UNE PASSE Argon2id sur CHAQUE slot, même si
    // un slot précédent a matché. Sinon le timing révèle l'existence du
    // decoy (1× Argon2id = matché primary, 2× = matché decoy ou échec avec
    // decoy présent). Toujours 2× Argon2id → pas de side-channel.
    //
    // F3 v2.4.4 — Refactor anti-RAM-exposure decoy. Auparavant `_tryUnlockSlot`
    // mute `_entries`/`_isOpen` dès qu'un slot déchiffrait, donc dans le cas
    // (rare) où l'utilisateur avait choisi le MÊME password pour primary et
    // decoy, la 2ᵉ itération exposait brièvement les entries decoy en RAM
    // avant l'écrasement par le winner. Désormais : `_v4Unlock` est pur,
    // on capture le winner (key + entries + cache méta) localement, et on
    // applique l'état AU FINAL pour le winner uniquement.
    _Slot? matchedSlot;
    Uint8List? winnerKey;
    List<Entry>? winnerEntries;
    Uint8List? winnerSalt;
    Uint8List? winnerWrappedDek;
    Uint8List? winnerWrapNonce;
    KdfParams? winnerParams;
    bool winnerIsV3 = false;

    // AUDIT 2026-08-03 — si une exception traverse malgré tout la boucle
    // (Argon2id en manque de mémoire, `path_provider` indisponible…), les
    // tampons du gagnant DÉJÀ capturés — dont sa clé de coffre — resteraient
    // en RAM jusqu'au passage du ramasse-miettes, à une date non déterministe
    // et hors de portée de `_wipeKey()`, qui n'agit que sur `_key`.
    // On les efface avant de laisser l'exception remonter vers la garde
    // fail-closed de `_unlockInternal`. Même raisonnement que SEC F21.
    try {
      for (final slot in _Slot.values) {
        final file = await _vaultFileFor(slot);
        if (!await file.exists()) {
          // Anti-timing : consomme ~1 Argon2id même si le slot n'existe pas.
          await _consumeDummyArgon2();
          continue;
        }
        // AUDIT 2026-08-03 — lecture + parse protégés PAR EMPLACEMENT, et non
        // seulement par la garde globale de `_unlockInternal`.
        //
        // Un `pt_vault_b.enc` corrompu ne doit JAMAIS empêcher d'ouvrir
        // `pt_vault_a.enc` : avec une garde uniquement globale, la première
        // exception rencontrée condamnait l'ouverture des DEUX emplacements,
        // alors que le coffre principal était parfaitement lisible.
        // On consomme le Argon2id factice pour que l'emplacement illisible reste
        // indiscernable d'un emplacement absent du point de vue du chronomètre.
        final Map<String, dynamic> raw;
        final int version;
        try {
          raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
          version = raw['version'] as int? ?? 1;
        } catch (_) {
          await _consumeDummyArgon2();
          continue;
        }

        if (version >= _currentVersion) {
          // ── Path v4 pur (refactor F3 v2.4.4) ────────────────────────────
          final r = await _v4Unlock(
            slot: slot,
            password: masterPassword,
            raw: raw,
          );
          if (r == null) continue;
          if (matchedSlot == null) {
            matchedSlot = slot;
            winnerKey = r.finalKey;
            winnerEntries = r.entries;
            winnerSalt = r.salt;
            winnerWrappedDek = r.wrappedDek;
            winnerWrapNonce = r.wrapNonce;
            winnerParams = r.params;
          } else {
            // 2ᵉ slot déchiffre aussi (user a réutilisé le même password —
            // erreur de config). Wipe immédiatement TOUS les buffers du
            // perdant : sa clé v4, son cache méta, et ses entries (liste de
            // String laissée à la merci du GC, mais on déréférence vite).
            SecretBytes.wipe(r.finalKey);
            SecretBytes.wipe(r.salt);
            SecretBytes.wipe(r.wrappedDek);
            SecretBytes.wipe(r.wrapNonce);
            // r.entries sera GC après cette itération (référence locale).
          }
        } else {
          // ── Path v3 legacy (rare, vault non-migré depuis v2.0.0) ───────
          // Si on a déjà un winner v4, on skip la migration v3 du perdant —
          // on ne migre que pour le winner (cas v3 winner ci-dessous).
          // Anti-timing : consomme ~1 Argon2id dummy pour ne pas révéler
          // qu'on a déjà un winner.
          if (matchedSlot != null) {
            await _consumeDummyArgon2();
            continue;
          }
          final r = await _tryUnlockSlot(slot, masterPassword);
          if (r == UnlockResult.success) {
            // Le path v3 mute déjà `_key`/`_entries`/`_isOpen`/`_activeSlot`
            // et déclenche la migration v3→v4 inline. On capture juste un
            // marqueur pour ne pas réécrire l'état au final.
            matchedSlot = slot;
            winnerKey = Uint8List.fromList(_key!);
            winnerEntries = List<Entry>.from(_entries);
            winnerIsV3 = true;
          }
        }
      }
    } catch (_) {
      if (winnerKey != null) SecretBytes.wipe(winnerKey);
      if (winnerSalt != null) SecretBytes.wipe(winnerSalt);
      if (winnerWrappedDek != null) SecretBytes.wipe(winnerWrappedDek);
      if (winnerWrapNonce != null) SecretBytes.wipe(winnerWrapNonce);
      winnerEntries = null;
      rethrow;
    }

    if (matchedSlot != null && winnerKey != null) {
      if (!winnerIsV3) {
        // F3 v2.4.4 — Application des side effects UNIQUEMENT pour le
        // winner v4. Aucune mutation n'a eu lieu pendant le loop pour ce
        // path : pas de fenêtre d'exposition RAM des entries decoy.
        _wipeKey();
        _key = winnerKey;
        _entries = winnerEntries!;
        _isOpen = true;
        _activeSlot = matchedSlot;
        if (_cachedSalt != null) SecretBytes.wipe(_cachedSalt!);
        if (_cachedWrappedDek != null) SecretBytes.wipe(_cachedWrappedDek!);
        if (_cachedWrapNonce != null) SecretBytes.wipe(_cachedWrapNonce!);
        _cachedSalt = winnerSalt;
        _cachedWrappedDek = winnerWrappedDek;
        _cachedWrapNonce = winnerWrapNonce;
        _cachedKdfParams = winnerParams;
        await _onUnlockSuccess();
        // SEC F18 v2.5.4 — réécrit ce coffre s'il porte encore l'étiquette
        // héritée. Pour le leurre, c'est ce qui retire du disque le mot
        // « decoy » écrit en clair.
        await _migrateFileLabelIfLegacy(matchedSlot);
        // AUDIT 2026-08-03 — seul moment où l'on détient la clé de cet
        // emplacement : si son fichier est resté sur un barreau inférieur à
        // celui du voisin, c'est maintenant ou jamais qu'on le réaligne.
        await _realignPaddingIfNeeded(matchedSlot);
      } else {
        // SEC F21 v2.5.4 — le chemin v3 a DÉJÀ posé `_key` / `_entries`
        // lui-même ; `winnerKey` n'est qu'une copie de contrôle, créée par
        // `Uint8List.fromList` donc sur un buffer DISTINCT de `_key`.
        //
        // Avant : la branche `if (!winnerIsV3)` était sautée et la fonction
        // retournait directement, si bien qu'une copie COMPLÈTE de la clé du
        // coffre restait en RAM jusqu'au passage du GC — à une date non
        // déterministe, hors de portée de `_wipeKey()` qui n'agit que sur
        // `_key`. Portée étroite (coffres v3 hérités, migrés vers v4 dans la
        // foulée) mais c'est du matériel de clé, donc on l'efface.
        //
        // `winnerEntries` n'est pas effaçable de la même façon : c'est une
        // `List<Entry>` d'objets immuables que seul le GC peut reprendre. On
        // la déréférence au moins explicitement.
        SecretBytes.wipe(winnerKey);
        winnerEntries = null;
      }
      return UnlockResult.success;
    }
    _wipeKey();
    await _onUnlockFail();
    return UnlockResult.wrongPassword;
  }

  /// True if a biometric-bound vault key has been registered. We check a
  /// non-secret flag in flutter_secure_storage so we don't need to trigger
  /// a biometric prompt just to know whether the feature is available.
  Future<bool> get hasBiometricKey async =>
      (await _storage.read(key: _biometricFlagKey)) == '1';

  Future<void> saveBiometricKey() async {
    if (_key == null) return;
    // SÉCURITÉ : la biométrique est verrouillée au coffre PRIMARY.
    // Si l'utilisateur ouvre le decoy puis tente d'activer la bio, on
    // refuse — sinon la clé du decoy serait stockée dans biometric_storage
    // et un attaquant qui aurait l'app pourrait déverrouiller le decoy
    // sans connaître son password (avec juste l'empreinte). Pire encore,
    // cela trahirait l'existence du decoy à un attaquant attentif.
    if (_activeSlot != _Slot.primary) {
      throw StateError(
        'La biométrique n\'est disponible que sur le coffre principal',
      );
    }
    final store = await _bioStorage();
    await store.write(base64Encode(_key!));
    await _storage.write(key: _biometricFlagKey, value: '1');
  }

  Future<void> deleteBiometricKey() async {
    // F2 v2.4.3 — Ordre inversé : on supprime D'ABORD le flag UI (source de
    // vérité pour `hasBiometricKey` → bouton biométrie affiché ou non).
    // Si la suppression du storage Keystore échoue ensuite (cas rare,
    // typiquement clé déjà invalidée par l'OS), l'UI reflète quand même
    // l'état "biométrie désactivée" et invite l'utilisateur à réactiver
    // proprement. Avant : le flag pouvait survivre à un delete partiel, et
    // le bouton bio s'affichait alors qu'aucun storage utilisable n'existait.
    await _storage.delete(key: _biometricFlagKey);
    try {
      final store = await _bioStorage();
      await store.delete();
    } catch (_) {}
    _bioFile = null;
  }

  /// AUDIT 2026-08-03 (Gemini PT-002) — compteur de verrouillages.
  ///
  /// Incrémenté à CHAQUE [lock]. Toute opération longue qui prend un
  /// instantané de l'état du coffre pour le restaurer ensuite doit relever ce
  /// compteur avant, et renoncer à restaurer s'il a changé.
  ///
  /// Sans ce garde-fou, `_passwordMatchesPrimaryInternal` — qui dure le temps
  /// d'un Argon2id, soit près d'une seconde — remettait `_key`, `_entries`,
  /// `_isOpen` et `_activeSlot` dans son `finally` SANS CONDITION. Un
  /// verrouillage survenant pendant ce laps de temps était donc annulé, et le
  /// coffre se retrouvait de nouveau déchiffré en mémoire.
  ///
  /// Les déclencheurs ne sont pas théoriques : passage en arrière-plan avec
  /// verrouillage immédiat, minuterie d'inactivité, « Verrouiller maintenant »,
  /// et surtout `PanicService.panic()`. Le scénario complet : on met à jour
  /// l'instantané d'héritage — ce qui appelle `passwordMatchesPrimary` — et on
  /// déclenche la panique dans la seconde. La panique verrouillait, puis la
  /// vérification rouvrait le coffre derrière elle.
  int _lockGeneration = 0;

  void lock() {
    _lockGeneration++;
    _wipeKey();
    _entries = [];
    _isOpen = false;
    _activeSlot = null;
    // F5 v2.4.3 — wipe cryptographique des bytes (et pas seulement
    // déréférencement) du cache méta. Salt et wrap ne sont pas secrets stricto
    // sensu mais leur concaténation est un fingerprint unique du vault,
    // utilisable pour corréler des dumps mémoire across sessions.
    if (_cachedSalt != null) SecretBytes.wipe(_cachedSalt!);
    if (_cachedWrappedDek != null) SecretBytes.wipe(_cachedWrappedDek!);
    if (_cachedWrapNonce != null) SecretBytes.wipe(_cachedWrapNonce!);
    _cachedSalt = null;
    _cachedWrappedDek = null;
    _cachedWrapNonce = null;
    // Les paramètres ne sont pas un secret (ils sont en clair dans le fichier),
    // mais ils décrivent la session ouverte : ils partent avec elle, sinon un
    // `_saveVault` ultérieur les reporterait sur un coffre qu'ils ne décrivent
    // plus.
    _cachedKdfParams = null;
    // F17 v2.3.7 — reset la référence BiometricStorageFile pour qu'un
    // unlock biométrique post-panic re-acquière le storage proprement
    // (sinon référence dangling vers un fichier potentiellement supprimé).
    _bioFile = null;
    // P0-1 v2.4.0 — purge le snapshot anti-phishing (domaine bancaire
    // courant) côté natif. Sans ça, le domaine reste en RAM ~15 s post-lock,
    // récupérable par instrumentation. Fire-and-forget : le lock() reste
    // synchrone côté caller (auto-lock timer, lifecycle), best-effort.
    unawaited(AntiPhishingService.clearSnapshot());
    // F4 v2.4.3 — purge du clipboard ET annulation du timer auto-clear.
    // Avant : `lock()` (auto-lock timer Settings, lock manuel) laissait un
    // timer pendant qui pouvait re-tirer un callback sur context disposé,
    // et la valeur copiée restait dans le presse-papier jusqu'à expiration.
    // Seul `PanicService.panic()` faisait ce nettoyage — incohérent.
    unawaited(ClipboardService.cancelAndClear());
  }

  // ── CRUD ────────────────────────────────────────────────────────────────────

  Future<void> addEntry(Entry e) async {
    _entries.add(e);
    await _saveVault();
  }

  Future<void> updateEntry(Entry e) async {
    final i = _entries.indexWhere((x) => x.id == e.id);
    if (i >= 0) {
      _entries[i] = e;
      await _saveVault();
    }
  }

  Future<void> deleteEntry(String id) async {
    _entries.removeWhere((e) => e.id == id);
    await _saveVault();
  }

  // ── Settings ────────────────────────────────────────────────────────────────

  String exportJson() => const JsonEncoder.withIndent(
    '  ',
  ).convert(_entries.map((e) => e.toJson()).toList());

  /// SEC F6 v2.5.2 — premier barreau de l'échelle de rembourrage : 64 Kio.
  ///
  /// Volontairement GÉNÉREUX. Le déni plausible exige que les deux
  /// emplacements aient la MÊME taille ; le moyen le plus robuste d'y parvenir
  /// est que tous les coffres réalistes tiennent sur le même barreau. 64 Kio
  /// de JSON représentent plusieurs centaines d'entrées : en pratique un
  /// coffre réel, un coffre leurre réel et un leurre factice sont tous les
  /// trois à 64 Kio, donc rigoureusement indistinguables par la taille.
  /// Le surcoût de stockage est négligeable et le coût AES-GCM sur 64 Kio est
  /// sous la milliseconde.
  static const int _paddingBaseRungBytes = 65536;

  /// Barreau de l'échelle couvrant [bytes]. Progression ×4 : 64 Kio, 256 Kio,
  /// 1 Mio, 4 Mio… Les barreaux sont rares et très espacés, pour que franchir
  /// l'un d'eux reste un événement exceptionnel.
  static int _ladderRungFor(int bytes) {
    var rung = _paddingBaseRungBytes;
    while (rung < bytes) {
      rung *= 4;
    }
    return rung;
  }

  /// Rembourre [plain] par des espaces jusqu'au barreau couvrant à la fois sa
  /// propre longueur et [minPlainBytes] — la longueur de clair de l'AUTRE
  /// emplacement, pour que les deux fichiers coïncident.
  ///
  /// Le remplissage utilise l'espace (0x20) et non des octets nuls :
  /// `jsonDecode` ignore les espaces de fin, donc le clair rembourré se relit
  /// avec le décodeur EXISTANT. Aucun bump de version de format, aucune
  /// migration, et les coffres déjà écrits sans rembourrage continuent de
  /// s'ouvrir — vérifié empiriquement avant implémentation.
  static Uint8List _padToLadder(Uint8List plain, {int minPlainBytes = 0}) {
    final need = plain.length > minPlainBytes ? plain.length : minPlainBytes;
    final target = _ladderRungFor(need);
    final out = Uint8List(target)..fillRange(0, target, 0x20);
    out.setRange(0, plain.length, plain);
    return out;
  }

  /// Longueur du CLAIR de l'emplacement opposé à [slot], sans posséder sa clé.
  ///
  /// AES-GCM préserve la longueur et `cipher.data` vaut `ciphertext || tag`,
  /// donc `longueurClair = base64Decode(data).length - tagBytes`. C'est ce qui
  /// rend l'appariement possible depuis une session leurre, qui ne détient pas
  /// la clé du principal — et c'est aussi, par construction, exactement
  /// l'information dont disposait l'examinateur pour distinguer les deux
  /// fichiers. On s'en sert ici pour la neutraliser.
  ///
  /// Retourne 0 (aucune contrainte) si le fichier est absent, illisible, ou au
  /// format v3 hérité qui n'a pas de champ `cipher`.
  Future<int> _otherSlotPlainLength(_Slot slot) =>
      _plainLengthOf(_otherSlot(slot));

  static _Slot _otherSlot(_Slot slot) =>
      slot == _Slot.primary ? _Slot.decoy : _Slot.primary;

  /// Longueur du CLAIR (rembourrage compris) de l'emplacement [slot], calculée
  /// sans posséder sa clé — voir [_otherSlotPlainLength] pour le raisonnement.
  Future<int> _plainLengthOf(_Slot slot) async {
    try {
      final f = await _vaultFileFor(slot);
      if (!f.existsSync()) return 0;
      final raw = jsonDecode(await f.readAsString());
      if (raw is! Map<String, dynamic>) return 0;
      final cipher = raw['cipher'];
      if (cipher is! Map) return 0;
      final data = cipher['data'];
      if (data is! String) return 0;
      // On calcule la longueur décodée SANS décoder : `_saveVaultV4` appelle
      // cette méthode à chaque écriture du coffre (ajout, édition, suppression
      // d'entrée), et décoder allouerait puis jetterait 64 Kio à chaque fois.
      // base64 : 4 caractères ⇒ 3 octets, moins le bourrage `=`.
      final b64 = data.length;
      if (b64 < 4 || b64 % 4 != 0) return 0;
      var padding = 0;
      if (data.endsWith('==')) {
        padding = 2;
      } else if (data.endsWith('=')) {
        padding = 1;
      }
      final len = (b64 ~/ 4) * 3 - padding - AeadService.tagBytes;
      return len > 0 ? len : 0;
    } catch (_) {
      return 0;
    }
  }

  /// SEC F18 v2.5.4 — étiquette `kek.alias` réellement présente sur disque pour
  /// [slot], sans déchiffrer quoi que ce soit. Sert à détecter les coffres
  /// écrits avant ce correctif, qui portent encore l'alias d'adressage —
  /// c'est-à-dire, pour le leurre, le mot « decoy » en clair.
  ///
  /// Retourne `null` si le fichier est absent, illisible, ou au format v3.
  Future<String?> _fileLabelOnDisk(_Slot slot) async {
    try {
      final f = await _vaultFileFor(slot);
      if (!f.existsSync()) return null;
      final raw = jsonDecode(await f.readAsString());
      if (raw is! Map<String, dynamic>) return null;
      final kek = raw['kek'];
      if (kek is! Map) return null;
      final a = kek['alias'];
      return a is String ? a : null;
    } catch (_) {
      return null;
    }
  }

  /// AUDIT 2026-08-03 — remet les deux emplacements sur le MÊME barreau quand
  /// celui de [slot] a pris du retard.
  ///
  /// SEC F6 rembourre à l'écriture, en s'alignant sur le voisin. Mais il ne
  /// s'aligne QUE quand on écrit : dès que le coffre principal franchit un
  /// barreau (64 Kio de JSON, soit quelques centaines d'entrées), il passe à
  /// 256 Kio pendant que l'autre fichier reste à 64 Kio. Rien ne le rattrapait
  /// ensuite. Pour un utilisateur sans vrai leurre, l'écart devenait
  /// PERMANENT : le leurre factice n'est réécrit qu'à la création du coffre ou
  /// à la réparation du schéma de fichiers.
  ///
  /// Résultat, `ls -l` donnait ~87 Ko contre ~350 Ko — le plus gros étant le
  /// vrai coffre. C'est exactement ce que SEC F6 dit vouloir empêcher : « le
  /// déni plausible que le nommage neutre `_a`/`_b` existe pour offrir tombait
  /// sur un simple `ls -l` ».
  ///
  /// Ce point-ci traite le cas du VRAI leurre : on ne peut réécrire un
  /// emplacement que si l'on détient sa clé, donc au déverrouillage. Le cas du
  /// leurre FACTICE est traité à l'écriture, par
  /// [_realignDummyDecoyIfSmaller], puisqu'il ne sera jamais déverrouillé.
  ///
  /// Best-effort et silencieux : un échec ne doit jamais empêcher l'ouverture.
  Future<void> _realignPaddingIfNeeded(_Slot slot) async {
    try {
      final own = await _plainLengthOf(slot);
      final other = await _plainLengthOf(_otherSlot(slot));
      if (own <= 0 || other <= 0) return;
      if (_ladderRungFor(own) >= _ladderRungFor(other)) return;
      // `_saveVault` rembourre sur le barreau couvrant les DEUX longueurs :
      // le simple fait de réécrire suffit à réaligner.
      await _saveVault();
    } catch (_) {
      /* réessai au prochain déverrouillage */
    }
  }

  /// Pendant de [_realignPaddingIfNeeded] pour le leurre FACTICE, qui n'est
  /// jamais déverrouillé et ne peut donc pas se réaligner tout seul.
  ///
  /// Appelée après chaque écriture du coffre principal. Ne fait rien tant que
  /// les deux fichiers sont sur le même barreau — c'est-à-dire quasiment
  /// toujours, les barreaux étant espacés d'un facteur 4. Quand elle agit, elle
  /// coûte un Argon2id (~1 s) : c'est le prix d'un franchissement de barreau,
  /// événement qui n'arrive qu'une poignée de fois dans la vie d'un coffre.
  ///
  /// ⚠️ GARDE VITALE : ne régénère QUE si l'on sait POSITIVEMENT qu'aucun vrai
  /// leurre n'est configuré. Le drapeau doit valoir exactement `'false'` ; une
  /// lecture qui échoue, qui rend `null` ou toute autre valeur fait renoncer.
  /// Se tromper ici détruirait le second coffre de l'utilisateur, dont le
  /// contenu n'existe nulle part ailleurs.
  Future<void> _realignDummyDecoyIfSmaller({
    required _Slot writtenSlot,
    required int writtenPlainLen,
    required int otherPlainLen,
  }) async {
    try {
      // Seule l'écriture du PRINCIPAL peut entraîner la régénération du leurre
      // factice. Depuis le leurre, on ne détient pas la clé du principal.
      if (writtenSlot != _Slot.primary) return;
      if (_ladderRungFor(otherPlainLen) >= _ladderRungFor(writtenPlainLen)) {
        return;
      }
      // Fail-safe : le drapeau doit valoir EXACTEMENT 'false'. Une lecture
      // absente, vide ou en échec fait renoncer — on ne régénère jamais « dans
      // le doute », puisque se tromper détruirait un vrai coffre leurre.
      final flag = await _storage.read(key: _decoyConfiguredKey);
      if (flag != 'false') {
        return;
      }
      await _createDummyDecoy();
    } catch (_) {
      /* best-effort : nouvelle tentative à la prochaine écriture */
    }
  }

  /// Réécrit le coffre de [slot] si son étiquette de fichier est héritée.
  ///
  /// À appeler juste après un déverrouillage v4 réussi, quand `_key`, `_entries`
  /// et le cache méta sont peuplés. La réécriture change l'étiquette ET l'AAD,
  /// donc re-chiffre — mais le `wrappedDek` reste valide : l'alias
  /// d'ADRESSAGE du Keystore, lui, n'a pas bougé. C'est tout l'intérêt du
  /// découplage.
  ///
  /// Best-effort et silencieux : un échec de réécriture ne doit JAMAIS
  /// empêcher l'ouverture du coffre. La prochaine tentative aura lieu au
  /// déverrouillage suivant.
  Future<void> _migrateFileLabelIfLegacy(_Slot slot) async {
    try {
      final onDisk = await _fileLabelOnDisk(slot);
      if (onDisk == null || onDisk == _fileLabelFor(slot)) return;
      final salt = _cachedSalt;
      final wrappedDek = _cachedWrappedDek;
      final wrapNonce = _cachedWrapNonce;
      // AUDIT 2026-08-03 — les paramètres du coffre OUVERT sont reportés tels
      // quels : cette réécriture ne redérive rien, elle ne fait que changer
      // l'étiquette. Y écrire les constantes annoncerait une dérivation que la
      // clé en cours ne respecte pas.
      final params = _cachedKdfParams;
      if (salt == null ||
          wrappedDek == null ||
          wrapNonce == null ||
          params == null) {
        return;
      }
      await _saveVaultV4(
        slot: slot,
        salt: salt,
        wrappedDek: wrappedDek,
        wrapNonce: wrapNonce,
        params: params,
      );
    } catch (_) {
      /* réessai au prochain déverrouillage */
    }
  }

  /// Entrées de test pour le rembourrage, privé à la bibliothèque. Le
  /// rembourrage conditionne le déni plausible : il doit être couvert par des
  /// tests, pas seulement par relecture.
  @visibleForTesting
  static Uint8List padToLadderForTest(
    Uint8List plain, {
    int minPlainBytes = 0,
  }) => _padToLadder(plain, minPlainBytes: minPlainBytes);

  @visibleForTesting
  static int get paddingBaseRungForTest => _paddingBaseRungBytes;

  /// Entrée de test pour le calcul de longueur base64 sans décodage.
  @visibleForTesting
  static int decodedLenFromBase64ForTest(String b64) {
    final n = b64.length;
    if (n < 4 || n % 4 != 0) return 0;
    var padding = 0;
    if (b64.endsWith('==')) {
      padding = 2;
    } else if (b64.endsWith('=')) {
      padding = 1;
    }
    return (n ~/ 4) * 3 - padding;
  }

  /// SEC F16 v2.5.2 — purge tout résidu `*_v3.enc.bak`.
  ///
  /// `_migrateV3ToV4` copie le fichier v3 en `.bak` SANS condition, mais ne le
  /// purge qu'à la toute fin de son `try`. Or `_saveVaultV4` termine son
  /// renommage atomique AVANT l'écriture du stockage sécurisé qui suit : une
  /// exception dans cette fenêtre laisse le fichier en v4 pour toujours, donc
  /// `_migrateV3ToV4` n'est plus jamais atteignable — les chemins de
  /// déverrouillage prennent la branche v4 — et le `.bak` ne peut plus être
  /// purgé par le flux normal.
  ///
  /// Ce résidu est une copie COMPLÈTE du coffre chiffrée en PBKDF2 + AES-CBC
  /// à partir du seul mot de passe maître, sans liaison Keystore : attaquable
  /// hors ligne à la vitesse GPU, là où le fichier v4 voisin résiste grâce à
  /// Argon2id et au `hwSecret` lié au TEE.
  ///
  /// Appelé à CHAQUE déverrouillage réussi, pour qu'aucun résidu ne survive à
  /// un échec quelconque — plutôt que de dépendre du seul chemin nominal.
  Future<void> _purgeLegacyV3Backups() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      for (final ent in dir.listSync(followLinks: false)) {
        if (ent is File && ent.path.endsWith('_v3.enc.bak')) _shredSync(ent);
      }
    } catch (_) {
      /* best-effort : ne doit jamais faire échouer un déverrouillage */
    }
  }

  /// Point d'entrée PUBLIC du déchiquetage de fichier.
  ///
  /// AUDIT 2026-08-03 — il existait trois traitements distincts pour le même
  /// besoin : `_shredSync` ici, une copie approchante dans l'écran Réglages
  /// (`_shredFile`, sans le repli en cas d'échec d'écrasement), et RIEN du tout
  /// dans `HeritageService.disable()`, qui se contentait d'un `deleteSync()`
  /// alors que `deleteVault` déchiquette explicitement ce même `pt_heir.enc`.
  /// Trois niveaux de rigueur pour supprimer des fichiers de même sensibilité.
  ///
  /// Une seule définition désormais, et c'est la plus stricte des trois.
  static void shredFileSync(File file) => _shredSync(file);

  /// Écrase le contenu d'un fichier par des octets aléatoires avant l'unlink,
  /// pour qu'une récupération des blocs sous-jacents ne rende pas le clair.
  /// Best-effort : sur un système de fichiers à copie sur écriture (F2FS,
  /// couche flash) l'écrasement ne garantit pas la destruction physique.
  static void _shredSync(File file) {
    try {
      if (!file.existsSync()) return;
      final len = file.lengthSync();
      if (len > 0) {
        final rnd = Random.secure();
        file.writeAsBytesSync(
          Uint8List.fromList(List<int>.generate(len, (_) => rnd.nextInt(256))),
          flush: true,
        );
      }
      file.deleteSync();
    } catch (_) {
      // Best-effort : on tente quand même l'unlink si l'écrasement a échoué.
      try {
        if (file.existsSync()) file.deleteSync();
      } catch (_) {}
    }
  }

  /// AUDIT 2026-08-03 — `decoySessionDeleteRefused` a été SUPPRIMÉE.
  ///
  /// Cette sentinelle faisait lever [deleteDecoyVault] quand l'appel venait
  /// d'une session leurre, et l'écran Réglages la traduisait en « opération
  /// indisponible ». Or la v2.5.4 avait justement retiré ce refus opaque de
  /// [deleteVault] (SEC F12), avec ce raisonnement — qui vaut mot pour mot
  /// ici : « un message "opération indisponible" est en soi une anomalie ;
  /// sous contrainte, il signale à l'adversaire que quelque chose est protégé,
  /// et le propriétaire légitime le lit comme une panne ».
  ///
  /// Le raisonnement n'avait simplement jamais été propagé à cette
  /// fonction-ci. Il l'est désormais : voir [DecoyDeleteOutcome].

  /// SEC F10 v2.5.2 — sentinelle levée par `changeMasterPassword` quand le mot
  /// de passe actuel fourni ne correspond pas. L'appelant doit la traduire en
  /// message dédié plutôt que d'exposer la sentinelle brute.
  static const wrongCurrentPassword = 'pt_change_pwd_wrong_current';

  /// SEC-R1 v2.5.2 — une opération concurrente détient déjà `_unlockGate`.
  /// L'appelant doit inviter l'utilisateur à réessayer, sans rien muter.
  static const vaultBusy = 'pt_vault_busy';

  Future<VaultDeleteOutcome> deleteVault() async {
    // SEC F12 v2.5.2 — Avant : `deleteVault` ne consultait NI `_activeSlot` NI
    // aucune réauthentification, et son unique appelant l'exposait derrière un
    // simple dialogue de confirmation, sans garde `isDecoyActive`. Une session
    // ouverte avec le mot de passe LEURRE pouvait donc anéantir le coffre
    // principal : `_keystore.deleteAll()` détruit les DEUX KEK liées au TEE,
    // rendant même une copie antérieure de `pt_vault_a.enc` définitivement
    // indéchiffrable. C'est l'inverse exact de l'objet du leurre — la victime
    // livre le mot de passe leurre en comptant sur le fait que le vrai coffre
    // reste sûr ET caché.
    //
    // v2.5.4 — le refus opaque introduit alors est REMPLACÉ. Un message
    // « opération indisponible » est en soi une anomalie : sous contrainte, il
    // signale à l'adversaire que quelque chose est protégé, et le propriétaire
    // légitime le lit comme une panne (constaté par Patrice le 2026-07-31).
    // Désormais, depuis une session leurre, la suppression PORTE sur le seul
    // emplacement leurre. La personne qui manipule l'app obtient ce qu'elle
    // demande, et le coffre principal reste intact et invisible.
    if (_activeSlot != _Slot.primary) {
      // Ordre IMPÉRATIF : verrouiller AVANT d'écraser. `_entries` et `_key`
      // tiennent encore le contenu du leurre ; tout CRUD survenant entre
      // l'écrasement et le verrouillage le réécrirait par-dessus le leurre
      // factice. C'est exactement le risque que documente la garde SEC-R3 de
      // `deleteDecoyVault`.
      lock();
      await _createDummyDecoy();
      await _storage.write(key: _decoyConfiguredKey, value: 'false');
      return VaultDeleteOutcome.decoyOnly;
    }
    lock();
    final dir = await getApplicationDocumentsDirectory();
    // SEC F1 v2.5.2 — Avant : liste de noms CODÉE EN DUR, qui oubliait
    // `pt_heir.enc` (l'instantané Héritage). Après « Tout supprimer », une
    // copie déchiffrable de CHAQUE entrée survivait donc dans le stockage
    // privé — et contrairement au coffre principal, cet instantané est dérivé
    // du seul mot de passe héritier SANS liaison hwSecret/TEE : il est
    // attaquable hors ligne depuis une simple copie de fichier. Le dialogue
    // promettait pourtant une suppression définitive.
    // Désormais : on ÉNUMÈRE les artefacts `pt_*` du répertoire documents,
    // pour que tout artefact futur soit couvert par défaut au lieu d'attendre
    // qu'on pense à l'ajouter ici.
    try {
      for (final ent in dir.listSync(followLinks: false)) {
        if (ent is! File) continue;
        final name = ent.uri.pathSegments.last;
        if (name.startsWith('pt_')) _shredSync(ent);
      }
    } catch (_) {
      /* Répertoire illisible : on retombe sur la liste explicite ci-dessous. */
    }
    // Filet de sécurité si l'énumération a échoué (et couvre les `.bak` v3).
    for (final name in const [
      'pt_vault_a.enc',
      'pt_vault_b.enc',
      'pt_vault.enc',
      'pt_vault_decoy.enc',
      'pt_heir.enc',
    ]) {
      _shredSync(File('${dir.path}/$name'));
      _shredSync(File('${dir.path}/${name}_v3.enc.bak'));
    }
    for (final slot in _Slot.values) {
      await _storage.delete(key: _saltKeyFor(slot));
    }
    // v2.5.x (H1) — efface le flag decoy (repart à zéro).
    await _storage.delete(key: _decoyConfiguredKey);
    // SEC F1 v2.5.2 — état Héritage : sans ça, `pt_heir_enabled` restait à '1'
    // et le bouton « Accès héritier » réapparaissait sur l'écran de
    // déverrouillage après le délai de grâce, servant l'ancien coffre.
    for (final k in const [
      'pt_heir_salt',
      'pt_heir_enabled',
      'pt_heir_threshold_days',
      'pt_heir_grace_start_ts',
      'pt_last_active_ts',
    ]) {
      await _storage.delete(key: k);
    }
    await deleteBiometricKey();
    // v4 : détruit aussi les 2 KEK keystore (decision #4).
    try {
      await _keystore.deleteAll();
    } catch (_) {
      /* Keystore inaccessible : best-effort */
    }
    await _storage.delete(key: _failCountKey);
    await _storage.delete(key: _lockoutKey);
    // SEC F5/F17 v2.5.2 — le nouveau format doit être purgé ici aussi, sinon
    // un verrouillage survivait à la suppression complète du coffre.
    await _storage.delete(key: _lockoutRemainingKey);
    await _storage.delete(key: _lockoutAnchorKey);
    // SEC F17 v2.5.2 — `pt_max_seen_ms` est un plancher persisté qui ne
    // décroît JAMAIS. Une valeur très future observée une fois (horloge réglée
    // à 2099 puis corrigée) restait le « maintenant » de l'app indéfiniment.
    // Ne pas l'effacer ici laissait un coffre fraîchement recréé hériter de ce
    // plancher empoisonné.
    await _storage.delete(key: 'pt_max_seen_ms');
    return VaultDeleteOutcome.fullWipe;
  }

  /// Désactive le VRAI coffre leurre sans toucher au primary. Utilisé depuis
  /// Settings.
  ///
  /// v2.5.x (H1) — ne SUPPRIME PLUS le fichier `_b` (le profil de fichiers doit
  /// rester constant : sinon la disparition du 2ᵉ fichier prouverait au repos
  /// qu'un decoy réel a existé puis été retiré). On l'ÉCRASE par un leurre
  /// factice non-déverrouillable et on remet le flag à 'false'. Après ça, un
  /// attaquant forensic voit toujours 2 fichiers indistinguables et ne peut
  /// pas prouver qu'un decoy réel a existé.
  Future<DecoyDeleteOutcome> deleteDecoyVault() async {
    // SEC-R3 v2.5.2 — le danger identifié reste entièrement valable : cette
    // fonction écrase `pt_vault_b.enc` par un leurre factice dont le mot de
    // passe aléatoire n'est JAMAIS persisté, donc le fichier devient
    // définitivement indéchiffrable. Or la section « Coffre leurre » des
    // Réglages s'affiche selon `hasDecoyVault`, un drapeau GLOBAL et non
    // l'emplacement actif : on peut donc demander cette suppression depuis la
    // session leurre elle-même, et écraser le coffre qu'on vient d'ouvrir.
    //
    // AUDIT 2026-08-03 — la parade change de nature, pas de rigueur.
    // Le refus opaque est remplacé par le traitement gracieux que SEC F12 a
    // introduit pour `deleteVault` : on fait ce qui est demandé, mais dans le
    // bon ordre. Un « opération indisponible » sous contrainte trahit qu'il y a
    // quelque chose à protéger ; ici, la personne qui manipule l'app obtient
    // exactement ce qu'elle demande — l'emplacement leurre disparaît — et le
    // coffre principal reste intact et invisible.
    if (_activeSlot == _Slot.decoy) {
      // Ordre IMPÉRATIF, identique à `deleteVault` : verrouiller AVANT
      // d'écraser. `_entries` et `_key` tiennent encore le contenu du leurre ;
      // tout CRUD survenant entre l'écrasement et le verrouillage le
      // réécrirait par-dessus le leurre factice qu'on vient de poser.
      lock();
      await _createDummyDecoy();
      await _storage.write(key: _decoyConfiguredKey, value: 'false');
      return DecoyDeleteOutcome.sessionLocked;
    }
    await _createDummyDecoy();
    await _storage.write(key: _decoyConfiguredKey, value: 'false');
    return DecoyDeleteOutcome.keptSession;
  }

  // ── Internal helpers (paths) ────────────────────────────────────────────────

  Future<File> _vaultFileFor(_Slot slot) async {
    final dir = await getApplicationDocumentsDirectory();
    // v2.5.x (H1) — noms neutres indistinguables. Avant : `pt_vault.enc` /
    // `pt_vault_decoy.enc` — le suffixe `_decoy` désignait au forensic quel
    // fichier était le vrai coffre (déni plausible cassé au repos). `_a`
    // (ex-primary) / `_b` (ex-decoy) sont indistinguables ; combinés au leurre
    // factice toujours présent (`ensureVaultLayout`), un attaquant ne peut plus
    // prouver l'existence d'un second coffre réel à partir des fichiers.
    final newFile = File(
      '${dir.path}/${slot == _Slot.primary ? 'pt_vault_a.enc' : 'pt_vault_b.enc'}',
    );
    // FALLBACK rétro-compat — filet de sécurité « sans rien casser » : si la
    // migration de noms (`ensureVaultLayout`) n'a pas encore tourné OU a échoué
    // (IO/Keystore), l'ancien fichier peut être le SEUL présent. On retombe
    // dessus tant que le nouveau n'existe pas → on ne perd JAMAIS l'accès au
    // coffre (et donc jamais de fausse détection « pas de coffre » qui
    // conduirait à un écrasement au setup). La migration renommera au prochain
    // boot réussi.
    if (!newFile.existsSync()) {
      final oldFile = File(
        '${dir.path}/${slot == _Slot.primary ? 'pt_vault.enc' : 'pt_vault_decoy.enc'}',
      );
      if (oldFile.existsSync()) return oldFile;
    }
    return newFile;
  }

  String _saltKeyFor(_Slot slot) =>
      slot == _Slot.primary ? _saltKey : _decoySaltKey;

  // ── Memory hygiene ──────────────────────────────────────────────────────────

  void _wipeKey() {
    if (_key != null) {
      for (int i = 0; i < _key!.length; i++) {
        _key![i] = 0;
      }
      _key = null;
    }
  }

  // ── Crypto helpers (statics — re-utilisés par les parts) ────────────────────
  //
  // v2.2.0 : les shims `_zero / _constEq / _randomBytes` ont été supprimés.
  // Les parts utilisent désormais `SecretBytes.*` directement (cf. v2.1.1 où
  // l'helper a été centralisé dans `files_tech_core`).
  //
  // M-2 (note de sécurité conservée) : `SecretBytes.constantTimeEq` retourne
  // tôt si les longueurs diffèrent. C'est inoffensif tant qu'on l'utilise sur
  // des HMAC/AEAD tags de taille fixe (cas de tous les callsites de la lib).
  // Ne PAS l'utiliser pour comparer des secrets de longueur variable.

  static Future<Uint8List> _deriveKey(
    String password,
    List<int> salt,
    int iterations,
  ) async =>
      compute(pbkdf2Worker, [utf8.encode(password), salt, iterations, 64]);
}
