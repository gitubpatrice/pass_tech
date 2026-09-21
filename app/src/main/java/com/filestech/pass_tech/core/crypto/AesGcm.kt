package com.filestech.pass_tech.core.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 96-bit nonce and a 128-bit tag, the only authenticated cipher of Pass Tech.
 *
 * The ciphertext is always handled as `ciphertext || tag`, the layout both the Flutter app and the
 * JCA use, so files written by either side read on the other without any reshuffling.
 */
object AesGcm {

    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = TAG_LENGTH * Byte.SIZE_BITS

    class Sealed(val nonce: ByteArray, val cipherAndTag: ByteArray)

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray = SecretBytes.random(NONCE_LENGTH),
    ): Sealed {
        checkSizes(key, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return Sealed(nonce = nonce, cipherAndTag = cipher.doFinal(plaintext))
    }

    /**
     * @return the plaintext, or `null` if the tag does not verify: wrong key, altered data, or AAD
     * that does not match. The three are indistinguishable by design.
     */
    fun decryptOrNull(key: ByteArray, nonce: ByteArray, cipherAndTag: ByteArray, aad: ByteArray): ByteArray? {
        if (key.size != KEY_LENGTH || nonce.size != NONCE_LENGTH || cipherAndTag.size < TAG_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return try {
            cipher.doFinal(cipherAndTag)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun checkSizes(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_LENGTH) { "AES-256-GCM needs a $KEY_LENGTH-byte key" }
        require(nonce.size == NONCE_LENGTH) { "AES-GCM nonce must be $NONCE_LENGTH bytes" }
    }
}
