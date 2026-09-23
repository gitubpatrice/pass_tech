package com.filestech.pass_tech.core.about

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.password.Diceware
import com.filestech.pass_tech.core.password.PasswordGenerator
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.update.UpdateCheck
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Whether what the About screen says out loud is still what the app does.
 *
 * These are not tests of arithmetic. Each one holds a claim the screen makes against the code that
 * has to make it true, so that changing one without the other turns a test red rather than leaving a
 * sentence behind. That is the exact failure 2.7.1's About screen had, three times over.
 */
class AppFactsTest {

    @Test
    fun `the vault's own derivation parameters are the ones described`() {
        assertThat(AppFacts.argonMemoryMiB * 1024).isEqualTo(KdfParams.OWASP_MOBILE_2024.memoryKiB)
        assertThat(AppFacts.argonIterations).isEqualTo(KdfParams.OWASP_MOBILE_2024.iterations)
        // The value the screen shows today. A change here is a change the screen must be read for.
        assertThat(AppFacts.argonMemoryMiB).isEqualTo(19)
        assertThat(AppFacts.argonIterations).isEqualTo(2)
    }

    @Test
    fun `the attempts and delays are the guard's own`() {
        assertThat(AppFacts.freeAttempts).isEqualTo(BruteForceGuard.VAULT_FREE_ATTEMPTS)
        assertThat(AppFacts.firstLockMillis).isEqualTo(BruteForceGuard.VAULT_LOCKS.min())
        assertThat(AppFacts.lastLockMillis).isEqualTo(BruteForceGuard.VAULT_LOCKS.max())
        assertThat(AppFacts.freeAttempts).isEqualTo(5)
        assertThat(AppFacts.firstLockMillis).isEqualTo(30_000)
        assertThat(AppFacts.lastLockMillis).isEqualTo(30 * 60 * 1000L)
    }

    /** The shortest delay must be the first one served, or "grows from 30 s to 30 min" is a lie. */
    @Test
    fun `the schedule only ever grows`() {
        assertThat(BruteForceGuard.VAULT_LOCKS).isInOrder()
        assertThat(BruteForceGuard.VAULT_LOCKS.first()).isEqualTo(AppFacts.firstLockMillis)
        assertThat(BruteForceGuard.VAULT_LOCKS.last()).isEqualTo(AppFacts.lastLockMillis)
    }

    @Test
    fun `the generator's bounds and its word list are the generator's`() {
        assertThat(AppFacts.generatorMinLength).isEqualTo(PasswordGenerator.MIN_LENGTH)
        assertThat(AppFacts.generatorMaxLength).isEqualTo(PasswordGenerator.MAX_LENGTH)
        assertThat(AppFacts.dicewareWords()).isEqualTo(Diceware.Language.current().words.size)
        // The values shown today. These two were missing, and the negative control that moved
        // MAX_LENGTH to 128 went uncaught: the binding held, so the screen would have followed, but
        // nothing said a word about a limit the app announces changing under it.
        assertThat(AppFacts.generatorMinLength).isEqualTo(8)
        assertThat(AppFacts.generatorMaxLength).isEqualTo(64)
        // 2.7.1's own comment said 512 words for a list of 471, and showed the entropy of neither.
        // The count is now the one of the list the app's language draws from, so it is checked
        // against that list and not against a number fixed here.
        assertThat(AppFacts.dicewareWords()).isAtLeast(400)
        assertThat(Diceware.Language.FRENCH.words).hasSize(471)
    }

    /**
     * "After 15 s to 60 s, or never". `0` in the list of choices means never cleared, and reading it
     * as a delay would put "after 0 s to 60 s" on the screen — a promise the app does not keep.
     */
    @Test
    fun `never is not read as a clipboard delay`() {
        assertThat(AppPreferences.CLIPBOARD_CHOICES).contains(0)
        assertThat(AppFacts.clipboardMinMillis).isEqualTo(15_000)
        assertThat(AppFacts.clipboardMaxMillis).isEqualTo(60_000)
    }

    /**
     * Same trap on the other side: `NEVER` is negative and `0` is "immediately", so the longest real
     * delay is the largest number, and it is the one the screen names.
     */
    @Test
    fun `the longest auto-lock delay is a real delay`() {
        assertThat(AppPreferences.AUTO_LOCK_CHOICES).contains(AppPreferences.NEVER)
        assertThat(AppFacts.autoLockMaxMillis).isEqualTo(30 * 60 * 1000L)
        assertThat(AppPreferences.AUTO_LOCK_CHOICES.max() * 1000L).isEqualTo(AppFacts.autoLockMaxMillis)
    }

    @Test
    fun `the number of kinds of entry is the number of kinds of entry`() {
        assertThat(AppFacts.entryTypes).isEqualTo(3)
    }

    /** "Twice a day at most" has to be what the twelve-hour delay actually allows. */
    @Test
    fun `the checks a day match the delay between checks`() {
        assertThat(AppFacts.updateChecksPerDay).isEqualTo(2)
        assertThat(UpdateCheck.BETWEEN_CHECKS_MILLIS * AppFacts.updateChecksPerDay).isEqualTo(24 * 60 * 60 * 1000L)
    }
}
