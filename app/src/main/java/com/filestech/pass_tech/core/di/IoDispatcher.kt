package com.filestech.pass_tech.core.di

import javax.inject.Qualifier

/**
 * The dispatcher for blocking work: disk, Keystore, and the Argon2id derivations of the vault.
 *
 * Qualifiers live in `core/`, not next to the modules in `di/`: a qualifier is only a name (it pulls
 * in `javax.inject`, nothing else), and the classes that carry it must not depend on the package that
 * wires the application together (Agenda Tech audit, 2026-09-11).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
