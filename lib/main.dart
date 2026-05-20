import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'l10n/app_localizations.dart';
import 'services/app_update.dart';
import 'services/clipboard_service.dart';
import 'services/first_launch_flag.dart';
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
  int? _pausedAtMonoMs;
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
      _pausedAtMonoMs ??= _stopwatch.elapsedMilliseconds;
      // Wipe clipboard immediately on background : don't risk leaving secrets
      // in the clipboard if the OS kills the process before the timer fires.
      await ClipboardService.cancelAndClear();
      final prefs = await SharedPreferences.getInstance();
      final lockSec = prefs.getInt('auto_lock_seconds') ?? 300;
      // Immediate lock: wipe key now, navigate on resume
      if (lockSec == 0 && VaultService().isOpen) VaultService().lock();
    } else if (state == AppLifecycleState.resumed) {
      final pausedMs = _pausedAtMonoMs;
      _pausedAtMonoMs = null;

      // If vault was locked while paused (immediate option) → go to unlock
      if (!VaultService().isOpen) {
        _navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute(builder: (_) => const UnlockScreen()),
          (_) => false,
        );
        return;
      }

      final prefs = await SharedPreferences.getInstance();
      final lockSec = prefs.getInt('auto_lock_seconds') ?? 300;
      if (lockSec < 0) return; // never
      if (pausedMs != null &&
          _stopwatch.elapsedMilliseconds - pausedMs >= lockSec * 1000 &&
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

ThemeData _lightTheme() => ThemeData(
  useMaterial3: true,
  colorSchemeSeed: const Color(0xFF1F6FEB),
  brightness: Brightness.light,
  // U11 v2.4.4 — snack flottant par défaut (cohérent avec SnackUtils +
  // les ScaffoldMessenger inline qui n'avaient pas `behavior:floating`).
  // Aligné PDF Tech v1.12.4 U2.
  snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
);

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
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}
