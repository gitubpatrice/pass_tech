package com.filestech.pass_tech.core.panic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The panic disguise against a real package manager, which is the only place it can be tested.
 *
 * It exists for one defect a JVM test cannot see: an `activity-alias` written `.MainAliasNormal` is
 * expanded by the manifest merger against the module's NAMESPACE, while `packageName` returns the
 * APPLICATION ID at runtime. Building the component from `packageName` works as long as the two are
 * equal — and this build is exactly the one where they are not, since the debug build adds a suffix.
 * 2.7.1 shipped that mistake: `setComponentEnabledSetting` threw on a component that did not exist,
 * the throw was swallowed, and the disguise failed silently on every build anyone could test on.
 *
 * It really switches this app's launcher entry, so it puts it back whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDisguiseOnDeviceTest {

    private val disguise = AliasLauncherDisguise(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun showTheAppAgain() {
        disguise.set(false)
    }

    @Test
    fun theLauncherEntrySwitchesBothWaysAndSaysWhichItIs() {
        // Whatever the app was left in, the starting point is stated, not assumed.
        assertThat(disguise.set(false)).isTrue()
        assertThat(disguise.disguised()).isFalse()

        assertThat(disguise.set(true)).isTrue()
        assertThat(disguise.disguised()).isTrue()

        assertThat(disguise.set(false)).isTrue()
        assertThat(disguise.disguised()).isFalse()
    }
}
