package com.filestech.pass_tech.core.di

import javax.inject.Qualifier

/** The scope that lives as long as the process: work that must not stop with a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
