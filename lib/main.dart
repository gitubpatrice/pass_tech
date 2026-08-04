import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'l10n/app_localizations.dart';
import 'services/app_update.dart';
import 'services/clipboard_service.dart';
import 'services/first_launch_flag.dart';
import 'services/monotonic_clock.dart';
import 'services/panic_service.dart';
import 'services/secure_window.dart';
import 'services/vault_service.dart';
import 'screens/onboarding_screen.dart';
import 'screens/setup_screen.dart';
import 'screens/splash_screen.dart';
import 'screens/unlock_screen.dart';

final _navigatorKey = GlobalKey<NavigatorState>();

/// Global theme mode notifier — listened by the app, updated by settings.
final ValueNotifier<ThemeMode> themeNotifier = ValueNotifier(ThemeMode.system);

/// Global locale notifier — `null` means follow the system locale.
/// Updated by settings, listened by the app.
final ValueNotifier<Locale?> localeNotifier = ValueNotifier(null);

const String prefKeyLocale = 'app_locale';

/// Parse a locale code from prefs into a `Locale?`.
/// `'system'` or unknown → null (follow system).
Locale? parseLocale(String? code) {
  switch (code) {
    case 'fr':
      return const Locale('fr');
    case 'en':
      return const Locale('en');
    default:
      return null;
  }
}

/// Serialize a `Locale?` for prefs. `null` → `'system'`.
String localeToString(Locale? l) {
  if (l == null) return 'system';
  return l.languageCode;
}

ThemeMode parseThemeMode(String s) {
  switch (s) {
    case 'light':
      return ThemeMode.light;
    case 'dark':
      return ThemeMode.dark;
    default:
      return ThemeMode.system;
  }
}

String themeModeToString(ThemeMode m) {
  switch (m) {
    case ThemeMode.light:
      return 'light';
    case ThemeMode.dark:
      return 'dark';
    case ThemeMode.system:
      return 'system';
  }
}

/// v2.3.11 — clé SharedPreferences pour la protection captures d'écran
/// (FLAG_SECURE). Default = true (protection active). L'utilisateur peut
/// désactiver via Réglages → Sécurité pour permettre le paste cross-app
/// sur Samsung (Knox bloque le clipboard quand FLAG_SECURE est actif).
const String prefKeyScreenshotProtection = 'screenshot_protection_enabled';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(statusBarColor: Colors.transparent),
  );

  final prefs = await SharedPreferences.getInstance();
  // v2.3.11 — lit la pref AVANT init() pour que la valeur user-controlée
  // soit appliquée dès le boot. Si l'utilisateur a explicitement désactivé
  // la protection, on n'applique PAS FLAG_SECURE → Knox ne marque pas
  // l'app comme "secure" → le clipboard cross-app fonctionne.
  final screenshotProtection =
      prefs.getBool(prefKeyScreenshotProtection) ?? true;
  await SecureWindow.applyUserPreference(enabled: screenshotProtection);
  // Pose FLAG_SECURE depuis Dart APRÈS création de la window si l'utilisateur
  // n'a pas désactivé la protection. Sans ce timing post-window-creation,
  // Samsung One UI + Knox refuse de propager les clearFlags ultérieurs.
  //
  // P0-3 v2.4.0 — `await` (au lieu de fire-and-forget v2.3.11) : sans ce
  // await, un utilisateur rapide pourrait naviguer entre `runApp` et la
  // propagation effective de `setSecure(true)` côté UI thread (~50 ms),
  // créant une fenêtre brève où screenshot serait possible.
  await SecureWindow.init();

  ClipboardService.clearAfterSeconds = prefs.getInt('clipboard_clear') ?? 30;
  themeNotifier.value = parseThemeMode(
    prefs.getString('theme_mode') ?? 'system',
  );
  localeNotifier.value = parseLocale(prefs.getString(prefKeyLocale));

  // v2.5.x (H1) — migration du schéma de fichiers (noms neutres) + leurre
  // factice toujours présent, AVANT toute lecture du vault. Idempotent,
  // crash-safe, non destructif. Best-effort : un échec (IO/Keystore) ne doit
  // pas empêcher le boot — l'app fonctionnera sur les anciens noms via le
  // fallback et re-tentera au prochain lancement.
  try {
    await VaultService().ensureVaultLayout();
  } catch (_) {
    /* re-tenté au prochain boot */
  }

  final vaultExists = await VaultService().vaultExists;
  final onboardingDone = prefs.getBool('onboarding_completed') ?? false;
  // v2.4.5 — splash de presentation Files Tech au tout premier lancement
  // uniquement. Hydratation prefs deja faite ci-dessus, lecture peu couteuse.
  final showSplash = await FirstLaunchFlag.shouldShow();
  runApp(
    PassTechApp(
      vaultExists: vaultExists,
      onboardingDone: onboardingDone,
      showSplash: showSplash,
    ),
  );
}

