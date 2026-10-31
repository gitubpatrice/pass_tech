package com.filestech.pass_tech.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import com.filestech.pass_tech.core.clipboard.SensitiveClipboard
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The selection toolbar's Copy must reach the vault's clipboard — marked sensitive, cleared — and never the
 * platform's one directly. See [SelectionClipboard].
 */
class SelectionClipboardTest {

    private val platform = mockk<Clipboard>(relaxed = true)
    private val vault = mockk<SensitiveClipboard>(relaxed = true)
    private val clipboard = SelectionClipboard(platform, vault)

    @Test
    fun aCopiedTextGoesToTheVaultClipboardOnly() = runTest {
        clipboard.setClipEntry(entryOf("the secure note"))

        verify(exactly = 1) { vault.copy("the secure note") }
        coVerify(exactly = 0) { platform.setClipEntry(any()) }
    }

    /** The control: what carries no text is left to the platform, as before. */
    @Test
    fun anEmptyClipStillGoesToThePlatform() = runTest {
        clipboard.setClipEntry(null)

        coVerify(exactly = 1) { platform.setClipEntry(null) }
        verify(exactly = 0) { vault.copy(any()) }
    }

    private fun entryOf(text: String): ClipEntry {
        val item = mockk<ClipData.Item> { every { this@mockk.text } returns text }
        val data = mockk<ClipData> {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
        }
        return mockk { every { clipData } returns data }
    }
}
