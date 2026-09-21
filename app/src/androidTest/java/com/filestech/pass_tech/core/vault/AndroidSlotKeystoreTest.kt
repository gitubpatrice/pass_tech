package com.filestech.pass_tech.core.vault

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.pass_tech.core.crypto.SecretBytes
import com.filestech.pass_tech.testing.PrefixedKeystore
import com.filestech.pass_tech.testing.keyLevel
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real AndroidKeyStore: only a device can tell where each key lives, and how a missing key, a
 * foreign key and altered data really answer. Under prefixed aliases: see [PrefixedKeystore].
 */
@RunWith(AndroidJUnit4::class)
class AndroidSlotKeystoreTest {

    private val keystore = PrefixedKeystore(AndroidSlotKeystore())
    private val slotKeys = Slot.entries.map { it.hardwareKeyAlias }

    @After
    fun cleanUp() {
        keystore.deleteCreated()
    }

    private fun Any.bytes() = (this as KeyResult.Done<*>).value as ByteArray

    @Test
    fun wrapsAndUnwrapsInTheTee() {
        keystore.ensureAesKey(AES)
        val secret = SecretBytes.random(32)
        val wrapped = keystore.wrap(AES, secret)
        assertThat(wrapped.nonce).hasLength(12)
        assertThat(wrapped.ciphertext).hasLength(32 + 16)
        assertThat(keystore.unwrap(AES, wrapped).bytes()).isEqualTo(secret)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertThat(keyLevel(keystore.realAlias(AES))).isEqualTo("TEE")
    }

    @Test
    fun anotherKeyOrAlteredDataIsRefused() {
        keystore.ensureAesKey(AES)
        keystore.ensureAesKey(OTHER)
        val wrapped = keystore.wrap(AES, SecretBytes.random(32))
        assertThat(keystore.unwrap(OTHER, wrapped)).isEqualTo(KeyResult.Refused)
        val altered = wrapped.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThat(keystore.unwrap(AES, SlotKeystore.Wrapped(altered, wrapped.nonce))).isEqualTo(KeyResult.Refused)
    }

    @Test
    fun aDeletedKeyIsMissingAndARecreatedOneRefuses() {
        keystore.ensureAesKey(AES)
        val wrapped = keystore.wrap(AES, SecretBytes.random(32))
        keystore.deleteKey(AES)
        assertThat(keystore.unwrap(AES, wrapped)).isEqualTo(KeyResult.NoKey)
        keystore.ensureAesKey(AES)
        assertThat(keystore.unwrap(AES, wrapped)).isEqualTo(KeyResult.Refused)
    }

    @Test
    fun ensureKeepsExistingKeys() {
        keystore.ensureAesKey(AES)
        keystore.ensureHmacKeys(slotKeys)
        val wrapped = keystore.wrap(AES, SecretBytes.random(32))
        val tag = keystore.hmac(slotKeys[0], INPUT).bytes()
        keystore.ensureAesKey(AES)
        keystore.ensureHmacKeys(slotKeys)
        assertThat(keystore.unwrap(AES, wrapped)).isInstanceOf(KeyResult.Done::class.java)
        assertThat(keystore.hmac(slotKeys[0], INPUT).bytes()).isEqualTo(tag)
    }

    @Test
    fun slotKeysAreDeterministicDistinctAndAllAtOneLevel() {
        keystore.ensureHmacKeys(slotKeys)
        val tags = slotKeys.map { keystore.hmac(it, INPUT).bytes() }
        assertThat(tags.map { it.size }.distinct()).containsExactly(32)
        assertThat(tags.map { it.toList() }.distinct()).hasSize(3)
        assertThat(keystore.hmac(slotKeys[0], INPUT).bytes()).isEqualTo(tags[0])

        val levels = slotKeys.map { keyLevel(keystore.realAlias(it)) }.distinct()
        assertThat(levels).hasSize(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasStrongBox = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
                .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            assertThat(levels.single()).isEqualTo(if (hasStrongBox) "StrongBox" else "TEE")
        }
    }

    @Test
    fun aMissingSlotKeySaysSo() {
        assertThat(keystore.hmac(slotKeys[0], INPUT)).isEqualTo(KeyResult.NoKey)
    }

    private companion object {
        const val AES = "pt_occ"
        const val OTHER = "pt_state"
        val INPUT = "pt:v5|slot=a|".encodeToByteArray() + ByteArray(32) { it.toByte() }
    }
}
