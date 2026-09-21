package com.filestech.pass_tech.core.model

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * A timestamp exactly as the Flutter app stores it: a Dart `DateTime` serialised with
 * `toIso8601String`, read back with `DateTime.parse`.
 *
 * Why not a plain `Instant`: Dart keeps local times WITHOUT their zone (`2026-09-21T19:46:12.345`)
 * and UTC times with a `Z`. The Flutter app displays a UTC value in UTC. Converting everything to
 * instants would make a backup written by this app show shifted dates once opened in 2.7.1: the way
 * back must be exact, so the two shapes are kept as they are.
 *
 * Precision is the microsecond, like Dart.
 */
class DartDateTime private constructor(
    /** Wall-clock fields: local time if [isUtc] is false, UTC otherwise. */
    val fields: LocalDateTime,
    val isUtc: Boolean,
) : Comparable<DartDateTime> {

    /** The instant this timestamp designates. A local time is read in [zone], as Dart does. */
    fun toEpochMicros(zone: ZoneId = ZoneId.systemDefault()): Long {
        val instant = if (isUtc) fields.toInstant(ZoneOffset.UTC) else fields.atZone(zone).toInstant()
        return Math.addExact(Math.multiplyExact(instant.epochSecond, MICROS_PER_SECOND), instant.nano / NANOS_PER_MICRO.toLong())
    }

    /** Byte-for-byte what Dart's `DateTime.toIso8601String` writes. */
    fun toIso8601String(): String {
        val year = fields.year
        val y = if (year in -MAX_FOUR_DIGIT_YEAR..MAX_FOUR_DIGIT_YEAR) fourDigits(year) else sixDigits(year)
        val micros = fields.nano / NANOS_PER_MICRO
        val ms = micros / MICROS_PER_MILLI
        val us = micros % MICROS_PER_MILLI
        return buildString {
            append(y).append('-').append(twoDigits(fields.monthValue)).append('-').append(twoDigits(fields.dayOfMonth))
            append('T').append(twoDigits(fields.hour)).append(':').append(twoDigits(fields.minute))
            append(':').append(twoDigits(fields.second)).append('.').append(threeDigits(ms))
            if (us != 0) append(threeDigits(us))
            if (isUtc) append('Z')
        }
    }

    override fun compareTo(other: DartDateTime): Int = toEpochMicros().compareTo(other.toEpochMicros())

    override fun equals(other: Any?): Boolean =
        other is DartDateTime && other.fields == fields && other.isUtc == isUtc

    override fun hashCode(): Int = 31 * fields.hashCode() + isUtc.hashCode()

    override fun toString(): String = toIso8601String()

    companion object {
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000
        private const val NANOS_PER_MICRO = 1_000
        private const val FRACTION_DIGITS = 6
        private const val MAX_FOUR_DIGIT_YEAR = 9_999
        private const val SIX_DIGIT_THRESHOLD = 100_000
        private const val MINUTES_PER_HOUR = 60L
        private const val DECIMAL_BASE = 10

        // Capture groups of PARSE_FORMAT.
        private const val GROUP_YEAR = 1
        private const val GROUP_MONTH = 2
        private const val GROUP_DAY = 3
        private const val GROUP_HOUR = 4
        private const val GROUP_MINUTE = 5
        private const val GROUP_SECOND = 6
        private const val GROUP_FRACTION = 7
        private const val GROUP_ZONE = 8
        private const val GROUP_OFFSET_SIGN = 9
        private const val GROUP_OFFSET_HOURS = 10
        private const val GROUP_OFFSET_MINUTES = 11

        // Dart's own limit: ±8.64e15 ms around the epoch, beyond which `DateTime.parse` throws.
        private const val MAX_EPOCH_MILLIS = 8_640_000_000_000_000L

        /** The grammar of Dart's `DateTime.parse` (sdk/lib/core/date_time.dart), verbatim. */
        private val PARSE_FORMAT = Regex(
            "^([+-]?\\d{4,6})-?(\\d\\d)-?(\\d\\d)" +
                "(?:[ T](\\d\\d)(?::?(\\d\\d)(?::?(\\d\\d)(?:[.,](\\d+))?)?)?" +
                "( ?[zZ]| ?([-+])(\\d\\d)(?::?(\\d\\d))?)?)?$",
        )

        fun of(fields: LocalDateTime, isUtc: Boolean): DartDateTime =
            DartDateTime(fields.withNano(fields.nano / NANOS_PER_MICRO * NANOS_PER_MICRO), isUtc)

        /** Local time now, the way the Flutter app stamps `createdAt` and `updatedAt`. */
        fun nowLocal(): DartDateTime = of(LocalDateTime.now(), isUtc = false)

        /**
         * Dart's `DateTime.parse`, `null` where Dart throws a FormatException.
         *
         * Like Dart, out-of-range fields are NORMALISED rather than refused (month 13 is January of
         * the next year), and an explicit offset turns the value into UTC.
         *
         * `matchEntire`, not `find`: in Java, `$` also matches before a final line terminator, so a
         * date followed by a newline would pass. Dart's `$` does not accept it.
         */
        fun parseOrNull(text: String): DartDateTime? {
            val groups = PARSE_FORMAT.matchEntire(text)?.groupValues ?: return null
            val isUtc = groups[GROUP_ZONE].isNotEmpty()
            return normalisedFieldsOrNull(groups)
                ?.takeIf { it.toInstant(ZoneOffset.UTC).toEpochMilli() in -MAX_EPOCH_MILLIS..MAX_EPOCH_MILLIS }
                ?.let { DartDateTime(it, isUtc) }
        }

        private fun normalisedFieldsOrNull(groups: List<String>): LocalDateTime? {
            fun number(group: Int): Long = groups[group].ifEmpty { "0" }.toLong()

            val year = groups[GROUP_YEAR].toIntOrNull() ?: return null
            var minute = number(GROUP_MINUTE)
            val offsetSign = groups[GROUP_OFFSET_SIGN]
            if (offsetSign.isNotEmpty()) {
                val offset = number(GROUP_OFFSET_HOURS) * MINUTES_PER_HOUR + number(GROUP_OFFSET_MINUTES)
                minute -= if (offsetSign == "-") -offset else offset
            }
            val micros = groups[GROUP_FRACTION].ifEmpty { null }?.let(::fractionToMicros) ?: 0L
            return try {
                LocalDateTime.of(year, 1, 1, 0, 0)
                    .plusMonths(number(GROUP_MONTH) - 1)
                    .plusDays(number(GROUP_DAY) - 1)
                    .plusHours(number(GROUP_HOUR))
                    .plusMinutes(minute)
                    .plusSeconds(number(GROUP_SECOND))
                    .plusNanos(micros * NANOS_PER_MICRO)
            } catch (_: DateTimeException) {
                null
            }
        }

        /** Dart keeps the first six fraction digits and drops the rest, without rounding. */
        private fun fractionToMicros(digits: String): Long {
            var result = 0L
            for (i in 0 until FRACTION_DIGITS) {
                result = result * DECIMAL_BASE + (if (i < digits.length) digits[i] - '0' else 0)
            }
            return result
        }

        // Locale.ROOT: a device set to Arabic or Persian would otherwise write non-ASCII digits.
        private fun fourDigits(n: Int): String = (if (n < 0) "-" else "") + String.format(Locale.ROOT, "%04d", abs(n))

        private fun sixDigits(n: Int): String {
            val sign = if (n < 0) "-" else "+"
            return if (abs(n) >= SIX_DIGIT_THRESHOLD) "$sign${abs(n)}" else "${sign}0${abs(n)}"
        }

        private fun threeDigits(n: Int): String = String.format(Locale.ROOT, "%03d", n)

        private fun twoDigits(n: Int): String = String.format(Locale.ROOT, "%02d", n)
    }
}
