package com.filestech.pass_tech.core.integrity

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the integrity signals answer on a real phone, where the fake predicates of the JVM tests
 * cannot follow.
 *
 * Two things are measured here, and neither can be measured anywhere else:
 *
 * - **the debuggable flag is read from the running app**, not from a constant frozen when the APK
 *   was built. `BuildConfig.DEBUG` is `false` in every published build, so the warning could never
 *   appear on anyone's phone — and it is blind to the case it exists for, an APK repackaged and
 *   re-signed by someone else with the flag turned on, which carries the original `BuildConfig`;
 * - **the five root managers are packages this app may see.** From Android 11 an app sees no package
 *   it has not named in the manifest, so the lookup answered "not installed" for all five whatever
 *   was on the phone.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrityOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * This build IS debuggable — a test only ever runs against one — so the flag must be on. Read
     * from `ApplicationInfo`, this is the phone's own answer about the process that is running.
     */
    @Test
    fun theDebuggableFlagIsReadFromTheRunningApp() {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        assertThat(debuggable).isTrue()

        val integrity = AndroidDeviceIntegrity(context, debuggable)
        assertThat(integrity.issues()).contains(IntegrityIssue.DEBUGGABLE_BUILD)
    }

    /**
     * Asking about the five costs nothing and never throws out of [AndroidDeviceIntegrity] — the
     * lookup is wrapped — and on a phone with none of them installed the honest answer is "no".
     *
     * **What this cannot prove, and no test on this phone can**: that the manifest declaration is
     * what makes the answer meaningful. Without it the platform refuses to answer; with it the
     * answer is a plain "not installed"; and from inside the app the two look identical as long as
     * none of the five is really there. `canPackageQuery` does not help — it throws when the package
     * it is asked about does not exist. The declaration itself is checked by `RootAppVisibilityTest`
     * against the manifest; measuring its effect needs a phone with one of the five installed.
     */
    @Test
    fun theRootLookupAnswersWithoutThrowing() {
        val integrity = AndroidDeviceIntegrity(context, debuggable = false)
        assertThat(integrity.issues()).doesNotContain(IntegrityIssue.DEBUGGABLE_BUILD)
    }
}
