package com.passtech.pass_tech

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterFragmentActivity() {
    private val secureClipboardChannel = "com.passtech.pass_tech/secure_clipboard"
    private val raspChannel = "com.passtech.pass_tech/rasp"

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
