package com.filestech.pass_tech

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.filestech.pass_tech.core.vault.VaultManager
import com.filestech.pass_tech.ui.AppViewModel
import com.filestech.pass_tech.ui.PassTechApp
import com.filestech.pass_tech.ui.entries.EntriesViewModel
import com.filestech.pass_tech.ui.entry.EntryViewModel
import com.filestech.pass_tech.ui.home.HomeViewModel
import com.filestech.pass_tech.ui.splash.SplashViewModel
import com.filestech.pass_tech.ui.theme.PassTechTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val app: AppViewModel by viewModels()
    private val entry: EntryViewModel by viewModels()
    private val entries: EntriesViewModel by viewModels()
    private val home: HomeViewModel by viewModels()
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
        // No screenshot, no recent-apps thumbnail: the screens show secrets (2.7.1 protected them by
        // default too; the setting to turn it off comes with the settings screen).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            PassTechTheme {
                PassTechApp(app = app, entry = entry, entries = entries, home = home, splash = splash)
            }
        }
    }
}
