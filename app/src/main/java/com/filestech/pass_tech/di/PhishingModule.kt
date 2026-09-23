package com.filestech.pass_tech.di

import com.filestech.pass_tech.core.phishing.ActiveDomain
import com.filestech.pass_tech.core.phishing.AndroidPhishingComponent
import com.filestech.pass_tech.core.phishing.DomainSnapshot
import com.filestech.pass_tech.core.phishing.PhishingComponent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PhishingModule {

    @Binds
    abstract fun activeDomain(impl: DomainSnapshot): ActiveDomain

    @Binds
    abstract fun phishingComponent(impl: AndroidPhishingComponent): PhishingComponent
}
