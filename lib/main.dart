import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'services/clipboard_service.dart';
import 'services/update_service.dart';
import 'services/vault_service.dart';
import 'screens/setup_screen.dart';
import 'screens/unlock_screen.dart';

final _navigatorKey = GlobalKey<NavigatorState>();

/// Global theme mode notifier — listened by the app, updated by settings.
final ValueNotifier<ThemeMode> themeNotifier = ValueNotifier(ThemeMode.system);

ThemeMode parseThemeMode(String s) {
  switch (s) {
    case 'light': return ThemeMode.light;
    case 'dark':  return ThemeMode.dark;
    default:      return ThemeMode.system;
  }
}

String themeModeToString(ThemeMode m) {
  switch (m) {
    case ThemeMode.light:  return 'light';
    case ThemeMode.dark:   return 'dark';
    case ThemeMode.system: return 'system';
  }
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(statusBarColor: Colors.transparent));

  final prefs       = await SharedPreferences.getInstance();
  ClipboardService.clearAfterSeconds = prefs.getInt('clipboard_clear') ?? 30;
  themeNotifier.value = parseThemeMode(prefs.getString('theme_mode') ?? 'system');

  final vaultExists = await VaultService().vaultExists;
  runApp(PassTechApp(vaultExists: vaultExists));
}

class PassTechApp extends StatefulWidget {
  final bool vaultExists;
  const PassTechApp({super.key, required this.vaultExists});

  @override
  State<PassTechApp> createState() => _PassTechAppState();
}

class _PassTechAppState extends State<PassTechApp>
    with WidgetsBindingObserver {
  DateTime? _pausedAt;

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
    await UpdateService().checkForUpdate();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _handleLifecycle(state);
  }

  Future<void> _handleLifecycle(AppLifecycleState state) async {
    if (state == AppLifecycleState.paused) {
      _pausedAt = DateTime.now();
      ClipboardService.cancel();
      final prefs = await SharedPreferences.getInstance();
      final lockSec = prefs.getInt('auto_lock_seconds') ?? 300;
      // Immediate lock: wipe key now, navigate on resume
      if (lockSec == 0 && VaultService().isOpen) VaultService().lock();
    } else if (state == AppLifecycleState.resumed) {
      final paused = _pausedAt;
      _pausedAt = null;

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
      if (paused != null &&
          DateTime.now().difference(paused).inSeconds >= lockSec &&
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
      builder: (_, mode, __) => MaterialApp(
        title: 'Pass Tech',
        navigatorKey: _navigatorKey,
        debugShowCheckedModeBanner: false,
        themeMode: mode,
        theme: _lightTheme(),
        darkTheme: _darkTheme(),
        home: widget.vaultExists ? const UnlockScreen() : const SetupScreen(),
      ),
    );
  }
}

ThemeData _lightTheme() => ThemeData(
      useMaterial3: true,
      colorSchemeSeed: const Color(0xFF1F6FEB),
      brightness: Brightness.light,
    );

ThemeData _darkTheme() {
  const bg       = Color(0xFF0D1117);
  const surface  = Color(0xFF161B22);
  const surface2 = Color(0xFF21262D);
  const border   = Color(0xFF30363D);
  const textPri  = Color(0xFFE6EDF3);
  const textSec  = Color(0xFF8B949E);
  const blue     = Color(0xFF58A6FF);
  const blueCont = Color(0xFF1F6FEB);

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      surface:                surface,
      onSurface:              textPri,
      onSurfaceVariant:       textSec,
      primary:                blue,
      onPrimary:              Color(0xFF0D1117),
      primaryContainer:       blueCont,
      onPrimaryContainer:     textPri,
      surfaceContainerHighest: surface2,
      outline:                border,
      error:                  Color(0xFFFF7B72),
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
  );
}
