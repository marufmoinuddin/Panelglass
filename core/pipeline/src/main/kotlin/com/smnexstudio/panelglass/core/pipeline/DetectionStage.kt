package com.smnexstudio.panelglass.core.pipeline

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.BoxKind
import com.smnexstudio.panelglass.core.ocr.PageDetection
import com.smnexstudio.panelglass.core.ocr.PanelCutter
import com.smnexstudio.panelglass.core.ocr.RegionBuilder
import com.smnexstudio.panelglass.core.ocr.RegionClassifier
import com.smnexstudio.panelglass.core.ocr.ReadingOrder
import com.smnexstudio.panelglass.core.ocr.RegionClusterer
import com.smnexstudio.panelglass.core.render.RegionRenderer
import com.smnexstudio.panelglass.core.ocr.TextBox
import com.smnexstudio.panelglass.core.ocr.TextDetector
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Pixels to regions: the detector (comic detector, second pass, OCR) on the page or its tiles, then regions built from
 * its boxes and lines, filtered, classified and put in reading order. Both entry points use it: the reader's viewport
 * (edge-cut boxes dropped, region of interest) and the Studio's whole pages. The caller holds the detect gate.
 */
class DetectionStage(private val detector: TextDetector) {
    private val regionBuilder = RegionBuilder(RegionClusterer())
    private val classifier = RegionClassifier()
    private val panelCutter = PanelCutter()

    /**
     * A tall page in overlapping tiles ([TileDetection]): each tile detected on its own (barely scaled), boxes and
     * lines mapped to page pixels and their overlap copies merged, then built, classified and ordered as one page.
     * A box cut by a tile's inner edge is not read there; the tile that holds it whole reads it.
     */
    suspend fun detectTiled(src: PageSource, lang: Lang, read: Boolean): List<TextRegion> {
        val results = ArrayList<TileDetection.TileResult>()
        for (tile in TileDetection.tiles(src.width, src.height)) {
            coroutineContext.ensureActive()
            val tileBmp = Bitmap.createBitmap(src.bitmap, tile.left, tile.top, tile.width, tile.height)
            val (small, scale) = ImageDecoder.forDetection(tileBmp)
            val inv = 1f / scale
            fun toPage(r: IntRect) = r.scale(inv).offset(tile.left, tile.top).clamp(src.width, src.height)
            val skipRead: (TextBox) -> Boolean = { b -> TileDetection.cutByTile(toPage(b.bbox), tile, src.height) }
            val detection = try {
                detector.detectPage(small, lang, skipRead, read = read)
            } finally {
                if (small !== tileBmp) small.recycle()
                if (tileBmp !== src.bitmap) tileBmp.recycle()
            }
            results += TileDetection.TileResult(
                tile,
                detection.boxes.map { it.copy(bbox = toPage(it.bbox)) },
                detection.lines.map { it.copy(bbox = toPage(it.bbox)) },
            )
        }
        val boxes = TileDetection.mergeBoxes(results, src.height)
        val lines = TileDetection.mergeLines(results, src.height)
        android.util.Log.d(TAG, "detect tiled: tiles=${results.size} boxes=${boxes.size} lines=${lines.size}")
        return buildRegions(src, lang, lines, boxes)
    }

