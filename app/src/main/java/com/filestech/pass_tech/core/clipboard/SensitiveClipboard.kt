package com.filestech.pass_tech.core.clipboard

/** Where a value copied out of the vault goes. See [SecureClipboard]. */
interface SensitiveClipboard {
    /** Copies [text], marked sensitive. Returns the seconds before it is cleared, or `null` if it never is. */
    fun copy(text: String): Int?

    /** Clears the clipboard now, and drops the pending clearing. Absolute: the panic path uses it. */
    fun clear()

    /** The vault closed. Clears unless the owner turned clearing off. */
    fun clearOnLock()

    /** Once per process: clears what a previous process of THIS app left behind and never cleared. */
    fun clearIfLeftBehind()
}
