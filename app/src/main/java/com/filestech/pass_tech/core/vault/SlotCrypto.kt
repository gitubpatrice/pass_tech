package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.Argon2id
import com.filestech.pass_tech.core.crypto.HkdfSha256
import com.filestech.pass_tech.core.crypto.KdfParams
import com.filestech.pass_tech.core.crypto.useThenWipe

/**
 * The key derivation every file of a slot shares (design v2.2): the vault itself, and the heir
 * snapshot beside it.
 * ```
 * pwHash   = Argon2id(password, salt, m, t, p)                  32 bytes, parameters in the file
 * hw       = HMAC-SHA256(slot key, domain || pwHash)            32 bytes, computed INSIDE the hardware
 * finalKey = HKDF-SHA256(salt, pwHash || hw, info, 32)
 * ```
 * The slot key ([Slot.hardwareKeyAlias]) never leaves the hardware and takes part in EVERY attempt:
 * with a copy of the files, or even with code running on the phone, a guess can only be checked on
 * this phone, through its Keystore, for as long as the key cannot be extracted. The v4 of the Flutter
 * app wrapped a random secret instead, which does not depend on the password: one unwrap was enough
 * to take the files elsewhere and test passwords at GPU speed (GPT 5.6 review of the design, v2.2).
 *
 * **[domain] and [info] separate the two kinds of file.** The same passphrase on the same slot gives
 * two unrelated keys, so a heir snapshot put where a vault file belongs — or the other way round —
 * cannot be opened by the password of the other.
 */
internal object SlotCrypto {

    const val KEY_LENGTH = 32
    const val SALT_LENGTH = 32

    /**
     * [KeyResult.Done] carries the key; otherwise the reason there is none: [KeyResult.NoKey] (the
     * slot key is gone, this file opens nothing, ever) or [KeyResult.Unavailable] (the Keystore did
     * not answer: nothing is known, retry). The hardware HMAC runs whatever Argon2id gave, so that
     * every slot costs the same work.
     */
    fun deriveKey(
        slot: Slot,
        params: KdfParams,
        salt: ByteArray,
        password: ByteArray,
        keystore: SlotKeystore,
        domain: String,
        info: ByteArray,
    ): KeyResult<ByteArray> =
        Argon2id.derive(password, salt, params).useThenWipe { pwHash ->
            val hw = (domain.encodeToByteArray() + pwHash).useThenWipe { keystore.hmac(slot.hardwareKeyAlias, it) }
            if (hw is KeyResult.Done) {
                hw.value.useThenWipe { tag ->
                    KeyResult.Done((pwHash + tag).useThenWipe { ikm -> HkdfSha256.derive(salt, ikm, info, KEY_LENGTH) })
                }
            } else {
                hw
            }
        }
}