    /**
     * Detection on the snapshot scaled to ≤1600 px, mapped back to its pixels, then clustered and classified against
     * the full-resolution pixels.
     */
    suspend fun detectAll(
        src: PageSource, lang: Lang, dropEdgeCut: Boolean = false, roi: RegionOfInterest? = null,
        deferred: MutableList<IntRect>? = null, read: Boolean = true,
        /** A sure free-text box nobody could read becomes a [ReadState.UNREADABLE] region (to be erased). */
        keepUnreadable: Boolean = false,
    ): List<TextRegion> {
        val lines = ArrayList<TextLine>()
        val boxes = ArrayList<TextBox>()
        // The whole snapshot, scaled for the detector; its boxes map back by 1/scale.
        coroutineContext.ensureActive()
        val (small, scale) = ImageDecoder.forDetection(src.bitmap)
        val inv = 1f / scale
        // Boxes the filters below will drop anyway are not OCR'd: outside the page's images, under its controls,
        // already translated, or a text box cut by a scroll edge (the same tests, in page coordinates).
        val skipRead: (TextBox) -> Boolean = { b ->
            val p = b.bbox.scale(inv).clamp(src.width, src.height)
            (roi != null && !roi.allows(p)) ||
                (dropEdgeCut && b.kind != BoxKind.BUBBLE && (p.top <= EDGE_PX || p.bottom >= src.height - EDGE_PX))
        }
        val detection = try {
            when {
                !read -> detector.detectPage(small, lang, skipRead, read = false)
                roi != null || dropEdgeCut -> detector.detectPage(small, lang, skipRead)
                else -> detector.detectPage(small, lang)
            }
        } finally { if (small !== src.bitmap) small.recycle() }
        for (l in detection.lines) lines += l.copy(bbox = l.bbox.scale(inv).clamp(src.width, src.height))
        for (b in detection.boxes) boxes += b.copy(bbox = b.bbox.scale(inv).clamp(src.width, src.height))
        // One region per detector box (a bubble is one sentence), proximity clustering for the rest.
        val found = boxes.size
        if (roi != null) {
            boxes.removeAll { !roi.allows(it.bbox) }
            lines.removeAll { !roi.allows(it.bbox) }
        }
        if (dropEdgeCut) {
            // Only the scroll edges cut content: the page fills the width, so a box at the left/right edge is whole.
            // A cut *text* box is deferred; a cut balloon whose text box is whole is merely a smaller layout area
            // (joined balloons often reach the edge while their lower bubble is entirely on screen).
            val cut = boxes.filter { it.bbox.top <= EDGE_PX || it.bbox.bottom >= src.height - EDGE_PX }
            boxes.removeAll(cut.toSet())
            lines.removeAll { l -> cut.any { it.kind != BoxKind.BUBBLE && it.bbox.contains(l.bbox.centerX.toInt(), l.bbox.centerY.toInt()) } }
            deferred?.addAll(cut.map { it.bbox })
            // A loose line (outside every remaining text box) within two glyph heights of a scroll edge belongs to a
            // block the detector could not see whole; it will be read when the block is fully on screen.
            lines.removeAll { l ->
                val glyph = minOf(l.bbox.width, l.bbox.height)
                val boxed = boxes.any { it.kind != BoxKind.BUBBLE && it.bbox.contains(l.bbox.centerX.toInt(), l.bbox.centerY.toInt()) }
                !boxed && (l.bbox.top <= EDGE_PX + 2 * glyph || l.bbox.bottom >= src.height - EDGE_PX - 2 * glyph)
            }
        }
        if (read && keepUnreadable) lines += unreadableLines(boxes, lines)
        android.util.Log.d(TAG, "detect: boxes=$found kept=${boxes.size} lines=${lines.size} deferred=${deferred?.size ?: 0}")
        return buildRegions(src, lang, lines, boxes)
    }

    /** Regions from lines and boxes in page pixels: one per detector box, clustered, classified, in reading order. */
    private fun buildRegions(src: PageSource, lang: Lang, lines: List<TextLine>, boxes: List<TextBox>): List<TextRegion> {
        // Each region stays with its seed and boxed flag until classified: nothing is looked up by the region itself.
        val classified = regionBuilder.build(PageDetection(lines, boxes), lang)
            // A region with no letters is a page number or bare punctuation ("139", "……", "!?"): nothing to translate,
            // and painting over it only hides the original.
            .filter { it.region.unread || it.region.text.any(Char::isLetter) }
            // A lone character only ML Kit's full-page pass saw, outside every detector box, is not text to translate:
            // a ✓ read as ソ came out as "So". Detected boxes (sound effects such as ド) are kept, single letter or not.
            .filter { b -> val r = b.region; b.boxed || r.unread || r.unreadable || r.text.count(Char::isLetter) >= 2 }
            .map { b -> RegionBuilder.seeded(classifier.classify(src, b.region), b.seed) }
            // What nothing could read is kept only as a sound effect to erase: never balloon text to letter.
            .map { if (it.unreadable) it.copy(kind = RegionKind.SFX) else it }
        val panels = panelCutter.segment(src, lang.rtlReading)
        return panelCutter.orderRegions(mergeSameBalloon(dropNested(classified), lang), panels, lang.rtlReading)
    }

