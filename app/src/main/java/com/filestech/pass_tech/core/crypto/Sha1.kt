package com.filestech.pass_tech.core.crypto

import java.security.MessageDigest
import java.util.Locale

/**
 * SHA-1, for one purpose only: the k-anonymity protocol of the breach check, which is defined on it.
 * Nothing in the vault is protected by it.
 *
 * The UTF-8 buffer is wiped once the digest is taken. This is the path where the owner deliberately
 * submits their most sensitive passwords, one after another — 2.7.1 added the same wipe here after
 * finding five other places missing it.
 */
object Sha1 {

    fun hex(text: String): String {
        val bytes = text.toByteArray()
        return try {
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
        } finally {
            bytes.wipe()
        }
    }

    /** What the service is asked about, and what it answers with: the first five characters, then the rest. */
    fun prefixOf(hex: String): String = hex.take(PREFIX_LENGTH).uppercase(Locale.ROOT)

    fun suffixOf(hex: String): String = hex.drop(PREFIX_LENGTH).uppercase(Locale.ROOT)

    const val PREFIX_LENGTH = 5
}
