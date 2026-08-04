allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

// Force consistent JVM toolchain across all third-party Flutter plugins.
// Plugins disagree on their Java/Kotlin target (biometric_storage pins
// jvmToolchain(17), others still declare 1.8) and the inconsistency makes
// Gradle 8 fail. We bump everyone to 17 (sourceCompat, targetCompat,
// kotlinJvmTarget) and force the Java toolchain to JDK 21.
//
// AUDIT 2026-08-03 — ce bloc citait `mobile_scanner`, retiré depuis. Il est
// CONSERVÉ : la divergence ne venait pas de lui seul, et le build a été
// revalidé avec. Ne pas le supprimer sans reconstruire l'ensemble des plugins.
subprojects {
    afterEvaluate {
        plugins.withId("org.jetbrains.kotlin.android") {
            extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>()?.apply {
                jvmToolchain {
                    languageVersion.set(JavaLanguageVersion.of(21))
                }
            }
        }
        plugins.withId("com.android.library") {
            extensions.findByType<com.android.build.gradle.LibraryExtension>()?.apply {
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
        plugins.withId("com.android.application") {
            extensions.findByType<com.android.build.gradle.AppExtension>()?.apply {
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            sourceCompatibility = "17"
            targetCompatibility = "17"
        }
    }
}

subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