class PassTechApp extends StatefulWidget {
  final bool vaultExists;
  final bool onboardingDone;
  final bool showSplash;
  const PassTechApp({
    super.key,
    required this.vaultExists,
    required this.onboardingDone,
    required this.showSplash,
  });

  @override
  State<PassTechApp> createState() => _PassTechAppState();
}

class _PassTechAppState extends State<PassTechApp> with WidgetsBindingObserver {
  /// B2 v2.3.8 — `Stopwatch` monotonique pour l'auto-lock après pause.
  /// `DateTime.now()` suit l'horloge système : un attaquant root qui
  /// recule la date après pause empêche l'auto-lock de déclencher.
  /// `Stopwatch.elapsedMilliseconds` ne se laisse pas tromper.
  ///
  /// ⚠️ AUDIT 2026-08-03 (Gemini PT-001) — mais il se laisse ENDORMIR.
  ///
  /// Le `Stopwatch` de Dart s'appuie sur `CLOCK_MONOTONIC`, qui **cesse
  /// d'avancer pendant la veille profonde** de l'appareil. Or c'est le cas
  /// nominal : on repose son téléphone, l'écran s'éteint, Android entre en
  /// Doze au bout de quelques minutes. Au retour, deux heures plus tard, le
  /// chronomètre n'a compté que le temps d'éveil — souvent moins que le délai
  /// de verrouillage automatique, réglé à 300 s par défaut. **Le coffre
  /// restait donc ouvert.** Le correctif de la v2.3.8 protégeait du décalage
  /// d'horloge et ouvrait cette brèche-là sans que personne le voie.
  ///
  /// La bonne source est `SystemClock.elapsedRealtime()`, qui compte le
  /// sommeil profond — déjà exposée par `MonotonicClock.elapsedRealtimeMs()`,
  /// introduite en v2.5.2 pour le verrouillage anti-force-brute.
  ///
  /// On conserve le `Stopwatch` en second témoin, pour deux raisons : il est
  /// SYNCHRONE, donc capturable à l'instant exact du passage en arrière-plan
  /// (l'appel de canal, lui, impose un `await` — c'est précisément le piège
  /// qui avait affaibli SEC F3), et il reste disponible si la plateforme ne
  /// répond pas. Au retour, on retient le **plus grand** des deux temps
  /// écoulés : `elapsedRealtime` domine toujours, et en cas de réponse
  /// aberrante du canal on verrouille plus tôt plutôt que plus tard.
  int? _pausedAtMonoMs;
  int? _pausedAtBootMs;

  /// Temps écoulé retenu quand aucune horloge fiable n'est disponible.
  /// Dépasse tous les délais de verrouillage proposés (le plus long est
  /// 30 min), donc force le verrouillage sans cas particulier à écrire.
  static const int _dureeInfinieMs = 1 << 40;
  static final Stopwatch _stopwatch = Stopwatch()..start();

