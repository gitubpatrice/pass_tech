package com.filestech.pass_tech.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.filestech.pass_tech.BuildConfig
import com.filestech.pass_tech.core.breach.BreachApi
import com.filestech.pass_tech.core.breach.HibpApi
import com.filestech.pass_tech.core.integrity.AndroidDeviceIntegrity
import com.filestech.pass_tech.core.integrity.DeviceIntegrity
import com.filestech.pass_tech.core.phishing.ActiveDomain
import com.filestech.pass_tech.core.phishing.AndroidPhishingComponent
import com.filestech.pass_tech.core.phishing.DomainSnapshot
import com.filestech.pass_tech.core.phishing.PhishingComponent
import com.filestech.pass_tech.core.update.GithubReleaseApi
import com.filestech.pass_tech.core.update.ReleaseApi
import com.filestech.pass_tech.core.update.UpdateCheck
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
abstract class PhishingModule {

    @Binds
    abstract fun activeDomain(impl: DomainSnapshot): ActiveDomain

    @Binds
    abstract fun phishingComponent(impl: AndroidPhishingComponent): PhishingComponent

    @Binds
    abstract fun breachApi(impl: HibpApi): BreachApi

    @Binds
    abstract fun releaseApi(impl: GithubReleaseApi): ReleaseApi

    @Binds
    abstract fun deviceIntegrity(impl: AndroidDeviceIntegrity): DeviceIntegrity

    companion object {
        /** From BuildConfig, so the version has exactly one source: version.properties. */
        @Provides
        @Named(UpdateCheck.INSTALLED_VERSION)
        fun installedVersion(): String = BuildConfig.VERSION_NAME

        /**
         * Whether anything at all may read this process's memory, **asked of the running app**.
         *
         * It used to be `BuildConfig.DEBUG`, a constant frozen when the APK was built — so it is
         * `false` in every published build and the warning could never once appear on anyone's
         * phone. Worse, the case the warning exists for is the one that constant cannot see: an APK
         * **repackaged and re-signed by someone else** with the debuggable flag turned on, which
         * carries the original `BuildConfig`. The flag on `ApplicationInfo` is the phone's own
         * answer about the process that is actually running.
         */
        @Provides
        @Named(DEBUGGABLE)
        fun debuggable(@ApplicationContext context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        /** So that no other `Boolean` injected anywhere in the app can be handed this one by accident. */
        const val DEBUGGABLE = "debuggable"
    }
}
