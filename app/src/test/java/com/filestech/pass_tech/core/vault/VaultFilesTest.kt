package com.filestech.pass_tech.core.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VaultFilesTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `writing one slot rewrites the others with the same bytes and a new date`() {
        val files = VaultFiles(dir)
        files.writeAll(Slot.entries.associateWith { "initial ${it.label}" })
        val longAgo = System.currentTimeMillis() - 86_400_000L
        Slot.entries.forEach { File(dir, it.vaultFileName).setLastModified(longAgo) }

        files.writeAll(mapOf(Slot.A to "changed a"))

        assertThat(files.read(Slot.A)).isEqualTo("changed a")
        assertThat(files.read(Slot.B)).isEqualTo("initial b")
        assertThat(files.read(Slot.C)).isEqualTo("initial c")
        // The point of the rewrite: no file keeps the old date, so no date designates the open slot.
        Slot.entries.forEach { assertThat(File(dir, it.vaultFileName).lastModified()).isGreaterThan(longAgo) }
    }

    @Test
    fun `no temporary file survives a write`() {
        VaultFiles(dir).writeAll(Slot.entries.associateWith { "x" })
        assertThat(dir.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
        assertThat(dir.list()!!.toList()).containsExactly("pt_vault_a.enc", "pt_vault_b.enc", "pt_vault_c.enc")
    }

    @Test
    fun `an absent slot is not created by a write that does not name it`() {
        val files = VaultFiles(dir)
        files.writeAll(mapOf(Slot.A to "a"))
        assertThat(files.exists(Slot.A)).isTrue()
        assertThat(files.exists(Slot.B)).isFalse()
        assertThat(files.read(Slot.B)).isNull()
    }
}
