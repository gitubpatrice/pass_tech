package com.filestech.pass_tech.core.integrity

import android.content.Context
import android.os.Build
import android.os.Debug
import com.filestech.pass_tech.core.state.StateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What this phone looks like, right now. */
fun interface DeviceIntegrity {
    fun issues(): Set<IntegrityIssue>
}

@Singleton
class AndroidDeviceIntegrity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debuggable: Boolean,
) : DeviceIntegrity {

    override fun issues(): Set<IntegrityIssue> = buildSet {
        if (runCatching { rooted() }.getOrDefault(false)) add(IntegrityIssue.ROOTED)
        // Read at the moment the owner is about to type their master password. A debugger attached
        // afterwards is not caught, and nothing here could catch it: this is a warning, not a guard.
        if (runCatching { Debug.isDebuggerConnected() }.getOrDefault(false)) add(IntegrityIssue.DEBUGGER_ATTACHED)
        if (debuggable) add(IntegrityIssue.DEBUGGABLE_BUILD)
        if (runCatching { emulator() }.getOrDefault(false)) add(IntegrityIssue.EMULATOR)
    }

    private fun rooted(): Boolean = IntegritySignals.rooted(
        exists = { File(it).exists() },
        installed = { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess },
    )

    private fun emulator(): Boolean = IntegritySignals.emulator(
        IntegritySignals.Description(
            fingerprint = Build.FINGERPRINT.orEmpty(),
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
        ),
    )
}

/**
 * Says what to warn about, and remembers what has already been said, so the same warning does not
 * greet the owner at every single unlock — only a phone that has CHANGED speaks up again.
 *
 * **What has already been said is kept in the encrypted state store**, not in the plain settings.
 * 2.7.1 kept it in SharedPreferences, which is a file a root process can write — so on the very
 * phones this warns about, the mark could be set in advance and the warning would never appear
 * again. Here forging it needs the Keystore key; deleting the file only brings the warning BACK,
 * which is the side to fail on.
 */
@Singleton
class IntegrityWarning @Inject constructor(
    private val device: DeviceIntegrity,
    private val state: StateStore,
) {

    /** Empty when there is nothing to say, or nothing new to say. */
    fun due(): Set<IntegrityIssue> {
        val issues = device.issues()
        // A fast path, not a guard: the line below would answer the same. It spares the healthy
        // phone — the ordinary case — a decryption of the state file at every single unlock. A
        // negative control showed it changes no verdict, which is exactly what a fast path should do.
        if (issues.isEmpty()) return emptySet()
        return if (mark(issues) == seen()) emptySet() else issues
    }

    /** The owner has read it: this exact situation stays quiet until it changes. */
    fun seen(issues: Set<IntegrityIssue>) {
        runCatching { state.update { JsonObject(it + (SEEN to JsonPrimitive(mark(issues)))) } }
    }

    private fun seen(): String? = runCatching { state.read()[SEEN]?.jsonPrimitive?.content }.getOrNull()

    /**
     * Built from the enum NAMES and never from what is on screen: a mark built on translated text
     * would change with the language, and the warning would come back although nothing about the
     * phone had moved (2.7.1 fixed exactly that in 2.7.0).
     */
    private fun mark(issues: Set<IntegrityIssue>): String = issues.map { it.name }.sorted().joinToString(",")

    private companion object {
        const val SEEN = "integrity.seen"
    }
}
