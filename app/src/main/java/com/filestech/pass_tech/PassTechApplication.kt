package com.filestech.pass_tech

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.filestech.pass_tech.core.settings.AppLanguage
import com.filestech.pass_tech.core.vault.AutoLock
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PassTechApplication : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    /**
     * The chosen language, applied before anything else exists, so that whatever reads a string from
     * the application context — and the process default locale with it — is already in it. Below
     * Android 13 only; above, the system has applied its own per-app language already.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLock)
    }
}
