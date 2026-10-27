package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.TextLine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which manga-ocr readings of a box are taken for art, speed lines or small print ([ComicTextDetector.unsureFreeText]). */
class UnsureFreeTextTest {
    private val box = IntRect(0, 0, 200, 200)
    private fun line(confidence: Float) = TextLine(box, "ドン", confidence = confidence)

    @Test
    fun anUnsureReadingOfFreeTextIsNotText() {
        // Speed lines read as ドーン! scored 0.62 on a chapter's dumps; small print 0.69, a watermark 0.73.
        assertTrue(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.TEXT_FREE, 0.9f), listOf(line(0.62f))))
        assertTrue(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.TEXT_FREE, 0.9f), listOf(line(0.73f))))
    }

    @Test
    fun realFreeTextAndAnyBalloonAreKept() {
        // Real free lines and every real balloon scored 0.87 or more.
        assertFalse(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.TEXT_FREE, 0.9f), listOf(line(0.87f))))
        // Balloon text is never dropped, however unsure: a translation there is worth the risk.
        assertFalse(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.TEXT_BUBBLE, 0.9f), listOf(line(0.5f))))
        assertFalse(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.BUBBLE, 0.9f), listOf(line(0.5f))))
        // Nothing read is not "unsure": the unreadable-text path handles it.
        assertFalse(ComicTextDetector.unsureFreeText(TextBox(box, BoxKind.TEXT_FREE, 0.9f), emptyList()))
    }
}