  /// v2.5.0 (F9) — guard cache session sur `_checkForUpdate`.
  /// `PassTechApp` est recréé à chaque transition lock→unlock (auto-lock 5s).
  /// Sans ce flag, `_checkForUpdate` re-déclenchait une requête HTTP
  /// vers l'API GitHub Releases à chaque rebuild → consommation réseau
  /// inutile + pattern de timing observable côté serveur (fingerprinting
  /// IP×fréquence). Le flag est `static` pour persister à travers les
  /// recréations du widget root mais pas à travers un kill OS — comportement
  /// voulu (refresh check au démarrage de l'app, pas à chaque unlock).
  static bool _updateCheckedThisSession = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkForUpdate();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _checkForUpdate() async {
    if (_updateCheckedThisSession) return;
    _updateCheckedThisSession = true;
    // SEC F22 v2.5.4 — aucune sonde réseau quand le mode panique est actif.
    //
    // Avant, cette vérification partait à CHAQUE démarrage à froid, avant tout
    // déverrouillage, sans aucune condition. Or après une panique l'app se
    // présente comme une calculatrice : une calculatrice qui contacte l'API
    // GitHub au lancement est une anomalie observable sur l'appareil même —
    // journal de connexions, application pare-feu type NetGuard, VPN local.
    // Le camouflage tombait donc au niveau réseau, alors qu'il tient au niveau
    // de l'interface.
    //
    // NB de précision : le nom du dépôt voyage dans le CHEMIN de l'URL, qui est
    // chiffré par TLS. Un observateur purement passif ne voit que le SNI
    // `api.github.com`, partagé par quantité d'applications. Ce n'est donc pas
    // une divulgation du nom de l'app — c'est l'existence même du trafic qui
    // contredit le camouflage.
    try {
      // SEC 2026-08-04 (audit GPT F8) — on s'abstient dès que ce n'est
      // pas explicitement `false`. `null` = état indéterminé : ne pas
      // savoir si le camouflage est actif doit conduire au silence
      // réseau, pas à une requête qui le trahirait.
      if (await PanicService.isDisguised() != false) return;
    } catch (_) {
      // Canal indisponible : on s'abstient. Fail-CLOSED — mieux vaut sauter
      // une vérification de mise à jour que trahir un camouflage actif.
      return;
    }
    await appUpdateService.checkForUpdate();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _handleLifecycle(state);
  }

