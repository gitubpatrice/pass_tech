package com.filestech.pass_tech.core.update

import com.filestech.pass_tech.testing.Resources
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Reading a release, and refusing one. The account that publishes these could one day not be its
 * owner, so every field is held to its shape.
 */
class ReleaseJsonTest {

    private fun body(tag: String = "v3.1.0", notes: String = "", assets: String = "[]") =
        """{"tag_name":"$tag","body":"$notes","assets":$assets}"""

    @Test
    fun `the tag loses its v and must be a strict X_Y_Z`() {
        assertThat(ReleaseJson.parse(body("v3.1.0"))?.version).isEqualTo("3.1.0")
        assertThat(ReleaseJson.parse(body("3.1.0"))?.version).isEqualTo("3.1.0")
        for (refused in listOf("3.1", "3.1.0.1", "v3.1.0-rc1", "v3.1.0+4", "latest", "")) {
            assertThat(ReleaseJson.parse(body(refused))).isNull()
        }
    }

    @Test
    fun `anything that is not a release is refused rather than guessed at`() {
        for (refused in listOf("", "not json", "[]", "<html>captive portal</html>", """{"body":"x"}""", """{"tag_name":3}""")) {
            assertThat(ReleaseJson.parse(refused)).isNull()
        }
    }

    @Test
    fun `an APK is taken only from GitHub itself`() {
        val github =
            """[{"name":"a.apk","browser_download_url":"https://github.com/gitubpatrice/pass_tech/releases/download/v3.1.0/a.apk"}]"""
        assertThat(ReleaseJson.parse(body(assets = github))?.apkUrl).endsWith("a.apk")

        val objects = """[{"name":"a.apk","browser_download_url":"https://objects.githubusercontent.com/x/a.apk"}]"""
        assertThat(ReleaseJson.parse(body(assets = objects))?.apkUrl).isNotNull()

        // A release published by someone who should not be publishing it cannot point elsewhere.
        val elsewhere = """[{"name":"a.apk","browser_download_url":"https://evil.example/a.apk"}]"""
        assertThat(ReleaseJson.parse(body(assets = elsewhere))?.apkUrl).isNull()

        val cleartext = """[{"name":"a.apk","browser_download_url":"http://github.com/a.apk"}]"""
        assertThat(ReleaseJson.parse(body(assets = cleartext))?.apkUrl).isNull()

        val notAnApk = """[{"name":"notes.txt","browser_download_url":"https://github.com/a.txt"}]"""
        assertThat(ReleaseJson.parse(body(assets = notAnApk))?.apkUrl).isNull()
    }

    @Test
    fun `the checksum is picked out of the notes, however it is written`() {
        val hash = "a".repeat(64)
        for (written in listOf("SHA-256: $hash", "sha256=$hash", "SHA256:  $hash")) {
            assertThat(ReleaseJson.parse(body(notes = written))?.sha256).isEqualTo(hash)
        }
        assertThat(ReleaseJson.parse(body(notes = "SHA-256: ${"a".repeat(63)}"))?.sha256).isNull()
        assertThat(ReleaseJson.parse(body())?.sha256).isNull()
    }

    @Test
    fun `the checksum of the very file the owner is pointed at`() {
        val mine = "a".repeat(64)
        val other = "b".repeat(64)
        val notes = "$other  pass-tech-universel-3.1.0.apk\n$mine  a.apk"
        val assets = """[{"name":"a.apk","browser_download_url":"https://github.com/x/a.apk"}]"""
        // Four APKs are published at each release: matching on the file NAME is what keeps the right
        // checksum with the right file.
        assertThat(ReleaseJson.parse(body(notes = notes, assets = assets))?.sha256).isEqualTo(mine)
    }

    @Test
    fun `the real answer of the real service is read`() {
        // `gh api repos/gitubpatrice/pass_tech/releases/latest`, taken on 2026-09-23. The shape of
        // the answer is the service's, not ours: a test written only against a made-up body proves
        // nothing about the day GitHub changes a field.
        val real = ReleaseJson.parse(Resources.text("github/releases-latest.json"))
        assertThat(real?.version).isEqualTo("2.7.1")
        assertThat(real?.apkUrl).startsWith("https://github.com/gitubpatrice/pass_tech/releases/download/")
        assertThat(real?.apkUrl).endsWith(".apk")
        assertThat(real?.notes).isNotEmpty()
        // Its notes DO publish the four checksums, as `sha256sum` prints them — which 2.7.1's
        // pattern could not read, so its checksum line never once appeared on a real release.
        assertThat(real?.sha256).isEqualTo("52c8f4a53e75dc2918466d3ea67f0459ed7828472b9dfed08e8a41c34b8a02d3")
    }

    @Test
    fun `a version nobody can read is never newer than anything`() {
        assertThat(Semver.isNewer("3.1.0", "3.0.0")).isTrue()
        assertThat(Semver.isNewer("3.0.1", "3.0.0")).isTrue()
        assertThat(Semver.isNewer("4.0.0", "3.9.9")).isTrue()
        assertThat(Semver.isNewer("3.0.0", "3.0.0")).isFalse()
        assertThat(Semver.isNewer("2.9.9", "3.0.0")).isFalse()
        // Reading a missing part as zero made `1.2.2` look newer than `1.2.3-beta`: a DOWNGRADE
        // offered as an update (2.7.1 fixed this).
        assertThat(Semver.isNewer("1.2.2", "1.2.3-beta")).isFalse()
        assertThat(Semver.isNewer("1.2.3-beta", "1.2.2")).isFalse()
        assertThat(Semver.isNewer("1.2.3", "1.2")).isFalse()
        assertThat(Semver.isNewer("9".repeat(10) + ".0.0", "1.0.0")).isFalse()
    }
}
