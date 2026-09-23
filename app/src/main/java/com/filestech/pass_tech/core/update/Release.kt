package com.filestech.pass_tech.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A published version, once its description has been read and found trustworthy. */
data class Release(
    /** Without the leading `v`, and always a strict `X.Y.Z`. */
    val version: String,
    val notes: String,
    /** Where the APK is, on GitHub and nowhere else. `null` when the release carries none. */
    val apkUrl: String?,
    /** What the notes say the APK hashes to, for the owner to check by hand. Nothing downloads itself. */
    val sha256: String?,
)

/**
 * Reading what GitHub answered, and refusing everything that does not look exactly right. The
 * account that publishes these releases could one day not be its owner, so nothing here is taken on
 * trust: not the tag, not the host an asset points at, not the shape of the answer.
 */
object ReleaseJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Strict `X.Y.Z`: no pre-release, no build metadata, as every Files Tech version is written. */
    private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")

    /** `SHA-256: <hex>`, the form 2.7.1 looked for. */
    private val LABELLED = Regex("""sha-?256\s*[:=]\s*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)

    /**
     * `<hex>  <name>.apk`, the form `sha256sum` prints and the form **every release of this project
     * actually uses**. 2.7.1 looked only for the labelled one, so its checksum line never appeared
     * on a single real release — a feature that reads as implemented and has never once fired.
     * Found by running the check against the live service (2026-09-23).
     */
    private val PER_FILE = Regex("""([0-9a-fA-F]{64})\s+(\S+\.apk)""", RegexOption.IGNORE_CASE)

    /** An asset may only be served from GitHub itself. A compromised release cannot point elsewhere. */
    private val ASSET_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    /** `null` when the answer is not a release this app is willing to describe. */
    fun parse(body: String): Release? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val tag = root.string("tag_name")?.removePrefix("v") ?: return null
        if (!SEMVER.matches(tag)) return null
        val notes = root.string("body").orEmpty()
        val apk = apkUrl(root)
        return Release(tag, notes, apk, sha256(notes, apk))
    }

    /**
     * The checksum of the file the owner is about to be pointed at — the per-file list is matched on
     * that file's NAME, so a release carrying four APKs does not hand out the wrong one.
     */
    private fun sha256(notes: String, apkUrl: String?): String? {
        val name = apkUrl?.substringAfterLast('/')
        val perFile = name?.let { file ->
            PER_FILE.findAll(notes).firstOrNull { it.groupValues[2].equals(file, ignoreCase = true) }?.groupValues?.get(1)
        }
        return (perFile ?: LABELLED.find(notes)?.groupValues?.get(1))?.lowercase()
    }

    private fun apkUrl(root: JsonObject): String? =
        (root["assets"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstNotNullOfOrNull { asset ->
                val name = asset.string("name") ?: return@firstNotNullOfOrNull null
                val url = asset.string("browser_download_url") ?: return@firstNotNullOfOrNull null
                if (!name.endsWith(".apk", ignoreCase = true)) return@firstNotNullOfOrNull null
                val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (parsed.scheme != "https" || parsed.host !in ASSET_HOSTS) null else url
            }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Comparing two `X.Y.Z`, and refusing to compare anything else. */
object Semver {

    /**
     * `false` whenever either side cannot be read. Offering an update in the dark risks offering a
     * DOWNGRADE, and that is what treating an unreadable part as zero did: a local `1.2.3-beta` had
     * its last part read as 0, so `1.2.2` looked newer than it (2.7.1 fixed this).
     *
     * This build's own name carries `-debug`, so a debug build is never offered anything — which is
     * the safe side of the same rule.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in r.indices) {
            if (r[i] != l[i]) return r[i] > l[i]
        }
        return false
    }

    /** Bounded: a part of several thousand digits, out of a hostile tag, costs to convert for nothing. */
    private fun parts(version: String): List<Int>? {
        val split = version.split('.')
        if (split.size != PARTS) return null
        val read = split.map { part -> part.takeIf { it.isNotEmpty() && it.length <= MAX_DIGITS }?.toIntOrNull() }
        return if (read.any { it == null || it < 0 }) null else read.filterNotNull()
    }

    private const val PARTS = 3
    private const val MAX_DIGITS = 9
}
