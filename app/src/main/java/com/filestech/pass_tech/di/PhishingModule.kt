package com.filestech.pass_tech.di

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

        /** Whether anything at all may read this process's memory. */
        @Provides
        fun debuggable(): Boolean = BuildConfig.DEBUG
    }
}
