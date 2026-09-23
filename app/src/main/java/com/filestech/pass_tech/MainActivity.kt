package com.filestech.pass_tech

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.core.panic.LauncherDisguise
import com.filestech.pass_tech.core.settings.AppLanguage
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.AppViewModel
import com.filestech.pass_tech.ui.PassTechApp
import com.filestech.pass_tech.ui.about.AboutViewModel
import com.filestech.pass_tech.ui.audit.AuditViewModel
import com.filestech.pass_tech.ui.entries.EntriesViewModel
import com.filestech.pass_tech.ui.entry.EntryViewModel
import com.filestech.pass_tech.ui.home.HomeViewModel
import com.filestech.pass_tech.ui.settings.SettingsViewModel
import com.filestech.pass_tech.ui.splash.SplashViewModel
import com.filestech.pass_tech.ui.theme.PassTechTheme
import com.filestech.pass_tech.ui.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** A [FragmentActivity]: the system biometric prompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var disguise: LauncherDisguise

    private val app: AppViewModel by viewModels()
    private val entry: EntryViewModel by viewModels()
    private val entries: EntriesViewModel by viewModels()
    private val home: HomeViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val audit: AuditViewModel by viewModels()
    private val about: AboutViewModel by viewModels()
    private val update: UpdateViewModel by viewModels()
    private val splash: SplashViewModel by viewModels()

    /**
     * The chosen language, applied to this window's own resources before anything is drawn. Below
     * Android 13 only, and a no-op above, where the system applies its per-app language itself
     * ([AppLanguage]).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash stays up until the first screen is known: whether the first-launch splash
        // is due, and which entry form to show. Nothing flashes in between.
        // An open vault shows the home: the entry form does not matter then.
        installSplashScreen().setKeepOnScreenCondition {
            splash.shouldShow.value == null ||
                (app.vaultState.value == VaultManager.State.Locked && entry.state.value.mode == null)
        }
        super.onCreate(savedInstanceState)
        // No screenshot, no recent-apps thumbnail: the screens show secrets. On from the first frame,
        // before the setting is read; lifted only once it reads an explicit "off" (2.7.1's default).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val ui by settings.state.collectAsStateWithLifecycle()
            LaunchedEffect(ui.screenshotProtection) {
                if (ui.screenshotProtection) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            val dark = when (ui.theme) {
                AppPreferences.Theme.SYSTEM -> isSystemInDarkTheme()
                AppPreferences.Theme.LIGHT -> false
                AppPreferences.Theme.DARK -> true
            }
            // The bars follow the app's theme, not the system's: a forced light theme on a dark phone
            // would otherwise draw white status icons on a light page.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            PassTechTheme(darkTheme = dark) {
                PassTechApp(
                    app = app,
                    entry = entry,
                    entries = entries,
                    home = home,
                    settings = settings,
                    audit = audit,
                    about = about,
                    update = update,
                    splash = splash,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nameRecentTask()
    }

    /**
     * What Recent apps calls this task, and which icon it draws there.
     *
     * While the panic disguise is on, both are the calculator's: the recents card is the one place
     * left where the name of a hidden app still showed, once its owner had come back through the
     * calculator and gone home. `android:label` and `android:icon` of `<application>` cannot be
     * changed while the app runs, so Settings › Apps and the share sheet keep saying Pass Tech — the
     * residual 2.7.1 states in THREAT_MODEL, and this closes one more of its surfaces.
     *
     * Read on every resume: the disguise can be turned on, or off, while this activity lives.
     */
    private fun nameRecentTask() {
        val disguised = disguise.disguised() == true
        val label = getString(if (disguised) R.string.app_disguise_label else R.string.app_name)
        val icon = if (disguised) R.drawable.ic_launcher_calc else R.mipmap.ic_launcher
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder().setLabel(label).setIcon(icon).build()
            } else {
                // Before Android 13 the description takes a bitmap, so the vector is drawn into one.
                val bitmap = ContextCompat.getDrawable(this, icon)?.toBitmap(RECENTS_ICON_PIXELS, RECENTS_ICON_PIXELS)
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label, bitmap)
            },
        )
    }

    private companion object {
        /** androidx.activity's own defaults for the navigation bar scrim. */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        /** A recents card icon, drawn from a vector: big enough for the densest screen here. */
        const val RECENTS_ICON_PIXELS = 192
    }
}
