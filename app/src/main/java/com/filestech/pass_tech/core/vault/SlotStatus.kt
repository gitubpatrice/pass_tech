package com.filestech.pass_tech.core.vault

/**
 * What the app may do with a slot WITHOUT opening it (design v2 §2).
 *
 * Only two states prove a slot free: its file is absent (nothing to lose), or its mark reads `free`.
 * Everything else is treated as occupied. In particular [Unknown], an existing file whose mark cannot
 * be read (Keystore key lost, damaged file), is never written, realigned or erased: losing the ability
 * to create a vault is recoverable, losing a vault is not.
 */
sealed interface SlotStatus {

    /** No file: provably free, since there is nothing in it. */
    data object Missing : SlotStatus

    data class Marked(val mark: OccupancyMark) : SlotStatus

    /** A file exists but its mark cannot be read. Treated as occupied, always. */
    data object Unknown : SlotStatus

    val provablyFree: Boolean
        get() = this is Missing || (this is Marked && !mark.occupied)

    val creationRequested: Boolean
        get() = this is Marked && mark.creationRequested
}