  Future<void> _handleLifecycle(AppLifecycleState state) async {
    // B3 v2.3.8 — étendu à `inactive` et `hidden` en plus de `paused`
    // (Android 14+ predictive back gesture peut rester en `inactive`
    // plusieurs secondes avec clipboard populé).
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden) {
      // B2 — horodatage monotonique (Stopwatch) immune au clock-skew.
      //
      // DOIT rester la toute première instruction, et SYNCHRONE. Une version
      // intermédiaire de SEC F3 plaçait l'appel FLAG_SECURE (un aller-retour
      // de canal de plateforme) avant cette ligne : l'horodatage était alors
      // capturé APRÈS l'attente, donc plus tard que la mise en arrière-plan
      // réelle. Le verrouillage automatique en déduisait un temps écoulé plus
      // court et verrouillait plus tard — affaiblissement silencieux introduit
      // par un correctif de sécurité.
      _pausedAtMonoMs ??= _stopwatch.elapsedMilliseconds;
      // SEC F3 v2.5.2 — réarme FLAG_SECURE avant toute autre opération
      // asynchrone : Android prend son instantané de fenêtre au moment du
      // passage en arrière-plan, donc chaque `await` qui précède est une
      // fenêtre de capture.
      await SecureWindow.suspendRelaxForBackground();
      // AUDIT 2026-08-03 — ancre qui compte la veille profonde. Prise APRÈS
      // l'horodatage synchrone et APRÈS le réarmement de FLAG_SECURE : c'est un
      // aller-retour de canal, il ne doit précéder ni l'un ni l'autre. Le léger
      // décalage est sans effet, puisque les deux ancres sont comparées à
      // elles-mêmes au retour.
      _pausedAtBootMs ??= await MonotonicClock.elapsedRealtimeMs();
      // Wipe clipboard immediately on background : don't risk leaving secrets
      // in the clipboard if the OS kills the process before the timer fires.
      await ClipboardService.cancelAndClear();
      final prefs = await SharedPreferences.getInstance();
      final lockSec = prefs.getInt('auto_lock_seconds') ?? 300;
      // Immediate lock: wipe key now, navigate on resume
      if (lockSec == 0 && VaultService().isOpen) VaultService().lock();
    } else if (state == AppLifecycleState.resumed) {
      // SEC F3 v2.5.2 — rétablit la relaxation si un écran la demande encore.
      await SecureWindow.resumeRelaxAfterBackground();
      final pausedMs = _pausedAtMonoMs;
      final pausedBootMs = _pausedAtBootMs;
      _pausedAtMonoMs = null;
      _pausedAtBootMs = null;

      // If vault was locked while paused (immediate option) → go to unlock
      if (!VaultService().isOpen) {
        // SEC F19 v2.5.4 — « coffre fermé » ne veut pas dire « coffre
        // existant ». Après « Tout supprimer », l'app repart sur l'écran de
        // création ; il suffisait alors de basculer vers une autre app et de
        // revenir pour que ce bloc pousse l'écran de DÉVERROUILLAGE d'un
        // coffre supprimé. L'utilisateur se retrouvait devant une demande de
        // mot de passe sans issue — aucune saisie ne pouvait aboutir — et
        // seul un redémarrage complet du processus permettait d'en sortir.
        // Reproduit sur émulateur le 2026-07-31.
        //
        // On ne navigue pas du tout dans ce cas : l'écran de création déjà
        // affiché est le bon, et le pousser de nouveau effacerait une saisie
        // en cours.
        if (!await VaultService().vaultExists) return;
        // UX 2026-08-03 — ne pas empiler un écran de déverrouillage sur un
        // écran de déverrouillage.
        //
        // Défaut signalé en usage réel : au lancement, annuler l'invite
        // biométrique la faisait revenir en boucle, sans jamais laisser saisir
        // le mot de passe maître, et le bouton Retour n'y changeait rien.
        //
        // L'invite est un dialogue système : elle met l'application en
        // arrière-plan. À l'annulation, on repasse ici, le coffre est fermé, et
        // ce bloc poussait un NOUVEL écran en VIDANT la pile — d'où le Retour
        // sans effet. Le nouvel écran relançait l'invite depuis son `initState`,
        // et la boucle se refermait.
        //
        // Ce bloc n'a de sens que pour RAMENER vers le déverrouillage depuis un
        // autre écran (accueil, réglages) après un verrouillage automatique. Si
        // l'écran est déjà là, il n'y a rien à faire — et surtout pas le
        // reconstruire, ce qui effacerait une saisie en cours.
        if (UnlockScreenState.estAffiche) {
          // SEC 2026-08-04 — on REVIENT à l'écran de déverrouillage au lieu de
          // le reconstruire.
          //
          // Un simple `return` ici — première version du correctif — était une
          // régression : l'écran de déverrouillage existe encore SOUS la vue
          // héritier, qui affiche les entrées déchiffrées de l'instantané. Le
          // garde voyait « écran présent, rien à faire » et laissait donc cette
          // vue ouverte au retour au premier plan, alors que le comportement
          // d'avant la refermait.
          //
          // `popUntil(isFirst)` traite les deux cas d'un coup : s'il y a une
          // route au-dessus (vue héritier, ou toute autre à venir), elle est
          // fermée ; s'il n'y en a pas, l'appel ne fait rien — donc ni boucle
          // d'invite biométrique, ni saisie effacée.
          //
          // `isFirst` désigne bien l'écran voulu : `SplashGate` RENVOIE
          // l'écran de déverrouillage comme widget enfant plutôt que de le
          // pousser, il n'y a donc qu'une route à la racine.
          _navigatorKey.currentState?.popUntil((r) => r.isFirst);
          return;
        }
        _navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute(builder: (_) => const UnlockScreen()),
          (_) => false,
        );
        return;
      }

