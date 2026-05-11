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
  /// Returns remaining lockout in seconds, or null if not locked out.
  Future<int?> getLockoutRemaining() async {
    final s = await VaultService._storage.read(key: VaultService._lockoutKey);
    if (s == null) return null;
    final until = int.tryParse(s) ?? 0;
    final now = await MonotonicClock.nowMs();
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
      final until = (await MonotonicClock.nowMs()) + lockSec * 1000;
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
