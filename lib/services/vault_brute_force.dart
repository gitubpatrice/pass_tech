// Protection brute-force du vault : compteur d'échecs + lockout exponentiel.
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - `getLockoutRemaining` : combien de secondes encore avant de pouvoir
//    retenter un unlock,
//  - `_onUnlockFail` : incrémente le compteur, déclenche un lockout
//    selon la table `_lockoutSteps`,
//  - `_onUnlockSuccess` : reset compteur + lockout.
//
// Le state est persisté dans flutter_secure_storage, partagé avec le reste de
// VaultService :
//  - `pt_fail_count`          : compteur d'échecs consécutifs.
//  - `pt_lockout_remaining_ms`: durée de verrouillage restante (format courant,
//    SEC F5/F17) — ancrée sur `SystemClock.elapsedRealtime()`, insensible aux
//    manipulations de l'horloge murale.
//  - `pt_lockout_anchor_ms`   : valeur d'elapsedRealtime au moment de l'écriture.
//  - `pt_lockout_until`       : ANCIEN format (échéance absolue sur horloge
//    murale). Lu uniquement en repli — pour les installations verrouillées au
//    moment de la mise à jour, et quand elapsedRealtime est indisponible.

part of 'vault_service.dart';

extension VaultBruteForce on VaultService {
  /// Returns remaining lockout in seconds, or null if not locked out.
  ///
  /// SEC F5/F17 v2.5.2 — le verrouillage est désormais ancré sur
  /// `SystemClock.elapsedRealtime()`, pas sur l'horloge murale.
  ///
  /// Avant : l'échéance était un horodatage ABSOLU comparé à
  /// `MonotonicClock.nowMs()`, qui rend `max(DateTime.now(), maxSeen)`. La
  /// monotonie ne jouait donc que contre les RECULS ; les avances passaient
  /// telles quelles et étaient même persistées comme nouveau `maxSeen`. Or
  /// l'avance est précisément la direction attaquante : Réglages Android →
  /// Date et heure → avancer d'un jour, et `getLockoutRemaining()` retournait
  /// `null`. Le seul frein protégeant le mot de passe maître — unique secret
  /// du coffre — se réinitialisait à volonté, ramenant le coût d'une campagne
  /// de devinettes à un Argon2id par essai au lieu de 48 essais par jour.
  ///
  /// Désormais on persiste une DURÉE RESTANTE plus une ancre elapsedRealtime,
  /// et on décompte le temps réellement écoulé depuis l'ancre.
  Future<int?> getLockoutRemaining() async {
    final remainingRaw = await VaultService._storage.read(
      key: VaultService._lockoutRemainingKey,
    );
    final anchorRaw = await VaultService._storage.read(
      key: VaultService._lockoutAnchorKey,
    );

    if (remainingRaw == null || anchorRaw == null) {
      // Rétro-compatibilité : installation verrouillée par une version
      // antérieure, qui n'a écrit que l'horodatage absolu. On l'honore une
      // dernière fois ; le prochain échec réécrira au nouveau format.
      return _legacyLockoutRemaining();
    }

    final remainingMs = int.tryParse(remainingRaw) ?? 0;
    final anchorMs = int.tryParse(anchorRaw) ?? 0;
    if (remainingMs <= 0) return null;

    final nowElapsed = await MonotonicClock.elapsedRealtimeMs();
    if (nowElapsed == null) {
      // Plateforme sans elapsedRealtime : on ne peut pas décompter, donc on
      // maintient le verrouillage entier. Fail-CLOSED — un attaquant ne doit
      // jamais gagner à faire échouer le canal.
      return (remainingMs / 1000).ceil();
    }

    // Recul de l'ancre = redémarrage de l'appareil (elapsedRealtime repart de
    // zéro). On ne décompte RIEN : un reboot ne doit pas raccourcir le
    // verrouillage. On ré-ancre pour que le décompte reprenne proprement.
    if (nowElapsed < anchorMs) {
      await VaultService._storage.write(
        key: VaultService._lockoutAnchorKey,
        value: nowElapsed.toString(),
      );
      return (remainingMs / 1000).ceil();
    }

    final left = remainingMs - (nowElapsed - anchorMs);
    if (left <= 0) {
      await VaultService._storage.delete(
        key: VaultService._lockoutRemainingKey,
      );
      await VaultService._storage.delete(key: VaultService._lockoutAnchorKey);
      return null;
    }
    return (left / 1000).ceil();
  }

  /// Lit l'ancien format (horodatage absolu sur horloge murale). Conservé
  /// uniquement pour ne pas déverrouiller d'un coup les installations
  /// verrouillées au moment de la mise à jour.
  Future<int?> _legacyLockoutRemaining() async {
    final s = await VaultService._storage.read(key: VaultService._lockoutKey);
    if (s == null) return null;
    final until = int.tryParse(s) ?? 0;
    final now = await MonotonicClock.nowMs();
    if (now >= until) return null;
    return ((until - now) / 1000).ceil();
  }

