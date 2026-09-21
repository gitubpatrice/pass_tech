package com.filestech.pass_tech.core.vault

/**
 * Size buckets of the vault plaintext: 64 KiB, 256 KiB, 1 MiB... (64 KiB × 4^k), as in the Flutter
 * app. Every slot is padded to the bucket of the LARGEST slot, so the file sizes say nothing about
 * which slot holds the real vault, nor about how many entries it has, until the vault outgrows the
 * first bucket (about 200 entries).
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
