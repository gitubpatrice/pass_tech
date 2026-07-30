import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("key.properties")
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.passtech.pass_tech"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.passtech.pass_tech"
        // M-12 : minSdk 24 (Android 7.0) explicite pour pouvoir désactiver
        // enableV1Signing (vulnérable Janus CVE-2017-13156 sur Android < 7).
        // V2/V3 suffisent à partir de Nougat. Android 5/6 représentent < 0,5 %
        // du parc fin 2025 et n'ont plus aucune mise à jour de sécurité.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // P2.1 v2.4.3 — réduction APK : seuls FR + EN embarqués (vs ~50 locales
        // tirées par biometric_storage / mobile_scanner / Material). Gain ~3-6 Mo.
        resourceConfigurations.addAll(listOf("en", "fr"))
    }

    // P2.1 v2.4.3 — Le split par ABI est obtenu via le flag CLI Flutter
    // `flutter build apk --release --split-per-abi` (gain ~25-30 Mo par APK
    // arm64 vs ~71 Mo universel). Configurer un bloc `splits.abi` ici
    // entrerait en conflit avec `ndk.abiFilters` posé automatiquement par
    // Flutter sur les builds debug et release CI (sans --split-per-abi),
    // cf. RFT v2.13.0 CI failure.

    signingConfigs {
        create("release") {
            val storeFileName = keystoreProperties["storeFile"] as String?
            if (storeFileName != null) {
                storeFile = rootProject.file(storeFileName)
                storePassword = keystoreProperties["storePassword"] as String?
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
                // M-12 : V1 (jar signing) désactivé. Vulnérable à Janus
                // (CVE-2017-13156) sur Android < 7. minSdk = 24 garantit que
                // V2/V3 suffisent ; v3 permet la rotation de clé en cas de
                // compromission (Android 9+).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // QW8 v2.4.0 — V4 signing (Android 11+) permet install
                // incrémental + meilleure chaîne d'attestation. Sans coût.
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            // SEC F15 v2.5.2 — Avant : repli SILENCIEUX sur le keystore de
            // debug quand `key.properties` manque. Alias `androiddebugkey` /
            // mot de passe `android` sont publiquement documentés : n'importe
            // qui pouvait alors reconstruire un Pass Tech troyanisé, le signer
            // avec sa propre copie de `~/.android/debug.keystore` et le faire
            // installer PAR-DESSUS l'app légitime — héritant du répertoire de
            // données, de `pt_vault_a.enc` et des alias KEK Keystore.
            // `key.properties` étant gitignoré, c'était l'état par défaut de
            // tout clone frais.
            // Désormais : on n'échoue que si un build RELEASE est réellement
            // demandé, pour ne pas casser les builds debug/profile sur une
            // machine sans keystore.
            val keyPropsFile = rootProject.file("key.properties")
            if (!keyPropsFile.exists() &&
                gradle.startParameter.taskNames.any { it.contains("elease") }
            ) {
                throw GradleException(
                    "key.properties introuvable : refus de signer un build " +
                        "release avec la clé de DEBUG. Restaure " +
                        "${keyPropsFile.absolutePath} avant de couper une " +
                        "release. Voir SECURITY.md (signature de release)."
                )
            }
            signingConfig = if (keyPropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

flutter {
    source = "../.."
}
