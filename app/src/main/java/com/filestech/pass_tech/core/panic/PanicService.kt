package com.filestech.pass_tech.core.panic

import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.vault.VaultManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Panic mode (design v2.1 §11 bis, point 7): the owner is being made to hand the phone over.
 *
 * Four steps, in this order, each on its own: **a step that fails never stops the next**. The vault
 * first, because that is the one that matters and the only one that cannot be undone from outside.
 *
 * 1. lock the vault — the key leaves memory;
 * 2. clear the clipboard, and the clearing it was waiting for;
 * 3. disarm the fingerprint — otherwise someone can hold the owner's finger to the sensor and open
 *    the very vault this exists to protect. Re-arming asks for the master password anyway;
 * 4. disguise the launcher.
 *
 * **It writes no vault file.** Nothing is erased: the owner unlocks with their password afterwards.
 *
 * Two things 2.7.1 did here and this does not:
 * - it shredded the exports left in its cache. There are none to shred: an export is written straight
 *   into the document the owner picked, and the app keeps no copy (phase 4);
 * - it erased the failed-attempt count and the lockout, so that the state after a panic looked like a
 *   fresh boot. That would hand anyone holding a decoy a free way to clear a running lockout, and the
 *   lockout is what stands between them and the real vault — the exact flaw recorded against 2.7.1
 *   (design §12). A lockout says someone mistyped a password, which says nothing about a panic.
 */
@Singleton
class PanicService @Inject constructor(
    private val vault: VaultManager,
    private val clipboard: SensitiveClipboard,
    private val disguise: LauncherDisguise,
) {

    suspend fun panic() {
        step { vault.lock() }
        step { clipboard.clear() }
        step { vault.disarmBiometrics() }
        step { disguise.set(true) }
    }

    /** Shows the Pass Tech name and icon again, from the settings, once the owner is safe. */
    fun reveal(): Boolean = disguise.set(false)

    /** `null` when the system did not answer: see [LauncherDisguise.disguised]. */
    fun disguised(): Boolean? = disguise.disguised()

    /**
     * Runs [action], swallowing whatever it throws. A Keystore that does not answer must not keep the
     * clipboard full or the launcher un-disguised; the owner has no second chance at this moment.
     */
    private suspend fun step(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // Nothing to report: there is no screen left to report it to, and no second attempt.
        }
    }
}
