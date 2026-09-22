package com.filestech.pass_tech.core.clipboard

/** Where a value copied out of the vault goes. See [SecureClipboard]. */
interface SensitiveClipboard {
    /** Copies [text], marked sensitive. Returns the seconds before it is cleared, or `null` if it never is. */
    fun copy(text: String): Int?

    /** Clears the clipboard now, and drops the pending clearing. */
    fun clear()
}
