package com.filestech.pass_tech.core.crypto

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Argon2id on a real device, with the parameters every vault and backup is written with.
 *
 * Two things only a device can tell: that BouncyCastle gives the same bytes on ART as on the JVM
 * (the RFC 9106 vector), and how long an unlock actually waits for the derivation. The timings are
 * written to logcat under the tag below.
 */
@RunWith(AndroidJUnit4::class)
class Argon2idOnDeviceTest {

    @Test
    fun rfc9106VectorOnArt() {
        val out = Argon2id.derive(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            params = KdfParams(memoryKiB = 32, iterations = 3, parallelism = 4),
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )
        assertThat(out.joinToString("") { "%02x".format(it) })
            .isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }

    @Test
    fun owaspParametersTiming() {
        val password = "Correct horse — batterie agrafée ✓ 2026".encodeToByteArray()
        val salt = ByteArray(32) { it.toByte() }
        val timings = (1..RUNS).map {
            val start = SystemClock.elapsedRealtime()
            Argon2id.derive(password, salt, KdfParams.OWASP_MOBILE_2024)
            SystemClock.elapsedRealtime() - start
        }
        Log.i(TAG, "Argon2id 19456 KiB / t=2 / p=1, $RUNS runs (ms): $timings, median ${timings.sorted()[RUNS / 2]}")
        // A generous ceiling, only to catch a pathological regression: the figure that matters is the log.
        assertThat(timings.sorted()[RUNS / 2]).isLessThan(CEILING_MS)
    }

    private companion object {
        const val TAG = "PassTechArgon2"
        const val RUNS = 5
        const val CEILING_MS = 5_000L
    }
}