  /// SEC 2026-08-03 (Gemini PT-002) — provisionne l'échec AVANT de tenter.
  ///
  /// `_onUnlockFail` n'est appelé qu'APRÈS la vérification, donc après une à
  /// deux secondes d'Argon2id. Il suffit de tuer l'application pendant ce
  /// calcul — depuis le sélecteur d'applications récentes, ou par script — pour
  /// que l'essai ne soit **jamais comptabilisé**. Répété, cela supprime
  /// purement et simplement le verrouillage progressif : il ne reste que le
  /// coût d'Argon2id, alors que tout l'intérêt du verrouillage est justement
  /// d'ajouter une barrière que le calcul seul n'offre pas.
  ///
  /// La parade est classique : on écrit l'échec AVANT, et on l'annule
  /// seulement si la vérification réussit ([_onUnlockSuccess] efface déjà le
  /// compteur). Une interruption laisse donc l'essai compté — fail-closed.
  ///
  /// ⚠️ Ne déclenche PAS le verrouillage lui-même : seul `_onUnlockFail`,
  /// appelé après un échec avéré, arme le compte à rebours. Un utilisateur
  /// légitime dont le déverrouillage aboutit ne voit jamais ce compteur.
  Future<void> _reserveUnlockAttempt() async {
    try {
      final s = await VaultService._storage.read(
        key: VaultService._failCountKey,
      );
      final count = ((int.tryParse(s ?? '0') ?? 0) + 1).clamp(0, 1000);
      await VaultService._storage.write(
        key: VaultService._failCountKey,
        value: count.toString(),
      );
    } catch (_) {
      /* best-effort : ne doit jamais empêcher une tentative légitime */
    }
  }

  /// [alreadyReserved] : l'essai a déjà été provisionné par
  /// [_reserveUnlockAttempt] avant la vérification. On se contente alors de
  /// lire le compteur pour armer le verrouillage, sans le réincrémenter — sinon
  /// chaque échec compterait double.
  Future<void> _onUnlockFail({bool alreadyReserved = false}) async {
    final s = await VaultService._storage.read(key: VaultService._failCountKey);
    // F6 v2.4.4 — clamp à 1000 max. Avant : un attaquant qui spam `unlock()`
    // pendant des heures montait le compteur à des dizaines de milliers ;
    // aucun impact crypto (lockout step toujours plafonné à 30 min via
    // `_lockoutSteps.length - 1`) mais usure NAND et pollution storage.
    final previous = int.tryParse(s ?? '0') ?? 0;
    // Si la réservation a échoué (stockage momentanément indisponible), on lit
    // 0 alors qu'un essai vient bel et bien d'échouer. Compter au moins 1 :
    // sans ce plancher, une erreur d'écriture désarmerait le verrouillage —
    // un repli du mauvais côté, exactement le défaut que SEC F5/F17 a corrigé
    // sur le compte à rebours.
    final count = alreadyReserved
        ? (previous < 1 ? 1 : previous).clamp(0, 1000)
        : (previous + 1).clamp(0, 1000);
    await VaultService._storage.write(
      key: VaultService._failCountKey,
      value: count.toString(),
    );

    if (count >= VaultService._failThreshold) {
      final stepIdx = (count - VaultService._failThreshold).clamp(
        0,
        VaultService._lockoutSteps.length - 1,
      );
      final lockSec = VaultService._lockoutSteps[stepIdx];
      // SEC F5/F17 v2.5.2 — on persiste une DURÉE plus une ancre monotone, et
      // non une échéance absolue sur l'horloge murale (avançable à volonté).
      final anchor = await MonotonicClock.elapsedRealtimeMs();
      if (anchor == null) {
        // Pas d'ancre monotone disponible à l'écriture. On NE DOIT PAS écrire
        // une ancre bidon : une ancre à 0 ferait consommer, à la première
        // lecture réussie, tout le temps écoulé depuis le démarrage — le
        // verrouillage serait levé instantanément. Fail-OUVERT, exactement le
        // défaut qu'on corrige. On retombe donc sur l'ancien format horloge
        // murale, moins bon mais cohérent, et on purge le nouveau pour que la
        // lecture emprunte bien le chemin hérité.
        final until = (await MonotonicClock.nowMs()) + lockSec * 1000;
        await VaultService._storage.write(
          key: VaultService._lockoutKey,
          value: until.toString(),
        );
        await VaultService._storage.delete(
          key: VaultService._lockoutRemainingKey,
        );
        await VaultService._storage.delete(key: VaultService._lockoutAnchorKey);
        return;
      }
      await VaultService._storage.write(
        key: VaultService._lockoutRemainingKey,
        value: (lockSec * 1000).toString(),
      );
      await VaultService._storage.write(
        key: VaultService._lockoutAnchorKey,
        value: anchor.toString(),
      );
      // L'ancien horodatage ne doit plus traîner : sinon le repli
      // rétro-compatible pourrait le relire et raccourcir le verrouillage.
      await VaultService._storage.delete(key: VaultService._lockoutKey);
    }
  }

  Future<void> _onUnlockSuccess() async {
    await VaultService._storage.delete(key: VaultService._failCountKey);
    await VaultService._storage.delete(key: VaultService._lockoutKey);
    await VaultService._storage.delete(key: VaultService._lockoutRemainingKey);
    await VaultService._storage.delete(key: VaultService._lockoutAnchorKey);
    // SEC F16 v2.5.2 — filet de rattrapage sur les sauvegardes v3.
    await _purgeLegacyV3Backups();
  }
}
