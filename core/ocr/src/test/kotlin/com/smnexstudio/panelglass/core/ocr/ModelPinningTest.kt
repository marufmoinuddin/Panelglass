package com.smnexstudio.panelglass.core.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** manga-ocr and LaMa download from https URLs pinned to the commit their SHA-256s belong to, never a branch. */
class ModelPinningTest {
    private val pinnedBase = Regex("""^https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}$""")

    @Test fun mangaOcrIsPinnedToACommit() {
        assertTrue(pinnedBase.matches(MangaOcrStore.BASE), MangaOcrStore.BASE)
        assertTrue(MangaOcrStore.MIN_ENCODER + MangaOcrStore.MIN_DECODER + MangaOcrStore.MIN_VOCAB < MangaOcrStore.EXPECTED_BYTES)
    }

    @Test fun lamaIsPinnedToACommitWithItsHash() {
        assertTrue(pinnedBase.matches(LamaStore.BASE), LamaStore.BASE)
        assertTrue(Regex("[0-9a-f]{64}").matches(LamaStore.SHA256))
        assertTrue(LamaStore.MIN_BYTES in 1 until LamaStore.EXPECTED_BYTES)
    }
}
