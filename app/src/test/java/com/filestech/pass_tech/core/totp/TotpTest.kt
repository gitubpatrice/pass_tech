package com.filestech.pass_tech.core.totp

import com.filestech.pass_tech.core.totp.Totp.SecretError
import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 key "12345678901234567890" in Base32. */
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /** The RFC gives 8 digits; 6 digits are their last six. */
    @ParameterizedTest
    @CsvSource(
        "59, 287 082",
        "1111111109, 081 804",
        "1111111111, 050 471",
        "1234567890, 005 924",
        "2000000000, 279 037",
        "20000000000, 353 130",
    )
    fun `the RFC 6238 SHA-1 vectors`(time: Long, expected: String) {
        assertThat(Totp.code(rfcSecret, time)).isEqualTo(expected)
    }

    @Test
    fun `spaces, lower case and padding in the secret do not change the code`() {
        val messy = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq===="
        assertThat(Totp.code(messy, 59)).isEqualTo("287 082")
    }

    @Test
    fun `a secret that decodes to nothing shows dashes instead of a code`() {
        assertThat(Totp.code("", 59)).isEqualTo(Totp.INVALID_CODE)
        assertThat(Totp.code("1890!", 59)).isEqualTo(Totp.INVALID_CODE)
    }

    @Test
    fun `the countdown runs from 30 to 1`() {
        assertThat(Totp.secondsRemaining(0)).isEqualTo(30)
        assertThat(Totp.secondsRemaining(1)).isEqualTo(29)
        assertThat(Totp.secondsRemaining(29)).isEqualTo(1)
        assertThat(Totp.secondsRemaining(30)).isEqualTo(30)
    }

    @Test
    fun `validation, as 2_7_1`() {
        assertThat(Totp.validate(rfcSecret)).isNull()
        assertThat(Totp.validate("  ")).isEqualTo(SecretError.EMPTY)
        assertThat(Totp.validate("ABC1")).isEqualTo(SecretError.INVALID_CHARACTERS)
        assertThat(Totp.validate("ABCD EFGH")).isEqualTo(SecretError.TOO_SHORT)
        // 16 Base32 characters = exactly 10 bytes: the minimum.
        assertThat(Totp.validate("GEZDGNBVGY3TQOJQ")).isNull()
        assertThat(Totp.validate("GEZDGNBVGY3TQOJ")).isEqualTo(SecretError.TOO_SHORT)
    }

    @Test
    fun `the secret is taken out of a pasted otpauth URI`() {
        val uri = "otpauth://totp/Example:alice@example.com?secret=$rfcSecret&issuer=Example"
        assertThat(Totp.secretFromUri(uri)).isEqualTo(rfcSecret)
        assertThat(Totp.secretFromUri("  $uri\n")).isEqualTo(rfcSecret)
        assertThat(Totp.secretFromUri("otpauth://totp/A%20B?issuer=X&secret=$rfcSecret")).isEqualTo(rfcSecret)
    }

    @Test
    fun `anything else gives no secret`() {
        assertThat(Totp.secretFromUri(rfcSecret)).isNull()
        assertThat(Totp.secretFromUri("otpauth://hotp/A?secret=$rfcSecret&counter=1")).isNull()
        assertThat(Totp.secretFromUri("otpauth://evil.example/A?secret=$rfcSecret")).isNull()
        assertThat(Totp.secretFromUri("otpauth://totp/A?issuer=X")).isNull()
        assertThat(Totp.secretFromUri("otpauth://totp/A?secret=ABC1")).isNull()
        assertThat(Totp.secretFromUri("otpauth://totp/A B?secret=$rfcSecret")).isNull()
        val long = "otpauth://totp/A?secret=$rfcSecret&issuer=" + "x".repeat(Totp.MAX_URI_LENGTH)
        assertThat(Totp.secretFromUri(long)).isNull()
    }

    /**
     * The codes and verdicts of 2.7.1's own TotpService on awkward secrets (spaces, case, padding,
     * foreign characters, too short), each code recorded with the second it was computed in.
     * Resource: `compat/2.7.1/totp.json`, made by `tools/compat/totp_vectors_test.dart`.
     */
    @Test
    fun `every code and verdict of 2_7_1 is reproduced`() {
        val cases = Json.parseToJsonElement(Resources.text("compat/2.7.1/totp.json")).jsonArray
        assertWithMessage("the vector file lost its cases").that(cases.size).isAtLeast(20)
        for (case in cases.map { it.jsonObject }) {
            val secret = case.getValue("secret").jsonPrimitive.content
            val why = "secret <$secret>"
            val time = case.getValue("time").jsonPrimitive.long
            assertWithMessage(why).that(Totp.code(secret, time)).isEqualTo(case.getValue("code").jsonPrimitive.content)
            val error = when (case.getValue("validate").jsonPrimitive.contentOrNull) {
                "empty" -> SecretError.EMPTY
                "invalidCharacters" -> SecretError.INVALID_CHARACTERS
                "tooShort" -> SecretError.TOO_SHORT
                else -> null
            }
            assertWithMessage(why).that(Totp.validate(secret)).isEqualTo(error)
        }
    }
}
