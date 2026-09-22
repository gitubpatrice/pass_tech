package com.filestech.pass_tech.core.panic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.filestech.pass_tech.PassTechApplication
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the launcher draws for this app: Pass Tech, or a calculator.
 *
 * Two `activity-alias` entries carry the MAIN/LAUNCHER filter, one enabled at a time. The disguise is
 * the launcher's business only: `android:label` and `android:icon` of `<application>` cannot be
 * changed while the app runs, so Settings › Apps, the share sheet and the permission manager keep
 * saying Pass Tech. Stated in THREAT_MODEL: this hides the app from a glance at a home screen, not
 * from someone who opens the settings.
 */
interface LauncherDisguise {

    /**
     * `true` disguised, `false` not, **`null` when the system did not answer**. The uncertainty is
     * returned rather than swallowed: 2.7.1 answered "not disguised" on any error, and the callers
     * that meant to stay on the safe side — the update check, which must not reach the network while
     * the disguise is on — never saw the failure they had written their fallback for.
     */
    fun disguised(): Boolean?

    /** `false` if nothing changed. */
    fun set(disguised: Boolean): Boolean
}

@Singleton
class AliasLauncherDisguise @Inject constructor(@ApplicationContext private val context: Context) : LauncherDisguise {

    override fun disguised(): Boolean? =
        try {
            context.packageManager.getComponentEnabledSetting(alias(DECOY)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) {
            null
        }

    override fun set(disguised: Boolean): Boolean =
        try {
            enable(alias(if (disguised) DECOY else NORMAL), true)
            enable(alias(if (disguised) NORMAL else DECOY), false)
            true
        } catch (_: Exception) {
            false
        }

    private fun enable(component: ComponentName, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    /**
     * The package the app is INSTALLED under, and the fully qualified name of the alias, which are
     * not the same string as soon as a build adds a suffix to the application id.
     *
     * An `activity-alias` written `.MainAliasNormal` is expanded by the manifest merger against the
     * module's **namespace**, never against the application id, while `packageName` returns the
     * application id at runtime. Building the component from `packageName` alone worked only as long
     * as the two happened to be equal: 2.7.1 hit this the day it added `.debug` to its debug build,
     * and `setComponentEnabledSetting` threw on a component that did not exist — so the disguise
     * failed silently on the only builds where anyone could test it. Taken from a class that lives in
     * the namespace by construction, so renaming the package moves both together.
     */
    private fun alias(name: String) = ComponentName(context.packageName, "$NAMESPACE.$name")

    private companion object {
        const val NORMAL = "MainAliasNormal"
        const val DECOY = "MainAliasDecoy"
        val NAMESPACE: String = PassTechApplication::class.java.name.substringBeforeLast('.')
    }
}
