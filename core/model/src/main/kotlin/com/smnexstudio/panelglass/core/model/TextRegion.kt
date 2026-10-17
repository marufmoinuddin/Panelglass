package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RegionKind { ENCLOSED, CAPTION, FREE, SFX, IN_SCENE }

/** Whether a line's or region's text was read. Code decides on this, never on the text. */
@Serializable
enum class ReadState {
    READ,

    /**
     * The detector found a text box but OCR was skipped because the engine reads the crop itself (see
     * [EngineId.readsCrops]).
     */
    UNREAD,

    /**
     * A free-text box the detector is sure of but no recognizer could read (brush-lettered sound effects): never
     * translated, only erased when the user asked for sound effects to be replaced.
     */
    UNREADABLE,
}

/** One recognised line as produced by the detector stage, before clustering. */
@Serializable
data class TextLine(
    val bbox: IntRect,
    val text: String,
    val vertical: Boolean = false,
    val angle: Float = 0f,
    val confidence: Float = 1f,
    /** Glyph size when the recogniser knows it better than the box does (a whole-bubble reading spans several columns). */
    val fontSizePx: Float? = null,
    val state: ReadState = ReadState.READ,
) {
    companion object {
        /**
         * Filler text of an [ReadState.UNREAD] line: non-blank so clustering, classification and ordering keep it. Only
         * [state] says a line is unread; before it existed this text did, and Studio rows saved then still carry it.
         */
        const val UNREAD = "[unread]"

        /** Filler text of an [ReadState.UNREADABLE] line, for the same reason as [UNREAD]. */
        const val UNREADABLE = "[unreadable]"

        fun unread(bbox: IntRect, vertical: Boolean = false) = TextLine(bbox, UNREAD, vertical = vertical, state = ReadState.UNREAD)

        fun unreadable(bbox: IntRect) = TextLine(bbox, UNREADABLE, state = ReadState.UNREADABLE)
    }
}

/** A clustered, classified region of source text. This is the unit the engine and renderer work on. */
@Serializable
data class TextRegion(
    val bbox: IntRect,
    val mask: GlyphMask? = null,
    val kind: RegionKind,
    val angle: Float = 0f,
    val vertical: Boolean = false,
    val text: String,
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val fgColor: Int = 0xFF000000.toInt(),
    val lines: List<TextLine> = emptyList(),
    /** The balloon around this text when a detector found one: the space the translation may use, in page pixels. */
    val container: IntRect? = null,
    val state: ReadState = ReadState.READ,
) {
    /** Built from [ReadState.UNREAD] lines: the engine reads this region from its crop. */
    val unread: Boolean get() = state == ReadState.UNREAD

    /** Built from a [ReadState.UNREADABLE] line: there is text here nothing could read; erase-only. */
    val unreadable: Boolean get() = state == ReadState.UNREADABLE

    /**
     * This region as saved before [state] existed, when the filler text alone marked it: a Studio row from then
     * decodes as [ReadState.READ] with the filler as its text. Regions saved since are returned as they are.
     */
    fun upgraded(): TextRegion = when {
        state != ReadState.READ -> this
        TextLine.UNREAD in text -> copy(state = ReadState.UNREAD, lines = lines.map { it.copy(state = ReadState.UNREAD) })
        text == TextLine.UNREADABLE -> copy(state = ReadState.UNREADABLE, lines = lines.map { it.copy(state = ReadState.UNREADABLE) })
        else -> this
    }

    companion object {
        /** A region the engine will read from its crop: [bbox] is also its balloon. */
        fun unread(bbox: IntRect, kind: RegionKind) = TextRegion(
            bbox = bbox, kind = kind, text = TextLine.UNREAD, lines = listOf(TextLine.unread(bbox)), container = bbox,
            state = ReadState.UNREAD,
        )
    }

    val fontSizePx: Float
        get() {
            val hinted = lines.mapNotNull { it.fontSizePx }
            return when {
                hinted.isNotEmpty() -> hinted.average().toFloat()
                lines.isEmpty() -> (if (vertical) bbox.width else bbox.height).toFloat()
                vertical -> lines.map { it.bbox.width }.average().toFloat()
                else -> lines.map { it.bbox.height }.average().toFloat()
            }
        }
}

/** Cached, engine-independent detection result for one image. */
@Serializable
data class DetectedPage(
    val width: Int,
    val height: Int,
    val regions: List<TextRegion>,
)
