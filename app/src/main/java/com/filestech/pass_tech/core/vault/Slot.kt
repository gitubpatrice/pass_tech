package com.filestech.pass_tech.core.vault

/**
 * A vault slot. Every slot exists on disk at all times once a first vault has been created: a slot
 * holding no vault carries a dummy that nobody can open. Nothing on disk, and nothing in the file
 * names, tells a used slot from a free one.
 *
 * The [label] is written into each file and bound into its authenticated data, so a file copied
 * from one slot to another refuses to open.
 */
enum class Slot(val label: String) {
    A("a"),
    B("b"),
    C("c"),
    ;

    val vaultFileName: String get() = "pt_vault_$label.enc"
    val keystoreAlias: String get() = "pt_kek_$label"

    companion object {
        fun fromLabel(label: String): Slot? = entries.firstOrNull { it.label == label }
    }
}
