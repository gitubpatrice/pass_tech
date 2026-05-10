import 'package:files_tech_core/files_tech_core.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// Instance partagée de [UpdateService] configurée pour Pass Tech.
///
/// v2.3.8 — version lue dynamiquement depuis `PackageInfo.fromPlatform()`
/// pour éviter la désynchronisation entre `pubspec.yaml` et la constante
/// (cf. feedback `feedback_appinfo_version_bump.md`). Le service est
/// construit paresseusement au premier appel.
UpdateService? _instance;
String _resolvedVersion = '0.0.0';

Future<UpdateService> _ensureInstance() async {
  if (_instance != null) return _instance!;
  try {
    final info = await PackageInfo.fromPlatform();
    _resolvedVersion = info.version;
  } catch (_) {
    // Cold path : si PackageInfo échoue (test, plateforme inattendue),
    // on garde 0.0.0 — toute version GitHub sera considérée "plus récente",
    // mais le caller affiche déjà une UI à l'utilisateur qui décidera.
  }
  return _instance ??= UpdateService(
    owner: 'gitubpatrice',
    repo: 'pass_tech',
    currentVersion: _resolvedVersion,
  );
}

/// Wrapper qui expose la même surface que `UpdateService` mais résout
/// la version dynamiquement à la première utilisation.
class _DynamicUpdateService {
  const _DynamicUpdateService();

  Future<UpdateInfo?> checkForUpdate({bool force = false}) async {
    final svc = await _ensureInstance();
    return svc.checkForUpdate(force: force);
  }
}

const appUpdateService = _DynamicUpdateService();
