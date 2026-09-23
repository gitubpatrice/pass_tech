package com.filestech.pass_tech.core.net

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The addresses this app agrees to open, and the redirects it agrees to follow. These are the rules
 * that stand between a `302` and a body from anywhere.
 */
class HttpTargetTest {

    private val allowed = setOf("api.github.com", "objects.githubusercontent.com")

    @Test
    fun `only https, and only a listed host`() {
        assertThat(HttpTarget.accept("https://api.github.com/x", allowed)).isNotNull()
        assertThat(HttpTarget.accept("http://api.github.com/x", allowed)).isNull()
        assertThat(HttpTarget.accept("https://evil.example/x", allowed)).isNull()
        assertThat(HttpTarget.accept("ftp://api.github.com/x", allowed)).isNull()
        assertThat(HttpTarget.accept("not a url", allowed)).isNull()
        assertThat(HttpTarget.accept("", allowed)).isNull()
    }

    @Test
    fun `a host that merely ends with a listed one is another host`() {
        assertThat(HttpTarget.accept("https://api.github.com.evil.example/x", allowed)).isNull()
        assertThat(HttpTarget.accept("https://notapi.github.com/x", allowed)).isNull()
    }

    @Test
    fun `the credentials of an address do not decide its host`() {
        // `https://api.github.com@evil.example/` is a URL whose host is evil.example.
        assertThat(HttpTarget.accept("https://api.github.com@evil.example/x", allowed)).isNull()
    }

    private val here = URI("https://api.github.com/repos/x/y/releases/latest")

    @Test
    fun `a redirect within the allowed hosts is followed`() {
        assertThat(HttpTarget.follow(here, "https://objects.githubusercontent.com/z", allowed).toString())
            .isEqualTo("https://objects.githubusercontent.com/z")
        // Relative, as a server is free to answer.
        assertThat(HttpTarget.follow(here, "/other", allowed).toString()).isEqualTo("https://api.github.com/other")
    }

    @Test
    fun `a redirect off the allowed hosts, or off https, is refused`() {
        assertThat(HttpTarget.follow(here, "https://evil.example/z", allowed)).isNull()
        assertThat(HttpTarget.follow(here, "http://api.github.com/z", allowed)).isNull()
    }

    @Test
    fun `a redirect with no destination is refused`() {
        // An empty Location resolves to the address we are already on: five identical requests.
        assertThat(HttpTarget.follow(here, "", allowed)).isNull()
        assertThat(HttpTarget.follow(here, "   ", allowed)).isNull()
        assertThat(HttpTarget.follow(here, null, allowed)).isNull()
    }
}
