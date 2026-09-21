package com.filestech.pass_tech.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.ZoneId

class DartDateTimeTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            // The three shapes Dart's toIso8601String writes: local ms, local µs, UTC.
            "2026-09-21T19:46:12.345",
            "2026-09-21T19:47:00.000123",
            "2025-01-02T03:04:05.000Z",
            "2025-01-02T03:04:05.006007Z",
            "2024-02-29T23:59:59.999",
        ],
    )
    fun `what Dart writes reads back and writes again identically`(text: String) {
        assertThat(DartDateTime.parseOrNull(text)?.toIso8601String()).isEqualTo(text)
    }

    @ParameterizedTest
    @CsvSource(
        // input, what Dart's toIso8601String gives after DateTime.parse
        "2026-09-21, 2026-09-21T00:00:00.000",
        "20260921, 2026-09-21T00:00:00.000",
        "2026-09-21 19:46, 2026-09-21T19:46:00.000",
        "2026-09-21T194612, 2026-09-21T19:46:12.000",
        "'2026-09-21T19:46:12,5', 2026-09-21T19:46:12.500",
        "2026-09-21T19:46:12.1234567, 2026-09-21T19:46:12.123456",
        "2026-09-21T19:46:12z, 2026-09-21T19:46:12.000Z",
        "2026-09-21T19:46:12+02:00, 2026-09-21T17:46:12.000Z",
        "2026-09-21T19:46:12 -0530, 2026-09-22T01:16:12.000Z",
        "2026-09-21T00:30:00+01, 2026-09-20T23:30:00.000Z",
        "+2026-09-21T00:00:00, 2026-09-21T00:00:00.000",
        // Dart normalises out-of-range fields instead of refusing them.
        "2026-13-01, 2027-01-01T00:00:00.000",
        "2026-02-30, 2026-03-02T00:00:00.000",
    )
    fun `parses like Dart's DateTime parse`(input: String, expected: String) {
        assertThat(DartDateTime.parseOrNull(input)?.toIso8601String()).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "2026", "2026-9-21", "21/09/2026", "2026-09-21T1:00", "2026-09-21T19:46:12Zjunk",
            // Arabic-Indic digits: `\d` is ASCII-only in Dart, and must be here too.
            "٢٠٢٦-٠٩-٢١",
            // Java's `$` accepts a final line terminator, Dart's does not.
            "2026-09-21\n", "2026-09-21T19:46:12.345\r\n",
        ],
    )
    fun `refuses what Dart's DateTime parse refuses`(input: String) {
        assertThat(DartDateTime.parseOrNull(input)).isNull()
    }

    @Test
    fun `years beyond four digits are written as Dart writes them`() {
        assertThat(DartDateTime.of(LocalDateTime.of(12_345, 1, 1, 0, 0), isUtc = false).toIso8601String())
            .isEqualTo("+012345-01-01T00:00:00.000")
        assertThat(DartDateTime.of(LocalDateTime.of(-44, 3, 15, 12, 0), isUtc = true).toIso8601String())
            .isEqualTo("-0044-03-15T12:00:00.000Z")
    }

    @Test
    fun `a local time is ordered in the device zone, a UTC time as UTC`() {
        val paris = ZoneId.of("Europe/Paris")
        val local = DartDateTime.parseOrNull("2026-09-21T12:00:00.000")!!
        val utc = DartDateTime.parseOrNull("2026-09-21T11:00:00.000Z")!!
        // 12:00 in Paris (UTC+2 in September) is 10:00 UTC, one hour BEFORE 11:00 UTC.
        assertThat(local.toEpochMicros(paris)).isLessThan(utc.toEpochMicros(paris))
    }

    @Test
    fun `sub-microsecond precision is dropped, as in Dart`() {
        val value = DartDateTime.of(LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123_456_789), isUtc = false)
        assertThat(value.toIso8601String()).isEqualTo("2026-01-01T00:00:00.123456")
    }
}
