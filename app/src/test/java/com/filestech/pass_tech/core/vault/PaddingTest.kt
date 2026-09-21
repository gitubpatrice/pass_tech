package com.filestech.pass_tech.core.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PaddingTest {

    @ParameterizedTest
    @CsvSource("0, 65536", "1, 65536", "65536, 65536", "65537, 262144", "262144, 262144", "262145, 1048576")
    fun `buckets grow by four from 64 KiB`(length: Int, bucket: Int) {
        assertThat(Padding.bucketFor(length)).isEqualTo(bucket)
    }

    @Test
    fun `padding adds spaces only, and the unpadded length finds the content back`() {
        val plain = """{"entries":[]}""".encodeToByteArray()
        val padded = Padding.pad(plain, Padding.FIRST_BUCKET)
        assertThat(padded).hasLength(Padding.FIRST_BUCKET)
        assertThat(padded.copyOf(plain.size)).isEqualTo(plain)
        assertThat(padded.drop(plain.size).all { it == ' '.code.toByte() }).isTrue()
        assertThat(Padding.unpaddedLength(padded)).isEqualTo(plain.size)
    }
}
