package com.filestech.pass_tech.core.password

import com.filestech.pass_tech.core.password.PasswordPolicy.Rejection
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The cases of the 2.7.1 tests (`password_policy_test.dart`, `password_strength_test.dart`), same verdicts. */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "chienbleumatin", "renardclochesoleil", "renard-cloche-violet-soleil-7", "MonCoffreFort2026",
            "le chat dort sur le toit", "abricotmiel12", "motdepassequejaichoisi", "CHIENBLEUMATIN",
        ],
    )
    fun `accepted, or a legitimate user would be locked out`(password: String) {
        assertThat(PasswordPolicy.check(password)).isNull()
    }

    @Test
    fun `too short`() {
        assertThat(PasswordPolicy.check("court")).isEqualTo(Rejection.TOO_SHORT)
        assertThat(PasswordPolicy.check("a".repeat(PasswordPolicy.MIN_LENGTH - 1))).isEqualTo(Rejection.TOO_SHORT)
        assertThat(PasswordPolicy.check("")).isEqualTo(Rejection.TOO_SHORT)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["aaaaaaaaaaaa", "abababababab", "abcdefghijkl", "123456789012345", "motdepasse123", "Password1234!", "zzzzzzzzzzzz"],
    )
    fun `long enough, but guessable`(password: String) {
        assertThat(PasswordPolicy.check(password)).isEqualTo(Rejection.TOO_WEAK)
    }

    @Test
    fun `common passwords are found under their dressing`() {
        assertThat(PasswordStrength.isCommon("P@ssw0rd")).isTrue()
        assertThat(PasswordStrength.isCommon("M0tD3P@sse")).isTrue()
        assertThat(PasswordStrength.isCommon("azerty2024")).isTrue()
        assertThat(PasswordStrength.isCommon("chaise-nuage-turbine")).isFalse()
        assertThat(PasswordStrength.entropyBits("motdepasse")).isEqualTo(0.0)
        assertThat(PasswordStrength.entropyBits("")).isEqualTo(0.0)
    }

    /**
     * The cost of [PasswordStrength.isCommon] follows the LENGTH of what it is given, and an
     * imported file chooses that length. This is the bound, and the two assertions are what it must
     * not break: a real password still gets a verdict, and a very long one is simply not "common".
     */
    @Test
    fun `a value far longer than any common root is not searched at all`() {
        // The discriminating case, and the reason it is written this way: without the bound, the
        // dressing is stripped, "password" is left, and the answer is TRUE — after a scan of the
        // whole value by every root. With it, the answer is false without looking, and false is also
        // the right answer: 308 characters is not a common password.
        assertThat(PasswordStrength.isCommon("password" + "!".repeat(300))).isFalse()
        assertThat(PasswordStrength.isWeak("password" + "!".repeat(300))).isFalse()
        // The bound does not reach down to anything a person would type.
        assertThat(PasswordStrength.isCommon("password" + "!".repeat(8))).isTrue()
        assertThat(PasswordStrength.isCommon("a".repeat(64 * 1024))).isFalse()
    }

    @Test
    fun `repetitions and sequences are discounted`() {
        assertThat(PasswordStrength.score("123456789012")).isLessThan(0.35)
        assertThat(PasswordStrength.entropyBits("aaaaaaaaaaaa")).isLessThan(PasswordStrength.entropyBits("qmzkvjxwpfhb") / 2)
    }

    @Test
    fun `the gauge levels follow the 2 7 1 thresholds`() {
        assertThat(PasswordStrength.level(0.34)).isEqualTo(PasswordStrength.Level.WEAK)
        assertThat(PasswordStrength.level(0.35)).isEqualTo(PasswordStrength.Level.MEDIUM)
        assertThat(PasswordStrength.level(0.65)).isEqualTo(PasswordStrength.Level.STRONG)
        assertThat(PasswordStrength.level(0.85)).isEqualTo(PasswordStrength.Level.VERY_STRONG)
    }
}
