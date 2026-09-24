package com.filestech.pass_tech.core.phishing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The accessibility service as the system sees it: listed or not, granted or not.
 *
 * Two different things, and 2.7.1 only had the second. **Listed** is the package manager's state of
 * the component, which this app sets: a disabled component does not appear under Settings ›
 * Accessibility at all, and its service cannot be started by anyone. **Granted** is the owner's
 * decision inside those settings, which no app may take for itself.
 */
interface PhishingComponent {

    /** `null` when the system did not answer. */
    fun listed(): Boolean?

    fun setListed(listed: Boolean): Boolean

    /** The owner granted it and the system is running it. `null` when the system did not answer. */
    fun granted(): Boolean?

    fun openSystemSettings(): Boolean
}

@Singleton
class AndroidPhishingComponent @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhishingComponent {

    /**
     * Built from the context and the class, never from strings. A build that adds a suffix to the
     * application id installs the package under one name while the service keeps the namespace's —
     * the mismatch that made the launcher disguise fail in silence (`AliasLauncherDisguise`). Here
     * the class is real, so the pair cannot drift apart.
     */
    private val component get() = ComponentName(context, PhishingDetectorService::class.java)

    override fun listed(): Boolean? =
        try {
            when (context.packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // Never overridden: the manifest has the say, and it must be READ. Answering "not
                // listed" here because the manifest is known to ship it disabled was right by luck,
                // not by measurement — a build that lost `android:enabled="false"` would have been
                // reported as unlisted while the system was listing it (found by putting that very
                // defect back, 2026-09-23).
                else ->
                    context.packageManager
                        .getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                        .isEnabled
            }
        } catch (_: Exception) {
            null
        }

    override fun setListed(listed: Boolean): Boolean =
        try {
            val state = if (listed) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            true
        } catch (_: Exception) {
            false
        }

    override fun granted(): Boolean? =
        try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo?.serviceInfo
                info?.packageName == context.packageName && info.name == PhishingDetectorService::class.java.name
            }
        } catch (_: Exception) {
            null
        }

    override fun openSystemSettings(): Boolean =
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
}