    companion object {
        /**
         * Regions without a reading nested in another one that already holds its text. Two overlapping text boxes over
         * one balloon read "…あなた" and "…あなたどうして…!": both were translated and lettered into the same balloon,
         * each at half the room. The larger region (the earlier of two equal ones) is kept.
         */
        internal fun dropNested(regions: List<TextRegion>): List<TextRegion> = regions.filterIndexed { i, r ->
            if (r.unread || r.unreadable) return@filterIndexed true
            val core = r.text.filter(Char::isLetterOrDigit)
            if (core.isEmpty() || r.bbox.area <= 0) return@filterIndexed true
            regions.withIndex().none { (j, o) ->
                j != i && !o.unread && !o.unreadable &&
                    (o.bbox.area > r.bbox.area || (o.bbox.area == r.bbox.area && j < i)) &&
                    (o.bbox.intersect(r.bbox)?.area ?: 0) >= r.bbox.area * NESTED_SHARE &&
                    o.text.filter(Char::isLetterOrDigit).contains(core)
            }
        }

        /** Share of a region's box inside another for it to count as nested in it. */
        private const val NESTED_SHARE = 0.8f

        /** Text boxes of one balloon span at most this multiple of their summed widths (and heights). */
        private const val ADJACENT_SPAN = 1.5f

        /**
         * Balloon text read as several regions (two columns: 見せてやる！ and この力が…) becomes one region, in reading
         * order. Each was erased with the whole balloon and gave a patch over the same rectangle, so the reader kept one
         * patch and dropped the other as a duplicate: the second sentence vanished, its Japanese erased. One region
         * also gives the engine the whole utterance and the lettering all the balloon's room. Not for a box spanning
         * joined balloons, whose texts lie far apart ([ADJACENT_SPAN]), nor for regions the engine reads itself.
         */
        internal fun mergeSameBalloon(regions: List<TextRegion>, lang: Lang): List<TextRegion> {
            val groups = regions.withIndex()
                .filter { (_, r) -> r.kind == RegionKind.ENCLOSED && r.container != null && r.state == ReadState.READ && r.text.isNotBlank() }
                .groupBy { it.value.container!! }
                // Columns (or rows) of one balloon sit side by side: together they span about the sum of their sizes. Two
                // balloons the detector boxed as one hold text far apart, which spans much more. The balloon's box may be
                // smaller than the lettering (a spiky shout's text reaches into the spikes): the regions were already
                // grouped by the balloon holding each text box's centre, so that is not checked again.
                .filter { (balloon, members) ->
                    members.size > 1 && members.map { it.value.bbox }.let { boxes ->
                        val u = boxes.reduce(IntRect::union)
                        u.width <= boxes.sumOf { it.width } * ADJACENT_SPAN && u.height <= boxes.sumOf { it.height } * ADJACENT_SPAN &&
                            balloon.width <= u.width * RegionRenderer.BALLOON_MAX_WIDTH_X
                    }
                }
            if (groups.isEmpty()) return regions
            val merged = HashMap<Int, TextRegion>()
            val gone = HashSet<Int>()
            val sep = if (lang.isCjk && lang != Lang.KO) "" else " "
            for ((_, members) in groups) {
                val ordered = ReadingOrder.sort(members.map { it.value }, lang.rtlReading)
                val first = ordered.first()
                merged[members.minOf { it.index }] = first.copy(
                    bbox = ordered.map { it.bbox }.reduce(IntRect::union),
                    text = ordered.joinToString(sep) { it.text.trim() },
                    lines = ordered.flatMap { it.lines },
                    vertical = ordered.count { it.vertical } * 2 >= ordered.size,
                )
                members.forEach { gone += it.index }
            }
            return regions.mapIndexedNotNull { i, r -> merged[i] ?: r.takeIf { i !in gone } }
        }

        /**
         * One [ReadState.UNREADABLE] line per free-text box the detector is sure of ([UNREADABLE_MIN_CONFIDENCE]) that no
         * line was read in: brush-lettered sound effects ML Kit cannot read even in high contrast (저벅, 벌벌).
         */
        internal fun unreadableLines(boxes: List<TextBox>, lines: List<TextLine>): List<TextLine> =
            boxes.filter { b ->
                b.kind == BoxKind.TEXT_FREE && b.confidence >= UNREADABLE_MIN_CONFIDENCE &&
                    lines.none { l -> b.bbox.contains(l.bbox.centerX.toInt(), l.bbox.centerY.toInt()) }
            }.map { TextLine.unreadable(it.bbox) }

        /** A free-text box below this detector score that nothing could read is not erased: it may not be text. */
        private const val UNREADABLE_MIN_CONFIDENCE = 0.5f
        /** A detector box this close to a viewport edge is assumed cut off. */
        private const val EDGE_PX = 2
        private const val TAG = "TranslationPipeline"
    }
}
