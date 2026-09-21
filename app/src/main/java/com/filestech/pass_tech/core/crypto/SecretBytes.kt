package com.filestech.pass_tech.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Helpers for byte arrays that hold secrets: keys, derived material, plaintext.
 *
 * Kotlin gives what Dart could not: every buffer here is a plain, mutable `ByteArray`, so a wipe
 * always happens. The Flutter app could receive read-only views from platform channels, on which
 * its `SecretBytes.wipe` silently did nothing (the hardware secret of 2.7.1 was one of them).
 */
object SecretBytes {

    private val random = SecureRandom()

    fun random(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)

    /** Constant-time comparison. Arrays of different lengths are unequal, without a timing leak on the content. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** Overwrites the array with zeros. */
fun ByteArray.wipe() {
    fill(0)
}

/** Runs [block] with this secret, then wipes it, whatever happens. */
inline fun <T> ByteArray.useThenWipe(block: (ByteArray) -> T): T =
    try {
        block(this)
    } finally {
        wipe()
    }
