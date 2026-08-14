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
    // AGP glisse par defaut, dans le bloc de signature de l'APK, la liste CHIFFREE de nos
    // dependances, a destination de la console Play. Aucune application Files Tech n'est
    // publiee sur Play : ce bloc ne sert rien ici, et un blob illisible n'a pas sa place dans
    // un binaire dont tout l'argument est d'etre verifiable. Le scanner F-Droid le refuse
    // (« found extra signing block 'Dependency metadata' »).
    //
    // Mesure sur Agenda Tech : 9085 octets retires. Constate present sur TOUTES les apps du
    // portefeuille le 2026-08-14 — il ne pouvait pas etre vu plus tot, car les controles
    // n'analysaient que des APK NON SIGNES, qui n'ont pas de bloc de signature.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
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
        // tirées par biometric_storage / Material). Gain ~3-6 Mo.
        // (2026-08-03 : `mobile_scanner` ne figure plus dans cette liste, la
        // dépendance ayant été retirée avec le scan de QR code.)
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
            // SEC-R2 v2.5.2 — on cible les tâches d'ASSEMBLAGE, pas tout ce qui
            // contient « release ». Un `contains("elease")` matchait aussi
            // `testReleaseUnitTest`, `lintRelease` ou `compileReleaseKotlin`,
            // qui n'impliquent aucune signature : un futur job CI exécutant
            // l'une d'elles sans keystore aurait échoué sur un message
            // trompeur parlant de refus de signature.
            // SEC 2026-08-03 (Gemini PT-003) — le filtre couvre aussi les
            // tâches GÉNÉRIQUES.
            //
            // SEC-R2 v2.5.2 avait resserré la détection sur `assembleRelease` /
            // `bundleRelease` pour ne plus faire échouer `testReleaseUnitTest`
            // ou `lintRelease`. Le resserrage est allé trop loin : `gradlew
            // assemble` et `gradlew build` construisent TOUTES les variantes,
            // release comprise, sans jamais contenir le mot « Release ». La
            // garde ne se déclenchait donc pas et l'APK release repartait signé
            // avec la clé de DEBUG, publiquement connue — exactement ce que
            // SEC F15 existe pour empêcher.
            //
            // On teste désormais l'égalité stricte sur les tâches génériques
            // (et non `contains`, qui rattraperait `assembleDebug`), en plus
            // des deux tâches explicites.
            val keyPropsFile = rootProject.file("key.properties")
            val releaseTaskDemande = gradle.startParameter.taskNames.any { nom ->
                val court = nom.substringAfterLast(':')
                court == "assemble" || court == "build" ||
                    nom.contains("assembleRelease") || nom.contains("bundleRelease")
            }
            if (!keyPropsFile.exists() && releaseTaskDemande) {
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
