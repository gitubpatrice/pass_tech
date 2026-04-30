package com.passtech.pass_tech

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterFragmentActivity() {
    private val secureClipboardChannel = "com.passtech.pass_tech/secure_clipboard"
    private val raspChannel = "com.passtech.pass_tech/rasp"
    private val disguiseChannel = "com.passtech.pass_tech/disguise"
    private val antiPhishingChannel = "com.passtech.pass_tech/antiphishing"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording, and the recent-apps preview thumbnail
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, secureClipboardChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "copySensitive" -> {
                        val text = call.argument<String>("text") ?: ""
                        copySensitive(text)
                        result.success(null)
                    }
                    "clear" -> {
                        clearClipboard()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, raspChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "checkIntegrity" -> {
                        result.success(checkIntegrity())
                    }
                    else -> result.notImplemented()
                }
            }

        // Anti-phishing : expose à Dart le domaine courant détecté par
        // l'AccessibilityService + l'état d'activation du service système.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, antiPhishingChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getCurrentDomain" -> {
                        // Lit la valeur volatile maintenue par PhishingDetectorService.
                        // Retourne null si AS désactivée ou aucun domaine détecté.
                        result.success(PhishingDetectorService.lastDomain)
                    }
                    "isAccessibilityServiceEnabled" -> {
                        result.success(isPhishingServiceEnabled())
                    }
                    "openAccessibilitySettings" -> {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            startActivity(intent)
                            result.success(null)
                        } catch (e: Exception) {
                            result.error("OPEN_ERROR", e.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        // Camouflage : bascule entre l'icône Pass Tech et l'alias "Calculatrice".
        // setComponentEnabledSetting est instantané sur l'utilisateur (l'icône
        // disparaît / réapparaît sur le launcher dans la seconde).
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, disguiseChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setDisguised" -> {
                        val disguised = call.argument<Boolean>("disguised") ?: false
                        try {
                            val pm = packageManager
                            val normalAlias = ComponentName(packageName,
                                "$packageName.MainAliasNormal")
                            val decoyAlias  = ComponentName(packageName,
                                "$packageName.MainAliasDecoy")
                            // Active l'alias désiré, désactive l'autre.
                            pm.setComponentEnabledSetting(
                                if (disguised) decoyAlias else normalAlias,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP)
                            pm.setComponentEnabledSetting(
                                if (disguised) normalAlias else decoyAlias,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP)
                            result.success(null)
                        } catch (e: Exception) {
                            result.error("DISGUISE_ERROR", e.message, null)
                        }
                    }
                    "isDisguised" -> {
                        try {
                            val decoyAlias = ComponentName(packageName,
                                "$packageName.MainAliasDecoy")
                            val state = packageManager.getComponentEnabledSetting(decoyAlias)
                            // ENABLED ou (DEFAULT && manifest enabled=true). Manifest = false.
                            result.success(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /// Détection root + emulator + debugger basique. Sans dépendance externe.
    /// Renvoie un Map avec les flags détectés. Best-effort : un attaquant
    /// déterminé peut contourner ces checks (Magisk Hide, Frida bypass).
    /// Sert surtout d'avertissement utilisateur.
    private fun checkIntegrity(): Map<String, Boolean> {
        return mapOf(
            "rooted"     to isRooted(),
            "emulator"   to isEmulator(),
            "debuggable" to isDebuggable(),
            "debugger"   to android.os.Debug.isDebuggerConnected()
        )
    }

    private fun isRooted(): Boolean {
        // Binaires su classiques
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/su", "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/system/xbin/mu", "/data/local/xbin/su",
            "/data/local/bin/su", "/data/local/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/su/bin/su", "/su/bin/.ext/.su"
        )
        if (suPaths.any { File(it).exists() }) return true

        // Apps Magisk / SuperSU
        val rootApps = arrayOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.noshufou.android.su"
        )
        rootApps.forEach { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) { /* not installed */ }
        }

        // build.tags suspects
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true

        return false
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * True si PhishingDetectorService est activé dans Réglages > Accessibilité.
     * On parcourt la liste des AS activés et on vérifie le component name.
     * Le user doit grant manuellement (sécurité Android) → l'app ne peut pas
     * activer l'AS programmatiquement.
     */
    private fun isPhishingServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val expectedComponent = ComponentName(this, PhishingDetectorService::class.java)
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabled.any { svc ->
                svc.id?.contains(expectedComponent.flattenToString(), ignoreCase = true) == true
                || svc.resolveInfo?.serviceInfo?.let { si ->
                    si.packageName == packageName
                    && si.name == PhishingDetectorService::class.java.name
                } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copySensitive(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Pass Tech", text)
        // Android 13+ : flag the content as sensitive so it isn't shown
        // in clipboard previews (e.g. notification bar).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clip.description.extras = extras
        } else {
            // Pre-13 fallback: legacy "android.content.extra.IS_SENSITIVE" key.
            val extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
    }

    private fun clearClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
