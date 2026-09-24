package com.filestech.pass_tech.core.heir

import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The mark on its own: what it says, what it refuses to say, and what it gives away by its shape.
 *
 * These three were found missing by negative controls — every one of the sabotages below went
 * uncaught by `HeirTest`, which exercises the mark only through the file it lives in and so never
 * touches the mark's own rules.
 */
class HeirMarkTest {

    private val keystore = InMemorySlotKeystore()

    /**
     * The one thing a copy of the private directory must not learn. A "real" mark is four letters
     * and a "dummy" one is five: without the fixed padding their sealed forms differ in length, and
     * the length alone would say which slot carries an heir — the very fact the mark exists to hide.
     */
    @Test
    fun `a real mark and a dummy one are the same size`() {
        val real = HeirMark.real.seal(Slot.A, keystore)
        val dummy = HeirMark.dummy.seal(Slot.A, keystore)
        assertThat(real.ciphertext.size).isEqualTo(dummy.ciphertext.size)
        assertThat(real.nonce.size).isEqualTo(dummy.nonce.size)
    }

    /** Two seals of the same mark differ: a random nonce, so the bytes themselves say nothing either. */
    @Test
    fun `the same mark sealed twice is not the same bytes`() {
        val once = HeirMark.real.seal(Slot.A, keystore)
        val twice = HeirMark.real.seal(Slot.A, keystore)
        assertThat(once.ciphertext).isNotEqualTo(twice.ciphertext)
        assertThat(HeirMark.openOrNull(once, Slot.A, keystore)).isEqualTo(HeirMark.real)
        assertThat(HeirMark.openOrNull(twice, Slot.A, keystore)).isEqualTo(HeirMark.real)
    }

    /**
     * The slot is inside the authenticated plaintext. Without that, a mark lifted from the dummy of
     * one slot and dropped into another slot's file would read as that slot's own, and a real
     * snapshot could be made to look like a dummy — after which the next save writes over it.
     */
    @Test
    fun `a mark does not read in another slot's file`() {
        val forA = HeirMark.real.seal(Slot.A, keystore)
        assertThat(HeirMark.openOrNull(forA, Slot.A, keystore)).isEqualTo(HeirMark.real)
        for (other in Slot.entries - Slot.A) {
            assertThat(HeirMark.openOrNull(forA, other, keystore)).isNull()
        }
    }

    /** Anything that is not this app's own mark reads as UNKNOWN, and an unknown file is never touched. */
    @Test
    fun `a mark that is not one reads as nothing`() {
        val real = HeirMark.real.seal(Slot.A, keystore)
        val tampered = com.filestech.pass_tech.core.vault.SlotKeystore.Wrapped(
            ciphertext = real.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() },
            nonce = real.nonce,
        )
        assertThat(HeirMark.openOrNull(tampered, Slot.A, keystore)).isNull()
    }

    @Test
    fun `both marks say what they are`() {
        assertThat(HeirMark.openOrNull(HeirMark.real.seal(Slot.B, keystore), Slot.B, keystore)?.real).isTrue()
        assertThat(HeirMark.openOrNull(HeirMark.dummy.seal(Slot.B, keystore), Slot.B, keystore)?.real).isFalse()
    }
}
