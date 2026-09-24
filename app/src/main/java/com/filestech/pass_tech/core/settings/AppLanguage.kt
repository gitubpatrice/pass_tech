package com.filestech.pass_tech.core.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.util.Locale

/**
 * The language the app shows, when its owner picks one instead of following the phone.
 *
 * **Why this exists at all, when Android 13 already has it.** From Android 13, a per-app language
 * sits under Settings › Apps, fed by `res/xml/locales_config.xml`, and this object simply reads and
 * writes that. Below 13 there is no such thing — and that is not a rare old phone: it is Patrice's
 * S9 on Android 10, and it is every phone the 2.7.1 release still runs on, which had a picker of its
 * own. Dropping it would have been a feature removed in a rewrite, silently.
 *
 * **One store per Android version, never two.** On 13 and above the system's own setting is the only
 * store: a choice made here shows up in Android's picker, and one made there shows up here, because
 * there is nothing else to disagree with. Below 13 the choice is a preference of ours, applied by
 * [wrap] when an activity is built. Keeping our own store on 13 too would have made Android's picker
 * look broken — it would set a locale that ours would override at the next launch.
 *
 * **The disguise is deliberately left out.** [wrap] is applied to this app's own windows, not to the
 * calculator the panic mode shows: a calculator in a language the phone is not set to would be the
 * kind of detail that gives a disguise away.
 */
object AppLanguage {

    /** "Follow the phone". Not a language code, so it can never be mistaken for one. */
    const val SYSTEM = "system"

    /**
     * The languages this build carries, in the order the picker offers them.
     *
     * This is the **fourth** place a language is written down — after `values-<code>/`, the build's
     * `androidResources.localeFilters`, and `res/xml/locales_config.xml` — and none of the four can
     * see the others. `StringsParityTest` holds all four to the same set, because every way of
     * getting it wrong is silent: a code listed here with no `values-` directory offers a language
     * that then shows English, and a `values-` directory missing from `localeFilters` is stripped
     * out of the APK at build time without a word.
     */
    val CODES = listOf("en", "fr", "de", "it", "es")

    /** [SYSTEM], or one of [CODES]. */
    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) fromSystem(context) else stored(context)

    /**
     * Below Android 13 the caller must recreate itself afterwards; above, the system does it. That
     * asymmetry is the system's, not ours, and [needsRecreate] is where it is written down once.
     */
    fun choose(context: Context, code: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) toSystem(context, code) else store(context, code)
    }

    /** Whether the caller has to rebuild its windows for a new choice to show. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Applies the stored choice to a base context, from `attachBaseContext`. A no-op on Android 13
     * and above, where the choice *is* the system's and the system has already applied it.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val code = stored(base).takeIf { it != SYSTEM } ?: return base
        val locale = Locale.forLanguageTag(code)
        // Everything on screen reads its language from the configuration; the passphrase generator
        // reads the process default instead, because the list it draws words from is not a resource.
        // Android 13 moves that default itself when it applies a per-app language. Below it nobody
        // does, so it is moved here — otherwise one choice would give an Italian screen with French
        // words on an old phone and Italian words on a new one.
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun fromSystem(context: Context): String =
        context.getSystemService(LocaleManager::class.java)
            ?.applicationLocales
            ?.takeUnless { it.isEmpty }
            ?.get(0)
            ?.language
            ?.takeIf { it in CODES }
            ?: SYSTEM

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun toSystem(context: Context, code: String) {
        val locales = if (code == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
    }

    /** A code this build no longer carries reads as [SYSTEM], never as a language nobody can show. */
    private fun stored(context: Context): String =
        preferences(context).getString(KEY, SYSTEM)?.takeIf { it in CODES } ?: SYSTEM

    private fun store(context: Context, code: String) {
        preferences(context).edit { putString(KEY, code) }
    }

    /**
     * Its own file, and not the DataStore the other settings live in: it is read from
     * `attachBaseContext`, before anything is injected and on the thread that draws, where a
     * DataStore read would have to be blocked on. One value, read synchronously, is what that
     * moment allows.
     */
    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "pt_language"
    private const val KEY = "app_language"
}
