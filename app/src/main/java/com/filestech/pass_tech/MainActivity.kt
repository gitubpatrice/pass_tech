package com.filestech.pass_tech

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.AppViewModel
import com.filestech.pass_tech.ui.PassTechApp
import com.filestech.pass_tech.ui.entries.EntriesViewModel
import com.filestech.pass_tech.ui.entry.EntryViewModel
import com.filestech.pass_tech.ui.home.HomeViewModel
import com.filestech.pass_tech.ui.settings.SettingsViewModel
import com.filestech.pass_tech.ui.splash.SplashViewModel
import com.filestech.pass_tech.ui.theme.PassTechTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val app: AppViewModel by viewModels()
    private val entry: EntryViewModel by viewModels()
    private val entries: EntriesViewModel by viewModels()
    private val home: HomeViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val splash: SplashViewModel by viewModels()

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
                PassTechApp(app = app, entry = entry, entries = entries, home = home, settings = settings, splash = splash)
            }
        }
    }

    private companion object {
        /** androidx.activity's own defaults for the navigation bar scrim. */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
