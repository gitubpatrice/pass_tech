package com.filestech.pass_tech.core.totp

import com.filestech.pass_tech.core.crypto.wipe
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Two-factor codes, RFC 6238: HMAC-SHA1, 30-second steps, 6 digits. A port of 2.7.1
 * (`lib/services/totp_service.dart`), rule for rule.
 *
 * The time is the WALL clock, on purpose: the server that checks the code uses it too. Every other
 * delay of the app runs on the boot clock.
 */
object Totp {
    const val PERIOD_SECONDS = 30

    /** What [code] shows when the secret decodes to nothing. */
    const val INVALID_CODE = "------"

    /** Above this, a pasted value is not even parsed (2.7.1, F12 v2.4.4). */
    const val MAX_URI_LENGTH = 2048

    private const val DIGITS = 6
    private const val MODULO = 1_000_000
    private const val OFFSET_MASK = 0x0F
    private const val BASE32_BITS = 5
    private const val BASE32_DIGITS_FROM = 26
    private const val MIN_SECRET_BYTES = 10
    private const val OTPAUTH = "otpauth://"

    enum class SecretError { EMPTY, INVALID_CHARACTERS, TOO_SHORT }

    /** The code for [epochSeconds], as "123 456". */
    fun code(secret: String, epochSeconds: Long): String {
        val key = decodeBase32(secret)
        if (key.isEmpty()) return INVALID_CODE
        val hash = try {
            Mac.getInstance("HmacSHA1").run {
                init(SecretKeySpec(key, "HmacSHA1"))
                doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(epochSeconds / PERIOD_SECONDS).array())
            }
        } finally {
            key.wipe()
        }
        // RFC 4226 §5.3, dynamic truncation: 31 bits read at the offset the last nibble gives.
        val offset = hash.last().toInt() and OFFSET_MASK
        val binary = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
        val digits = (binary % MODULO).toString().padStart(DIGITS, '0')
        return digits.substring(0, DIGITS / 2) + " " + digits.substring(DIGITS / 2)
    }

    /** Seconds before the next code, 1 to 30. */
    fun secondsRemaining(epochSeconds: Long): Int = PERIOD_SECONDS - Math.floorMod(epochSeconds, PERIOD_SECONDS.toLong()).toInt()

    /** `null` if [secret] is a usable Base32 secret, otherwise why not. */
    fun validate(secret: String): SecretError? {
        val cleaned = secret.uppercase().filterNot(Char::isWhitespace)
        return when {
            cleaned.isEmpty() -> SecretError.EMPTY
            cleaned.any { it !in 'A'..'Z' && it !in '2'..'7' && it != '=' } -> SecretError.INVALID_CHARACTERS
            else -> {
                val bytes = decodeBase32(secret)
                val size = bytes.size
                bytes.wipe()
                if (size < MIN_SECRET_BYTES) SecretError.TOO_SHORT else null
            }
        }
    }

    /**
     * The secret carried by a pasted `otpauth://totp/...?secret=` URI (what services print under their
     * QR code), or `null` if [pasted] is not such a URI or carries no usable secret. The URI must be a
     * TOTP one (host `totp`) and no longer than [MAX_URI_LENGTH] (2.7.1, F11 and F12 v2.4.4).
     */
    fun secretFromUri(pasted: String): String? {
        val raw = pasted.trim()
        val uri = raw.takeIf { it.length <= MAX_URI_LENGTH && it.startsWith(OTPAUTH, ignoreCase = true) }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("otpauth", ignoreCase = true) && it.host.equals("totp", ignoreCase = true) }
            ?: return null
        val secret = uri.rawQuery.orEmpty().split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "secret" }
            // Not the Charset overload: it needs API 33, and would throw on API 26 to 32 (lint NewApi).
            ?.let { URLDecoder.decode(it.second, "UTF-8") }
        return secret?.takeIf { validate(it) == null }
    }

    /** Base32 as 2.7.1 reads it: letters and 2-7 only, anything else skipped, trailing bits dropped. */
    private fun decodeBase32(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input.uppercase()) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in '2'..'7' -> c - '2' + BASE32_DIGITS_FROM
                else -> continue
            }
            buffer = (buffer shl BASE32_BITS) or value
            bits += BASE32_BITS
            if (bits >= Byte.SIZE_BITS) {
                bits -= Byte.SIZE_BITS
                // write() keeps the low 8 bits.
                out.write(buffer shr bits)
            }
        }
        return out.toByteArray()
    }
}
