package com.filestech.pass_tech.core.password

import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The verdicts of the Flutter app itself (2.7.1), produced by running its own code on a fixed list of
 * passwords (accents, emoji, whitespace, l33t, runs), then compared to the last bit: a different
 * rounding of log2, another reading of `\W` or of the case would show here.
 * Resource: `compat/2.7.1/password_strength.json`.
 */
class PasswordStrengthCompatTest {

    @Test
    fun `every verdict of 2 7 1 is reproduced exactly`() {
        val cases = Json.parseToJsonElement(Resources.text("compat/2.7.1/password_strength.json")).jsonObject
        assertWithMessage("the vector file lost its cases").that(cases.size).isAtLeast(40)
        for ((password, expected) in cases) {
            val dart = expected.jsonObject
            val why = "password <$password>"
            assertWithMessage(why).that(PasswordStrength.entropyBits(password)).isEqualTo(dart.getValue("entropyBits").jsonPrimitive.double)
            assertWithMessage(why).that(PasswordStrength.score(password)).isEqualTo(dart.getValue("score").jsonPrimitive.double)
            assertWithMessage(why).that(PasswordStrength.isCommon(password)).isEqualTo(dart.getValue("isCommon").jsonPrimitive.boolean)
            assertWithMessage(why).that(PasswordStrength.isWeak(password)).isEqualTo(dart.getValue("isWeak").jsonPrimitive.boolean)
            val rejection = when (dart.getValue("check").jsonPrimitive.contentOrNull) {
                "tooShort" -> PasswordPolicy.Rejection.TOO_SHORT
                "tooWeak" -> PasswordPolicy.Rejection.TOO_WEAK
                else -> null
            }
            assertWithMessage(why).that(PasswordPolicy.check(password)).isEqualTo(rejection)
        }
    }
}
