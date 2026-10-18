package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
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

    private fun region(text: String, l: Int, t: Int, r: Int, b: Int) =
        TextRegion(bbox = IntRect(l, t, r, b), kind = RegionKind.ENCLOSED, text = text)

    @Test
    fun aReadingNestedInOneThatHoldsItsTextIsDropped() {
        val whole = region("...あなたどうして...!", 444, 1194, 541, 1440)
        val part = region("...あなた", 497, 1194, 540, 1324)
        assertEquals(listOf(whole), DetectionStage.dropNested(listOf(part, whole)))
    }

    @Test
    fun neighboursAndDifferentTextInsideAreKept() {
        val balloon = region("あいつの狙いは僕だ", 100, 100, 300, 400)
        val inside = region("ちがう", 150, 150, 250, 250)
        val beside = region("あいつ", 400, 100, 500, 200)
        assertEquals(listOf(balloon, inside, beside), DetectionStage.dropNested(listOf(balloon, inside, beside)))
        // The same reading twice: one goes, the earlier one stays.
        val copy = region("あいつの狙いは僕だ", 100, 100, 300, 400)
        assertEquals(1, DetectionStage.dropNested(listOf(balloon, copy)).size)
    }

    private fun column(text: String, l: Int, t: Int, r: Int, b: Int, balloon: IntRect) =
        TextRegion(bbox = IntRect(l, t, r, b), kind = RegionKind.ENCLOSED, text = text, vertical = true, container = balloon)

    @Test
    fun twoColumnsInOneBalloonAreOneRegionReadRightToLeft() {
        val balloon = IntRect(380, 680, 760, 1240)
        val left = column("まずは小手調べだ!!", 412, 821, 635, 1215, balloon)
        val right = column("来るというのなら", 603, 701, 734, 900, balloon)
        val other = column("ほかの吹き出し", 900, 100, 960, 300, IntRect(880, 80, 980, 320))
        val out = DetectionStage.mergeSameBalloon(listOf(left, other, right), Lang.JA)
        assertEquals(2, out.size)
        assertEquals("来るというのならまずは小手調べだ!!", out[0].text)
        assertEquals(IntRect(412, 701, 734, 1215), out[0].bbox)
        assertEquals(other, out[1])
    }

    @Test
    fun aNarrowColumnBesideAWideOneInAShoutIsMerged() {
        // The geometry of a spiky shout read as two columns: a 70 px one beside a 164 px one, the balloon 287 px wide
        // and smaller than the lettering.
        val shout = IntRect(5, 1418, 292, 1921)
        val first = column("見せてやる!", 207, 1383, 277, 1584, shout)
        val second = column("この力は強い!!", 63, 1517, 227, 1856, shout)
        val out = DetectionStage.mergeSameBalloon(listOf(first, second), Lang.JA)
        assertEquals(listOf("見せてやる!この力は強い!!"), out.map { it.text })
    }

    @Test
    fun aBoxSpanningJoinedBalloonsIsNotMerged() {
        // Narrow text far apart in one very wide box: two balloons the detector boxed as one.
        val joined = IntRect(0, 0, 1000, 300)
        val a = column("ひとつめ", 40, 40, 80, 260, joined)
        val b = column("ふたつめ", 920, 40, 960, 260, joined)
        assertEquals(listOf(a, b), DetectionStage.mergeSameBalloon(listOf(a, b), Lang.JA))
    }
}
