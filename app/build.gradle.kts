import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The version comes from version.properties, with no fallback on purpose: a wrong versionCode breaks
// the update path of an installed app, so a build that fails loudly beats an APK with a silently
// wrong one.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    require(f.exists()) { "version.properties not found at the project root." }
    f.inputStream().use { load(it) }
}
val appVersionCode: Int = requireNotNull(versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()) {
    "versionCode missing or not an integer in version.properties."
}
val appVersionName: String = requireNotNull(versionProps.getProperty("versionName")?.trim()?.ifBlank { null }) {
    "versionName missing or blank in version.properties."
}

// Release signing is read from keystore.properties, which is git-ignored. Without it the release APK
// comes out unsigned, which is all CI needs to exercise R8 and measure the permissions.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.filestech.pass_tech"
    // compileSdk 37 is required by the current androidx releases (compose 1.12, core 1.19).
    compileSdk = 37

    defaultConfig {
        // NOT the id of the Flutter app (com.passtech.pass_tech): both are installed side by side
        // during the migration, which goes through a .ptbak export and import.
        applicationId = "com.filestech.pass_tech"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "Pass Tech"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Must list exactly the languages the app ships. Any values-xx/ folder missing from this list is
    // stripped from the APK at build time, silently (measured on the Flutter app in 2.7.0).
    androidResources {
        localeFilters += listOf("en", "fr", "de", "it", "es")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // No v1 (JAR) signature: minSdk 26 does not need it, and it is the scheme hit by
                // Janus (CVE-2017-13156).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build live next to the installed release. The launcher label says which
            // is which, so a test can never exercise the wrong build unnoticed.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Pass Tech (debug)"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        unitTests.all { test -> test.useJUnitPlatform() }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    // AGP adds a "Dependency metadata" block to the APK signing block, meant for the Play console.
    // The app is not published on Play, and an opaque blob has no place in a binary whose whole
    // argument is to be verifiable.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
