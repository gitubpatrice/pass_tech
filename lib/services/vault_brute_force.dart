// Protection brute-force du vault : compteur d'échecs + lockout exponentiel.
//
// Ce fichier est une `part` de la library `vault_service`. Il regroupe :
//  - `getLockoutRemaining` : combien de secondes encore avant de pouvoir
//    retenter un unlock,
//  - `_onUnlockFail` : incrémente le compteur, déclenche un lockout
//    selon la table `_lockoutSteps`,
//  - `_onUnlockSuccess` : reset compteur + lockout.
//
// Le state est persisté dans flutter_secure_storage via les clés `pt_fail_count`
// et `pt_lockout_until`, partagées avec le reste de VaultService.

part of 'vault_service.dart';

extension VaultBruteForce on VaultService {
  /// Clé interne pour stocker le timestamp max-seen (anti-rollback clock).
  /// F1 v2.3.7 — protection contre clock-skew bypass : si l'attaquant
  /// recule l'horloge système après le set du lockout, `now < until`
  /// resterait vrai éternellement (lockout permanent — DOS) ou la
  /// fenêtre se réinitialise (bypass). On stocke le timestamp max
  /// jamais vu et on l'utilise comme borne inférieure de "now".
  static const String _kMaxSeenMs = 'pt_max_seen_ms';

  /// "Now" robuste au clock skew : retourne `max(DateTime.now(), maxSeen)`
  /// et persiste la valeur courante. Recule de l'horloge → on garde
  /// la valeur précédente comme "now" (l'attaquant ne peut pas
  /// remonter le temps). Avance de l'horloge → on accepte (fail-open
  /// désirable côté UX, mais limité par la borne du lockout).
  Future<int> _monotonicNow() async {
    final realNow = DateTime.now().millisecondsSinceEpoch;
    final s = await VaultService._storage.read(key: _kMaxSeenMs);
    final maxSeen = int.tryParse(s ?? '0') ?? 0;
    final now = realNow > maxSeen ? realNow : maxSeen;
    if (now > maxSeen) {
      await VaultService._storage.write(
        key: _kMaxSeenMs,
        value: now.toString(),
      );
    }
    return now;
  }

  /// Returns remaining lockout in seconds, or null if not locked out.
  Future<int?> getLockoutRemaining() async {
    final s = await VaultService._storage.read(key: VaultService._lockoutKey);
    if (s == null) return null;
    final until = int.tryParse(s) ?? 0;
    final now = await _monotonicNow();
    if (now >= until) return null;
    return ((until - now) / 1000).ceil();
  }

  Future<void> _onUnlockFail() async {
    final s = await VaultService._storage.read(key: VaultService._failCountKey);
    final count = (int.tryParse(s ?? '0') ?? 0) + 1;
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
      final until = (await _monotonicNow()) + lockSec * 1000;
      await VaultService._storage.write(
        key: VaultService._lockoutKey,
        value: until.toString(),
      );
    }
  }

  Future<void> _onUnlockSuccess() async {
    await VaultService._storage.delete(key: VaultService._failCountKey);
    await VaultService._storage.delete(key: VaultService._lockoutKey);
  }
}
