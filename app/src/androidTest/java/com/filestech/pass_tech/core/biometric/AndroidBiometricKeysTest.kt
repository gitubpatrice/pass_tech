package com.filestech.pass_tech.core.biometric

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.pass_tech.core.vault.KeystoreUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The real biometric key: only a device can tell that it refuses to work without a biometric. Under
 * its own alias, never `pt_bio`: the tests run inside the app they test.
 *
 * Both branches assert something, so that a run on a phone with no fingerprint is not counted as a
 * pass that tested nothing: there, the system must refuse to create the key at all.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBiometricKeysTest {

    private val keys = AndroidBiometricKeys(ALIAS)
    private val enrolled = BiometricSupport.of(InstrumentationRegistry.getInstrumentation().targetContext).available()

    @After
    fun cleanUp() {
        keys.delete()
    }

    @Test
    fun theKeyWorksOnlyRightAfterAStrongBiometric() {
        if (!enrolled) {
            assertThrows(KeystoreUnavailableException::class.java) { keys.cipherToSeal() }
            return
        }
        val cipher = keys.cipherToSeal()
        // No prompt was shown: the secure hardware refuses to seal.
        assertThrows(Exception::class.java) { cipher.doFinal(ByteArray(KEY_BYTES)) }
        val info = keyInfo()
        assertThat(info.isUserAuthenticationRequired).isTrue()
        assertThat(info.isInvalidatedByBiometricEnrollment).isTrue()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertThat(info.userAuthenticationType).isEqualTo(KeyProperties.AUTH_BIOMETRIC_STRONG)
            assertThat(info.userAuthenticationValidityDurationSeconds).isEqualTo(0)
        } else {
            assertThat(info.userAuthenticationValidityDurationSeconds).isEqualTo(-1)
        }
    }

    @Test
    fun aDeletedKeyOpensNothing() {
        if (enrolled) keys.cipherToSeal()
        keys.delete()
        assertThat(keys.cipherToOpen(ByteArray(NONCE_BYTES))).isNull()
    }

    private fun keyInfo(): KeyInfo {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey
        return SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    private companion object {
        const val ALIAS = "test_pt_bio"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
    }
}
