package com.filestech.pass_tech.core.integrity

/** What the app noticed about the phone it is running on. Stable names: they are written down. */
enum class IntegrityIssue {
    /** Something on this phone can become root. */
    ROOTED,

    /** A debugger is attached right now. */
    DEBUGGER_ATTACHED,

    /** This build is a debuggable one, so anything can read its memory. */
    DEBUGGABLE_BUILD,

    /** Not a phone: an emulator, where no key is held by real secure hardware. */
    EMULATOR,
}

/**
 * The rules themselves, away from Android so each one can be checked on its own.
 *
 * **All of this is best effort and says so.** Magisk hides from every one of these checks, and so
 * does anyone who cares. It is a warning to an owner who does not know what was done to their phone,
 * never a gate: the app works either way, because refusing to open would lock people out of their
 * own vault on their own phone.
 */
object IntegritySignals {

    /** Where an `su` binary sits when nobody is hiding it. */
    val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/system/xbin/mu",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/su/bin/su",
        "/su/bin/.ext/.su",
    )

    /** The managers that hand root out. Installed is enough: it is what they are for. */
    val ROOT_APPS = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.noshufou.android.su",
    )

    /**
     * **`Build.TAGS` is deliberately not read**, and 2.7.1 read it.
     *
     * `test-keys` is there on any build not signed with a vendor's release key, which includes every
     * unofficial community ROM — phones that are perfectly ordinary and very often not rooted at
     * all. It would tell those owners their phone is rooted when it is not, and a warning that is
     * wrong on ordinary devices is one the owner learns to dismiss without reading. What it catches
     * that the two lists below do not is narrow: a self-built ROM with root, no `su` at a usual
     * path, and no manager installed — someone who knows exactly what they did.
     */
    fun rooted(exists: (String) -> Boolean, installed: (String) -> Boolean): Boolean =
        SU_PATHS.any(exists) || ROOT_APPS.any(installed)

    /** What a phone says about itself, the fields any app may read. */
    data class Description(
        val fingerprint: String,
        val model: String,
        val manufacturer: String,
        val brand: String,
        val device: String,
        val product: String,
        val hardware: String,
    )

    /**
     * The virtual machines an Android image runs on. `ranchu` is today's emulator, `goldfish` the
     * one before it, `vbox86` Genymotion — a real phone names the chip it actually has.
     */
    private val VIRTUAL_HARDWARE = listOf("goldfish", "ranchu", "vbox86")

    /**
     * **2.7.1's rules did not fire on the emulator everyone actually uses.** Measured on an API 34
     * image (2026-09-23): fingerprint `google/sdk_gphone64_x86_64/emu64xa:14/…`, model
     * `sdk_gphone64_x86_64`, brand `google`, device `emu64xa` — not one of its patterns matches,
     * since they were written for `google_sdk` and `Android SDK built for x86`. The app announced
     * that it warns about emulators and stayed silent on one.
     *
     * Its rules are kept, for the older images they do catch, and two are added: the virtual machine
     * the image runs on, and the `sdk_` names the SDK's own images carry. A real phone is called
     * after its maker's model, never `sdk_anything`.
     */
    fun emulator(device: Description): Boolean = with(device) {
        VIRTUAL_HARDWARE.any { hardware.startsWith(it) } ||
            product.startsWith("sdk_") ||
            model.startsWith("sdk_") ||
            fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            manufacturer.contains("Genymotion") ||
            (brand.startsWith("generic") && this.device.startsWith("generic")) ||
            product == "google_sdk"
    }
}
