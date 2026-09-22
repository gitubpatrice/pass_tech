package com.filestech.pass_tech.di

import com.filestech.pass_tech.core.panic.AliasLauncherDisguise
import com.filestech.pass_tech.core.panic.LauncherDisguise
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PanicModule {

    @Binds
    abstract fun launcherDisguise(impl: AliasLauncherDisguise): LauncherDisguise
}
