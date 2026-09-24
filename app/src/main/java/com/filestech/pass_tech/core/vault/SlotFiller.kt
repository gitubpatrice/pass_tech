package com.filestech.pass_tech.core.vault

import com.filestech.pass_tech.core.crypto.AesGcm
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.core.json.ensure
import com.filestech.pass_tech.core.json.objectOrNull
import com.filestech.pass_tech.core.json.orReject
import com.filestech.pass_tech.core.json.readOrNull
import com.filestech.pass_tech.core.json.requireString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * Evening out the slot files a session has no key for — vault slots and heir snapshots alike, which
 * is why it lives on its own rather than twice.
 *
 * ## What it is for
 *
 * Every slot file is meant to be the size of the largest, so that the sizes say nothing about which
 * slots are in use. A session pads its OWN plaintext before sealing it. For a slot it cannot open —
 * another vault's, or a real heir snapshot — it has no key, so it cannot re-pad anything.
 *
 * Until 3.0.0 it therefore left such a file at its old size. A vault that grew past a bucket left
 * every other occupied slot behind it, and a file smaller than the largest could only be an occupied
 * one: the decoy stopped being deniable to anyone holding a copy of the app's directory, which is
 * exactly the reader `Padding` exists to defeat (audit of 2026-09-24).
 *
 * ## How it works
 *
 * [grownTo] writes random bytes AFTER the ciphertext, inside the same field. No key is needed and
 * nothing the authentication tag covers is touched, so the file still opens. It only ever adds: a
 * file can be brought up to the common size by anyone, and brought back down only by the session
 * that holds its key, which knows where its own ciphertext ends.
 *
 * [openTrying] is the other half. The reader is not told where the ciphertext stops — writing that
 * down would hand back the very thing being hidden — so it tries each bucket the payload could have
 * been padded to, smallest first, and lets the tag answer. A wrong boundary fails exactly as a wrong
 * key does. A file with no filler matches on its first and only candidate, which is why files
 * written before any of this still open unchanged.
 */
object SlotFiller {

    private val json = Json

    /**
     * The same file with its ciphertext followed by enough random bytes to reach [targetBlobBytes].
     *
     * `null` when the file cannot be read as one of these envelopes, or is already that long — the
     * caller then leaves it exactly as it is, which is also what keeps a save from rewriting files
     * that need nothing.
     */
    fun grownTo(content: String, targetBlobBytes: Int): String? =
        readOrNull {
            val root = (json.parseToJsonElement(content) as? JsonObject).orReject()
            val cipher = root.objectOrNull(CIPHER).orReject()
            val blob = Base64.getDecoder().decode(cipher.requireString(DATA))
            ensure(targetBlobBytes > blob.size)
            val grown = blob + SecretBytes.random(targetBlobBytes - blob.size)
            val filled = JsonObject(cipher + (DATA to JsonPrimitive(Base64.getEncoder().encodeToString(grown))))
            json.encodeToString(JsonObject.serializer(), JsonObject(root + (CIPHER to filled)))
        }

    /**
     * Hands [open] each candidate end of the ciphertext in [cipherAndTag], smallest bucket first, and
     * returns the first payload that authenticates. `null` when none does — a wrong key, an altered
     * file, or a length no padding could have produced.
     */
    inline fun openTrying(cipherAndTag: ByteArray, open: (ByteArray) -> ByteArray?): ByteArray? {
        val room = cipherAndTag.size - AesGcm.TAG_LENGTH
        for (bucket in Padding.bucketsUpTo(room)) {
            val candidate = if (bucket == room) cipherAndTag else cipherAndTag.copyOf(bucket + AesGcm.TAG_LENGTH)
            open(candidate)?.let { return it }
        }
        return null
    }

    /** The two field names both envelopes share. */
    private const val CIPHER = "cipher"
    private const val DATA = "data"
}
