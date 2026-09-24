package com.filestech.pass_tech.core.password

/**
 * THE rule for every password and passphrase the app accepts: master password, decoy, heir,
 * `.ptbak` passphrase (2.7.1, `password_policy.dart`: one rule, so that no door is weaker than
 * the front one).
 *
 * No composition rule (symbol, digit, case), as NIST SP 800-63B advises: entropy is required, which
 * length alone can give. Twelve lowercase letters already make 56 bits.
 */
object PasswordPolicy {

    enum class Rejection { TOO_SHORT, TOO_WEAK }

    const val MIN_LENGTH = 12

    /** 0.6 = 48 bits. */
    private const val MIN_SCORE = 0.6

    /**
     * The entropy counts the classes present, not the characters used: `abababababab` was credited
     * 56 bits. Checked here, not in [PasswordStrength], whose score also feeds the strength gauge.
     */
    private const val MIN_DISTINCT_CHARS = 5

    /** `null` if [password] is acceptable. Length is reported before weakness: it says what to do. */
    fun check(password: String): Rejection? = when {
        password.length < MIN_LENGTH -> Rejection.TOO_SHORT
        password.toSet().size < MIN_DISTINCT_CHARS -> Rejection.TOO_WEAK
        PasswordStrength.score(password) < MIN_SCORE -> Rejection.TOO_WEAK
        else -> null
    }
}
