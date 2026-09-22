package com.filestech.pass_tech.di

import com.filestech.pass_tech.core.clipboard.SecureClipboard
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ClipboardModule {

    @Binds
    abstract fun sensitiveClipboard(impl: SecureClipboard): SensitiveClipboard
}