      final prefs = await SharedPreferences.getInstance();
      final lockSec = prefs.getInt('auto_lock_seconds') ?? 300;
      if (lockSec < 0) return; // never

      // AUDIT 2026-08-03 — temps réellement écoulé en arrière-plan.
      //
      // `elapsedRealtime` compte le sommeil profond, le `Stopwatch` non. On
      // retient le PLUS GRAND des deux : en fonctionnement normal c'est
      // toujours le premier, et si le canal rend une valeur aberrante ou
      // indisponible, on retombe sur le second — donc on verrouille trop tôt,
      // jamais trop tard.
      var elapsedMs = pausedMs == null
          ? 0
          : _stopwatch.elapsedMilliseconds - pausedMs;
      // SEC 2026-08-04 (audit GPT F7) — l'ABSENCE de l'ancre système verrouille.
      //
      // Mon commentaire affirmait « si le canal rend une valeur aberrante ou
      // indisponible, on retombe sur le second — donc on verrouille trop tôt,
      // jamais trop tard ». C'était FAUX dans le cas prévu par le code
      // lui-même : quand `elapsedRealtimeMs()` rend `null` au passage en
      // arrière-plan, `pausedBootMs` est nul, ce bloc est entièrement sauté, et
      // il ne reste que le `Stopwatch` — celui dont ce fichier explique trois
      // paragraphes plus haut qu'il NE COMPTE PAS la veille profonde.
      //
      // L'appareil pouvait donc dormir deux heures et revenir avec un temps
      // écoulé de quelques minutes : le coffre restait ouvert. La source forte
      // manquante donnait MOINS de sécurité, alors qu'elle doit en donner plus.
      //
      // Repli fail-closed : sans ancre fiable, on considère le temps écoulé
      // comme infini et on verrouille. Le coût pour l'utilisateur légitime est
      // une saisie de mot de passe ; le coût inverse est un coffre ouvert.
      if (pausedMs != null && pausedBootMs == null) {
        elapsedMs = _dureeInfinieMs;
      } else if (pausedBootMs != null) {
        final nowBootMs = await MonotonicClock.elapsedRealtimeMs();
        if (nowBootMs == null) {
          // L'ancre existait au départ mais le canal ne répond plus : même
          // raisonnement, on ne sait pas combien de temps s'est écoulé.
          elapsedMs = _dureeInfinieMs;
        } else if (nowBootMs >= pausedBootMs) {
          final bootElapsed = nowBootMs - pausedBootMs;
          if (bootElapsed > elapsedMs) elapsedMs = bootElapsed;
        } else {
          // SEC 2026-08-04 (relecture Codex) — un RECUL de l'ancre système
          // verrouille, au lieu d'être ignoré.
          //
          // La version précédente écrivait « on l'ignore », et ignorer revenait
          // à ne garder que le `Stopwatch` — celui qui NE COMPTE PAS la veille
          // profonde. C'est le troisième repli de ce bloc, et c'était le seul à
          // pencher du mauvais côté, alors que les deux autres, dix lignes plus
          // haut, verrouillent précisément parce que l'ancre est inexploitable.
          //
          // La documentation de `MonotonicClock.elapsedRealtimeMs` demande
          // d'ailleurs qu'un appelant qui persiste une valeur « détecte le
          // recul et le traite de façon conservatrice ». Ce code le détectait
          // sans le traiter.
          //
          // Le cas devrait être hors d'atteinte — `elapsedRealtime` ne recule
          // qu'au redémarrage de l'appareil, auquel le processus ne survit pas.
          // Mais un repli n'a de valeur que par le sens dans lequel il échoue,
          // et celui-ci laissait un coffre ouvert.
          elapsedMs = _dureeInfinieMs;
        }
      }

