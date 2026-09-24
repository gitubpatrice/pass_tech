package com.filestech.pass_tech.core.vault

/**
 * Size buckets of the vault plaintext: 64 KiB, 256 KiB, 1 MiB... (64 KiB × 4^k), as in the Flutter
 * app. Every slot file ends up the size of the LARGEST, so the sizes say nothing about which slot
 * holds the real vault, nor about how many entries it has.
 *
 * Two ways of getting there, and the second is why [bucketsUpTo] exists. A session pads its OWN
 * plaintext to the common bucket before sealing it. For a slot it has no key to — an occupied one —
 * it cannot do that, and until 3.0.0 it simply left the file short, which is precisely what gave the
 * occupied slots away; it now writes random bytes after that file's ciphertext instead.
 *
 * The padding is trailing spaces (0x20): JSON ignores them, so no length field is needed.
 */
object Padding {

    const val FIRST_BUCKET = 64 * 1024
    private const val GROWTH = 4
    private const val SPACE: Byte = 0x20

    /** The smallest bucket that holds [length] bytes. */
    fun bucketFor(length: Int): Int {
        require(length >= 0) { "negative length" }
        var bucket = FIRST_BUCKET.toLong()
        while (bucket < length) bucket *= GROWTH
        return Math.toIntExact(bucket)
    }

    /**
     * Every bucket a payload could have been padded to, smallest first, up to [limit] bytes.
     *
     * A slot file may be LONGER than the payload it holds: a session that cannot re-pad another
     * slot's plaintext — it has no key for it — brings that file to the common size by writing random
     * bytes after the ciphertext instead. The reader therefore does not know where the ciphertext
     * ends, and tries these, in order. It is the authentication tag that answers, so nothing about
     * the true size has to be written down anywhere for an observer to read.
     */
    fun bucketsUpTo(limit: Int): List<Int> {
        val buckets = mutableListOf<Int>()
        var bucket = FIRST_BUCKET.toLong()
        while (bucket <= limit) {
            buckets += bucket.toInt()
            bucket *= GROWTH
        }
        return buckets
    }

    /** [plain] padded with spaces to exactly [target] bytes. The caller wipes both arrays. */
    fun pad(plain: ByteArray, target: Int): ByteArray {
        require(target >= plain.size) { "padding target smaller than the plaintext" }
        return plain.copyOf(target).also { it.fill(SPACE, fromIndex = plain.size) }
    }

    /** Length of [padded] without its trailing padding. */
    fun unpaddedLength(padded: ByteArray): Int {
        var end = padded.size
        while (end > 0 && padded[end - 1] == SPACE) end--
        return end
    }
}
