import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// A6/A7 v2.3.8 — horloge monotone résistante au clock-skew.
///
/// Source du temps `DateTime.now().millisecondsSinceEpoch` peut être
/// manipulée côté root (recul horloge système) ou affectée par NTP sync.
/// Pour les chemins anti-bruteforce (lockout) et heritage (dead-man
/// switch), on persiste un "rolling max" du timestamp jamais vu et on
/// l'utilise comme borne inférieure de "now" — l'attaquant ne peut
/// reculer le temps qu'observe l'app.
///
/// **Limites** :
/// - Avance d'horloge non détectée (accepte donc fail-open : l'attaquant
///   peut accélérer la grâce heritage en avançant l'horloge ; on accepte
///   ce compromis car le vrai master/heir password reste nécessaire).
/// - Le clear root des secure storage reset le max-seen ; combiné avec
///   reset compteur lockout, c'est la limite OWASP Mobile (root = game over).
class MonotonicClock {
  MonotonicClock._();

  static const _kMaxSeenMs = 'pt_max_seen_ms';
  // flutter_secure_storage 10.x : EncryptedSharedPreferences est déprécié,
  // migration automatique vers custom ciphers au premier accès.
  static const _storage = FlutterSecureStorage(
    iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
  );

  /// "Now" robuste au clock skew : retourne `max(DateTime.now(), maxSeen)`
  /// et persiste la valeur courante. Recul de l'horloge → on garde
  /// l'ancien max comme "now". Avance acceptée (fail-open).
  ///
  /// F10 v2.4.4 — sérialisation via un Future cache pour éviter les races
  /// `read → max → write` non-atomiques quand 2 callers entrent en
  /// concurrence (auto-lock timer + unlock + heritage markActive). Dart est
  /// mono-thread sur un isolate donc le test/write SYNC est atomique, mais
  /// les `await` autour de `_storage.read/write` ouvrent des fenêtres de
  /// concurrence inter-microtâches. Sans guard, un write tardif pouvait
  /// écraser une valeur plus récente d'un autre caller, brisant l'invariant
  /// "rolling max jamais vu". Inoffensif sécuritairement (delta négligeable)
  /// mais documente l'intention.
  static Future<int>? _pending;
  static Future<int> nowMs() {
    final p = _pending;
    if (p != null) {
      return p.then(_continueAfter);
    }
    final c = _pending = _nowMsInternal();
    return c.whenComplete(() {
      if (identical(_pending, c)) _pending = null;
    });
  }

  /// Quand un appel concurrent a déjà résolu la valeur "now" (a fait le
  /// read+write storage), on peut juste relire le `realNow` actuel et le
  /// borner par l'ancien max — pas besoin d'un 2ᵉ aller-retour storage.
  static Future<int> _continueAfter(int previous) async {
    final realNow = DateTime.now().millisecondsSinceEpoch;
    return realNow > previous ? realNow : previous;
  }

  static Future<int> _nowMsInternal() async {
    final realNow = DateTime.now().millisecondsSinceEpoch;
    final s = await _storage.read(key: _kMaxSeenMs);
    final maxSeen = int.tryParse(s ?? '0') ?? 0;
    final now = realNow > maxSeen ? realNow : maxSeen;
    if (now > maxSeen) {
      await _storage.write(key: _kMaxSeenMs, value: now.toString());
    }
    return now;
  }
}
