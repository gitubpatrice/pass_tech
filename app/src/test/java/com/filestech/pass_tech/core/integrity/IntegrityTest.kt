package com.filestech.pass_tech.core.integrity

import com.filestech.pass_tech.core.state.StateStore
import com.filestech.pass_tech.testing.InMemorySlotKeystore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The rules on their own: what counts as root, what counts as an emulator, and what does not. */
class IntegritySignalsTest {

    private val nothing: (String) -> Boolean = { false }

    @Test
    fun `an su binary at any of the usual paths`() {
        for (path in IntegritySignals.SU_PATHS) {
            assertThat(IntegritySignals.rooted(exists = { it == path }, installed = nothing)).isTrue()
        }
        assertThat(IntegritySignals.rooted(exists = nothing, installed = nothing)).isFalse()
    }

    @Test
    fun `a root manager installed is enough, since that is what it is for`() {
        for (app in IntegritySignals.ROOT_APPS) {
            assertThat(IntegritySignals.rooted(exists = nothing, installed = { it == app })).isTrue()
        }
    }

    @Test
    fun `an ordinary phone built with test keys is not called rooted`() {
        // 2.7.1 read `Build.TAGS` and answered "rooted" on every unofficial community ROM, rooted
        // or not. A warning that is wrong on ordinary phones is one its owner learns to ignore.
        // Nothing about the build reaches this rule: it is handed a file test and an app test, and
        // that is all it can ask. A community ROM with neither an su binary nor a manager is a
        // phone like any other here.
        assertThat(IntegritySignals.rooted(exists = nothing, installed = nothing)).isFalse()
        // And the list it is handed really is used, so this is not a check that passes by doing nothing.
        assertThat(IntegritySignals.rooted(exists = { it == "/sbin/su" }, installed = nothing)).isTrue()
    }

    @Test
    fun `the emulator everyone actually uses`() {
        // Measured on the API 34 image, 2026-09-23. NOT ONE of 2.7.1's patterns matches it: the app
        // said it warns about emulators and said nothing on one.
        assertThat(
            IntegritySignals.emulator(
                IntegritySignals.Description(
                    fingerprint = "google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys",
                    model = "sdk_gphone64_x86_64",
                    manufacturer = "Google",
                    brand = "google",
                    device = "emu64xa",
                    product = "sdk_gphone64_x86_64",
                    hardware = "ranchu",
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `the older images 2-7-1 did catch`() {
        assertThat(emulator(fingerprint = "generic/sdk/generic:14")).isTrue()
        assertThat(emulator(fingerprint = "unknown")).isTrue()
        assertThat(emulator(model = "Android SDK built for x86")).isTrue()
        assertThat(emulator(model = "sdk_gphone64 Emulator")).isTrue()
        assertThat(emulator(manufacturer = "Genymotion")).isTrue()
        assertThat(emulator(brand = "generic", device = "generic")).isTrue()
        assertThat(emulator(product = "google_sdk")).isTrue()
        assertThat(emulator(hardware = "goldfish")).isTrue()
        assertThat(emulator(hardware = "vbox86p")).isTrue()
    }

    @Test
    fun `a real phone does not`() {
        // The two this project is tested on. A phone is named after its maker's model, and its
        // hardware is the chip it really has.
        assertThat(
            IntegritySignals.emulator(
                IntegritySignals.Description(
                    fingerprint = "samsung/star2ltexx/star2lte:10/QP1A.190711.020/G965FXXUHFVF1:user/release-keys",
                    model = "SM-G965F",
                    manufacturer = "samsung",
                    brand = "samsung",
                    device = "star2lte",
                    product = "star2ltexx",
                    hardware = "exynos9810",
                ),
            ),
        ).isFalse()
        assertThat(
            IntegritySignals.emulator(
                IntegritySignals.Description(
                    fingerprint = "samsung/e1qxxx/e1q:16/BP2A.250705.008/S921BXXS6DYF6:user/release-keys",
                    model = "SM-S921B",
                    manufacturer = "samsung",
                    brand = "samsung",
                    device = "e1q",
                    product = "e1qxxx",
                    hardware = "qcom",
                ),
            ),
        ).isFalse()
    }

    @Suppress("LongParameterList")
    private fun emulator(
        fingerprint: String = "samsung/x",
        model: String = "SM-G965F",
        manufacturer: String = "samsung",
        brand: String = "samsung",
        device: String = "star2lte",
        product: String = "star2ltexx",
        hardware: String = "exynos9810",
    ) = IntegritySignals.emulator(
        IntegritySignals.Description(fingerprint, model, manufacturer, brand, device, product, hardware),
    )
}

/** Saying it once, and saying it again only when the phone has changed. */
class IntegrityWarningTest {

    @TempDir
    lateinit var dir: File

    private val device = FakeIntegrity()
    private lateinit var state: StateStore
    private lateinit var warning: IntegrityWarning

    private class FakeIntegrity(var current: Set<IntegrityIssue> = emptySet()) : DeviceIntegrity {
        override fun issues(): Set<IntegrityIssue> = current
    }

    private fun build() {
        state = StateStore(File(dir, StateStore.FILE_NAME), InMemorySlotKeystore())
        warning = IntegrityWarning(device, state)
    }

    @Test
    fun `a healthy phone says nothing`() {
        build()
        assertThat(warning.due()).isEmpty()
    }

    @Test
    fun `said once, then quiet`() {
        build()
        device.current = setOf(IntegrityIssue.ROOTED)
        assertThat(warning.due()).containsExactly(IntegrityIssue.ROOTED)
        warning.seen(warning.due())
        assertThat(warning.due()).isEmpty()
    }

    @Test
    fun `a phone that changes speaks again`() {
        build()
        device.current = setOf(IntegrityIssue.EMULATOR)
        warning.seen(warning.due())
        assertThat(warning.due()).isEmpty()

        // Rooted after the fact is exactly the case worth hearing about.
        device.current = setOf(IntegrityIssue.EMULATOR, IntegrityIssue.ROOTED)
        assertThat(warning.due()).containsExactly(IntegrityIssue.EMULATOR, IntegrityIssue.ROOTED)
    }

    @Test
    fun `the same situation in another order is the same situation`() {
        build()
        device.current = setOf(IntegrityIssue.ROOTED, IntegrityIssue.EMULATOR)
        warning.seen(warning.due())
        device.current = setOf(IntegrityIssue.EMULATOR, IntegrityIssue.ROOTED)
        assertThat(warning.due()).isEmpty()
    }

    @Test
    fun `what was said is kept where root cannot forge it`() {
        build()
        device.current = setOf(IntegrityIssue.ROOTED)
        warning.seen(warning.due())
        // The mark is in the encrypted state file, not in the plain settings: 2.7.1 kept it in
        // SharedPreferences, which a root process — the very thing being warned about — can write.
        val onDisk = File(dir, StateStore.FILE_NAME).readBytes().decodeToString()
        assertThat(onDisk).doesNotContain("ROOTED")
        assertThat(onDisk).doesNotContain("integrity")
    }

    @Test
    fun `a state file that was wiped brings the warning back`() {
        build()
        device.current = setOf(IntegrityIssue.ROOTED)
        warning.seen(warning.due())
        File(dir, StateStore.FILE_NAME).delete()
        // Deleting it only makes the app talk again, which is the side to fail on.
        assertThat(warning.due()).containsExactly(IntegrityIssue.ROOTED)
    }
}
