package com.filestech.pass_tech.core.legal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Which file a language opens, and what it opens when its own has not been written yet. */
class LegalDocumentTest {

    @Test
    fun `each document names its file the way the assets folder does`() {
        assertThat(LegalDocument.PRIVACY.asset("fr")).isEqualTo("legal/PRIVACY.fr.md")
        assertThat(LegalDocument.TERMS.asset("en")).isEqualTo("legal/TERMS.en.md")
    }

    @Test
    fun `a language with no document of its own reads the English one, in that order`() {
        assertThat(LegalDocument.languages("de")).containsExactly("de", "en").inOrder()
    }

    @Test
    fun `a language nobody has ever translated still has somewhere to go`() {
        assertThat(LegalDocument.languages("pt")).containsExactly("pt", "en").inOrder()
    }

    /** Not cosmetic: the screen says "not in your language yet" by comparing the two. */
    @Test
    fun `an English reader is not offered English twice`() {
        assertThat(LegalDocument.languages(LegalDocument.FALLBACK)).containsExactly("en")
    }
}
