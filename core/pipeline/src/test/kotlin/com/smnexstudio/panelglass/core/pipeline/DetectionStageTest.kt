package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.ocr.BoxKind
import com.smnexstudio.panelglass.core.ocr.TextBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetectionStageTest {
    private val sfx = IntRect(100, 100, 200, 180)

    @Test
    fun aSureFreeTextBoxNothingWasReadInBecomesUnreadable() {
        val out = DetectionStage.unreadableLines(listOf(TextBox(sfx, BoxKind.TEXT_FREE, 0.8f)), emptyList())
        assertEquals(listOf(sfx), out.map { it.bbox })
        assertEquals(listOf(ReadState.UNREADABLE), out.map { it.state })
    }

    @Test
    fun readBoxesUnsureBoxesAndBalloonTextAreLeftAlone() {
        val read = TextLine(IntRect(120, 120, 180, 160), "ドン")
        assertEquals(0, DetectionStage.unreadableLines(listOf(TextBox(sfx, BoxKind.TEXT_FREE, 0.8f)), listOf(read)).size)
        assertEquals(0, DetectionStage.unreadableLines(listOf(TextBox(sfx, BoxKind.TEXT_FREE, 0.3f)), emptyList()).size)
        assertEquals(0, DetectionStage.unreadableLines(listOf(TextBox(sfx, BoxKind.TEXT_BUBBLE, 0.9f)), emptyList()).size)
    }
}