      if (pausedMs != null &&
          elapsedMs >= lockSec * 1000 &&
          VaultService().isOpen) {
        VaultService().lock();
        _navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute(builder: (_) => const UnlockScreen()),
          (_) => false,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeNotifier,
      builder: (_, mode, _) => ValueListenableBuilder<Locale?>(
        valueListenable: localeNotifier,
        builder: (_, locale, _) => MaterialApp(
          title: 'Pass Tech',
          navigatorKey: _navigatorKey,
          debugShowCheckedModeBanner: false,
          themeMode: mode,
          theme: _lightTheme(),
          darkTheme: _darkTheme(),
          locale: locale,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: SplashGate(
            showSplash: widget.showSplash,
            nextChild: !widget.onboardingDone && !widget.vaultExists
                ? const OnboardingScreen()
                : (widget.vaultExists
                      ? const UnlockScreen()
                      : const SetupScreen()),
          ),
        ),
      ),
    );
  }
}

ThemeData _lightTheme() {
  // Le schéma est construit ICI plutôt que via `colorSchemeSeed`, pour pouvoir
  // en réutiliser les couleurs dans les thèmes de composants ci-dessous.
  // Strictement équivalent : `colorSchemeSeed` fait exactement cet appel.
  final cs = ColorScheme.fromSeed(seedColor: const Color(0xFF1F6FEB));
  return ThemeData(
    useMaterial3: true,
    colorScheme: cs,
    brightness: Brightness.light,
    // U11 v2.4.4 — snack flottant par défaut (cohérent avec SnackUtils +
    // les ScaffoldMessenger inline qui n'avaient pas `behavior:floating`).
    // Aligné PDF Tech v1.12.4 U2.
    //
    // UI 2026-08-04 — fond BLEU de la marque au lieu du gris très sombre que
    // Material pose par défaut (`inverseSurface`), qui jurait avec le reste de
    // l'application en thème clair.
    //
    // Les couleurs viennent du schéma, jamais codées en dur : c'est ce qui
    // garantit le contraste dans les deux thèmes. `onPrimary` est calculé par
    // Material pour être lisible sur `primary` — l'écrire à la main
    // reproduirait l'erreur des `Colors.grey` corrigée en v2.4.4.
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: cs.primary,
      contentTextStyle: TextStyle(color: cs.onPrimary),
      actionTextColor: cs.onPrimary,
    ),
  );
}

ThemeData _darkTheme() {
  const bg = Color(0xFF0D1117);
  const surface = Color(0xFF161B22);
  const surface2 = Color(0xFF21262D);
  const border = Color(0xFF30363D);
  const textPri = Color(0xFFE6EDF3);
  const textSec = Color(0xFF8B949E);
  const blue = Color(0xFF58A6FF);
  const blueCont = Color(0xFF1F6FEB);

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      surface: surface,
      onSurface: textPri,
      onSurfaceVariant: textSec,
      primary: blue,
      onPrimary: Color(0xFF0D1117),
      primaryContainer: blueCont,
      onPrimaryContainer: textPri,
      surfaceContainerHighest: surface2,
      outline: border,
      error: Color(0xFFFF7B72),
    ),
    scaffoldBackgroundColor: bg,
    cardTheme: CardThemeData(
      color: surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: const BorderSide(color: border, width: 0.5),
      ),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: bg,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
    ),
    chipTheme: const ChipThemeData(
      backgroundColor: surface2,
      side: BorderSide(color: border),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: surface2,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: border),
      ),
    ),
    listTileTheme: const ListTileThemeData(tileColor: surface),
    dividerColor: border,
    // U11 v2.4.4 — snack flottant sur dark theme.
    // UI 2026-08-04 — même fond bleu qu'en thème clair. Ici `blue` (#58A6FF)
    // est clair et le texte sombre (#0D1117), l'inverse du thème clair : c'est
    // exactement ce que le passage par le schéma de couleurs garantit.
    snackBarTheme: const SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: blue,
      contentTextStyle: TextStyle(color: Color(0xFF0D1117)),
      actionTextColor: Color(0xFF0D1117),
    ),
  );
}
