package com.filestech.pass_tech.core.about

import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.model.EntryType
import com.filestech.pass_tech.core.password.Diceware
import com.filestech.pass_tech.core.password.PasswordGenerator
import com.filestech.pass_tech.core.security.BruteForceGuard
import com.filestech.pass_tech.core.settings.AppPreferences
import com.filestech.pass_tech.core.update.UpdateCheck

/**
 * Every number the About screen says out loud, read from the code that enforces it.
 *
 * The About screen is the one place where the app describes itself, and 2.7.1's had drifted from the
 * app three times over: it described a key the design had removed, announced a check at a moment the
 * check no longer happens, and named a file no release has ever published. Prose cannot be pinned
 * down, but numbers can — so none of these is typed into a translation. "Five attempts" is
 * [BruteForceGuard]'s own five; change the schedule and the sentence changes with it, in every
 * language at once.
 *
 * Nothing here holds a value of its own. A constant defined in this file would be exactly the second
 * copy the file exists to prevent. Durations are in milliseconds, so that the screen can put them
 * through the same formatter as every other delay the app shows.
 */
object AppFacts {

    /** Argon2id, as every vault and every `.ptbak` derives: memory in MiB, and the passes over it. */
    val argonMemoryMiB: Int = KdfParams.OWASP_MOBILE_2024.memoryKiB / KIB_PER_MIB
    val argonIterations: Int = KdfParams.OWASP_MOBILE_2024.iterations

    /** Guesses allowed before the first delay, then the shortest and longest delays of the schedule. */
    val freeAttempts: Int = BruteForceGuard.VAULT_FREE_ATTEMPTS
    val firstLockMillis: Long = BruteForceGuard.VAULT_LOCKS.min()
    val lastLockMillis: Long = BruteForceGuard.VAULT_LOCKS.max()

    /** The generator's bounds. */
    val generatorMinLength: Int = PasswordGenerator.MIN_LENGTH
    val generatorMaxLength: Int = PasswordGenerator.MAX_LENGTH

    /**
     * How many words a passphrase is drawn from. A function and not a value: the list depends on
     * the language the app is showing, and that can change under a running process — Android
     * applies a per-app language to the process default. A value read once would state the count
     * of the list the app started with, and the screen would keep showing it afterwards.
     */
    fun dicewareWords(): Int = Diceware.Language.current().words.size

    /**
     * The shortest and longest clearing delays the clipboard offers. Kept apart from the choices
     * themselves: `0` in that list means "never cleared", which is neither of these two.
     */
    val clipboardMinMillis: Long = AppPreferences.CLIPBOARD_CHOICES.filter { it > 0 }.min() * MILLIS_PER_SECOND
    val clipboardMaxMillis: Long = AppPreferences.CLIPBOARD_CHOICES.max() * MILLIS_PER_SECOND

    /**
     * The longest the vault stays open once the app is left. `AppPreferences.NEVER` is negative and
     * `0` is "immediately", so the largest number in the list is the longest real delay.
     */
    val autoLockMaxMillis: Long = AppPreferences.AUTO_LOCK_CHOICES.max() * MILLIS_PER_SECOND

    /** How many kinds of entry the vault holds — a password, a card, a note, as of today. */
    val entryTypes: Int = EntryType.entries.size

    /** How often the update check is willing to ask GitHub anything, at most. */
    val updateChecksPerDay: Int = (MILLIS_PER_DAY / UpdateCheck.BETWEEN_CHECKS_MILLIS).toInt()
}

private const val KIB_PER_MIB = 1024
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_DAY = 24 * 60 * 60 * MILLIS_PER_SECOND
