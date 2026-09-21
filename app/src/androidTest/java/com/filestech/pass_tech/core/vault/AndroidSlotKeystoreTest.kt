package com.filestech.pass_tech.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real AndroidKeyStore: only a device can tell whether wrapping works in StrongBox or the TEE,
 * and whether a deleted key really stops unwrapping.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSlotKeystoreTest {

    private val keystore = AndroidSlotKeystore()

    @After
    fun cleanUp() {
        Slot.entries.forEach(keystore::deleteKey)
    }

    @Test
    fun wrapsAndUnwrapsTheSlotSecret() {
        keystore.ensureKey(Slot.A)
        val secret = SecretBytes.random(32)
        val wrapped = keystore.wrap(Slot.A, secret)
        assertThat(wrapped.nonce).hasLength(12)
        assertThat(wrapped.ciphertext).hasLength(32 + 16)
        assertThat(keystore.unwrapOrNull(Slot.A, wrapped)).isEqualTo(secret)
    }

    @Test
    fun theKeyOfAnotherSlotUnwrapsNothing() {
        keystore.ensureKey(Slot.A)
        keystore.ensureKey(Slot.B)
        val wrapped = keystore.wrap(Slot.A, SecretBytes.random(32))
        assertThat(keystore.unwrapOrNull(Slot.B, wrapped)).isNull()
    }

    @Test
    fun aDeletedKeyUnwrapsNothingEvenOnceRecreated() {
        keystore.ensureKey(Slot.A)
        val wrapped = keystore.wrap(Slot.A, SecretBytes.random(32))
        keystore.deleteKey(Slot.A)
        assertThat(keystore.unwrapOrNull(Slot.A, wrapped)).isNull()
        keystore.ensureKey(Slot.A)
        assertThat(keystore.unwrapOrNull(Slot.A, wrapped)).isNull()
    }

    @Test
    fun alteredDataUnwrapsNothing() {
        keystore.ensureKey(Slot.A)
        val wrapped = keystore.wrap(Slot.A, SecretBytes.random(32))
        val altered = wrapped.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThat(keystore.unwrapOrNull(Slot.A, SlotKeystore.Wrapped(altered, wrapped.nonce))).isNull()
    }

    @Test
    fun ensureKeyKeepsAnExistingKey() {
        keystore.ensureKey(Slot.A)
        val wrapped = keystore.wrap(Slot.A, SecretBytes.random(32))
        keystore.ensureKey(Slot.A)
        assertThat(keystore.unwrapOrNull(Slot.A, wrapped)).isNotNull()
    }
}
