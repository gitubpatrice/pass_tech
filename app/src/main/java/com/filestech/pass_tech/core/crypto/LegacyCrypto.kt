package com.filestech.pass_tech.core.crypto

import java.nio.ByteBuffer
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives of the pre-Argon2 formats: PBKDF2-HMAC-SHA256, HMAC-SHA256 and AES-256-CBC.
 *
 * READ ONLY. They exist so that `.ptbak` v1 and v2 backups made by the Flutter app before 2.4.3
 * still open. Nothing in the Kotlin app ever writes with them.
 */
object LegacyCrypto {

    private const val HMAC = "HmacSHA256"
    private const val HASH_LENGTH = 32
    private const val AES_BLOCK = 16

    /**
     * PBKDF2-HMAC-SHA256 over raw password bytes.
     *
     * Implemented here rather than through `SecretKeyFactory("PBKDF2WithHmacSHA256")`: that API takes
     * a `char[]` and converts it to bytes itself, and the conversion of characters outside the Basic
     * Multilingual Plane is provider-dependent. The Flutter app hashed `utf8.encode(password)`; so do we.
     */
    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1) { "PBKDF2 needs at least one iteration" }
        require(length >= 1) { "PBKDF2 output length must be positive" }
        val mac = Mac.getInstance(HMAC).apply {
            // An empty key is legal for HMAC, but not for SecretKeySpec: HMAC pads the key with zeros
            // to the block size, so a single zero byte is the same key.
            init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, HMAC))
        }
        val out = ByteArray(length)
        val blocks = (length + HASH_LENGTH - 1) / HASH_LENGTH
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(intToBigEndian(block))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                val next = mac.doFinal(u)
                u.wipe()
                u = next
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            u.wipe()
            val offset = (block - 1) * HASH_LENGTH
            t.copyInto(out, destinationOffset = offset, startIndex = 0, endIndex = minOf(HASH_LENGTH, length - offset))
            t.wipe()
        }
        return out
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, HMAC))
            parts.forEach(::update)
            doFinal()
        }

    /** AES-256-CBC with PKCS#7 padding. `null` on bad padding or a malformed length. */
    fun aesCbcDecryptOrNull(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (iv.size != AES_BLOCK || ciphertext.isEmpty() || ciphertext.size % AES_BLOCK != 0) return null
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(ciphertext)
            }
        } catch (_: BadPaddingException) {
            null
        } catch (_: IllegalBlockSizeException) {
            null
        }
    }

    private fun intToBigEndian(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
}
