package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import com.filestech.pass_tech.core.panic.LauncherDisguise

/** The launcher disguise in memory: what it was asked, and what happens when the system will not answer. */
class FakeLauncherDisguise(private var disguised: Boolean = false) : LauncherDisguise {

    /** When false, every call fails the way a package manager that refuses does. */
    var answers = true

    var sets = 0
        private set

    override fun disguised(): Boolean? = if (answers) disguised else null

    override fun set(disguised: Boolean): Boolean {
        sets++
        if (!answers) return false
        this.disguised = disguised
        return true
    }
}

/** The clipboard in memory: what is in it, and how many times it was emptied. */
class FakeClipboard(private val clearAfterSeconds: Int? = null) : SensitiveClipboard {

    var content: String? = null
        private set

    var clears = 0
        private set

    override fun copy(text: String): Int? {
        content = text
        return clearAfterSeconds
    }

    override fun clear() {
        clears++
        content = null
    }
}
