package com.filestech.pass_tech.core.phishing

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accessibility service against a real package manager, which is the only place these two can be
 * checked.
 *
 * **That it ships disabled.** Nothing in the Kotlin says so — it is one attribute of the manifest,
 * and the app is listed under Settings › Accessibility, under the Pass Tech name, for as long as it
 * is enabled. A build that lost the attribute would look exactly the same everywhere else.
 *
 * **That the component it names exists.** The service is written `.core.phishing.PhishingDetectorService`
 * in the manifest, which the merger expands against the module's NAMESPACE, while `packageName`
 * returns the APPLICATION ID at run time — and this build is the one where the two differ, since the
 * debug build adds a suffix. `ComponentName(context, Class)` is what keeps them together; a component
 * built from strings would throw here, and 2.7.1 shipped exactly that mistake on its launcher aliases.
 *
 * It really enables this app's service, so it puts it back whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class PhishingComponentOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val component = AndroidPhishingComponent(context)

    /**
     * Back to DEFAULT and not to disabled: default is what a fresh install has, and the first
     * assertion below reads exactly that. Leaving it explicitly disabled would make every run after
     * the first one pass without looking at the manifest at all.
     */
    @After
    fun putTheShippingStateBack() {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, PhishingDetectorService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Test
    fun theServiceIsNotListedUntilTheOwnerAsksForIt() {
        // Never set means the manifest's own value, and that is what a fresh install reads.
        assertThat(
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, PhishingDetectorService::class.java),
            ),
        ).isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        assertThat(component.listed()).isFalse()

        assertThat(component.setListed(true)).isTrue()
        assertThat(component.listed()).isTrue()

        assertThat(component.setListed(false)).isTrue()
        assertThat(component.listed()).isFalse()
    }

    @Test
    fun theGrantIsTheSystemsToGive() {
        component.setListed(true)
        // No test can grant an accessibility service to itself, which is the whole point of the
        // setting. What is checked here is that asking is answered, and answered false.
        assertThat(component.granted()).isFalse()
    }
}
