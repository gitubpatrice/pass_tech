package com.filestech.pass_tech

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.filestech.pass_tech.core.vault.AutoLock
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PassTechApplication : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLock)
    }
}
