package com.filestech.pass_tech.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256 (RFC 5869). */
object HkdfSha256 {

    private const val HASH_LENGTH = 32
    private const val MAX_BLOCKS = 255
    private const val ALGORITHM = "HmacSHA256"

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_BLOCKS * HASH_LENGTH) { "HKDF output length out of range: $length" }

        // RFC 5869 §2.2: an absent salt is HashLen zeros.
        val extractKey = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        val prk = hmac(extractKey, ikm)
        try {
            val out = ByteArray(length)
            val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(prk, ALGORITHM)) }
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                val block = mac.doFinal()
                previous.wipe()
                previous = block
                val take = minOf(HASH_LENGTH, length - offset)
                block.copyInto(out, destinationOffset = offset, startIndex = 0, endIndex = take)
                offset += take
                counter++
            }
            previous.wipe()
            return out
        } finally {
            prk.wipe()
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(key, ALGORITHM))
            doFinal(data)
        }
}
