package com.smnexstudio.panelglass.core.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextNorm
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Reads the text inside one container crop. Lines come back in the crop's own pixel space. */
interface CropRecognizer {
    suspend fun recognize(crop: Bitmap, lang: Lang): List<TextLine>
}

/**
 * How a crop is resized before ML Kit sees it. Google's text-recognition guidance wants CJK glyphs well above
 * ~24 px, so small bubble crops are grown (≤ [MAX_UPSCALE]) until the short side reaches [TARGET_SHORT_SIDE], and
 * oversized crops are shrunk so the long side stays under [MAX_LONG_SIDE]. Results are mapped back by the caller.
 */
object CropScalePlan {
    const val TARGET_SHORT_SIDE = 1200
    const val MAX_LONG_SIDE = 3000
    const val MAX_UPSCALE = 3f

    fun scaleFor(width: Int, height: Int): Float {
        require(width > 0 && height > 0)
        val short = min(width, height).toFloat()
        val long = max(width, height).toFloat()
        return when {
            long > MAX_LONG_SIDE -> MAX_LONG_SIDE / long
            short < TARGET_SHORT_SIDE -> min(TARGET_SHORT_SIDE / short, min(MAX_UPSCALE, MAX_LONG_SIDE / long))
            else -> 1f
        }
    }
}

/**
 * A crop as black ink on white, for a second reading when the first found nothing: white or coloured narration
 * on dark art, outlined or brush lettering on busy backgrounds. Otsu splits the luminance in two; the smaller
 * class is the ink (lettering covers less of a text box than its background does).
 */
object HighContrast {
    /** ARGB pixels to black (ink) / white; null when the crop is one flat tone (nothing to separate). */
    fun binarize(argb: IntArray): IntArray? {
        if (argb.isEmpty()) return null
        val lum = IntArray(argb.size)
        val hist = IntArray(256)
        for (i in argb.indices) {
            val c = argb[i]
            val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            lum[i] = l; hist[l]++
        }
        val t = otsu(hist, argb.size) ?: return null
        val above = lum.count { it > t }
        // The ink is whichever side has fewer pixels.
        val inkBright = above < argb.size - above
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
        return IntArray(argb.size) { i -> if ((lum[i] > t) == inkBright) black else white }
    }

    private fun otsu(hist: IntArray, total: Int): Int? {
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0; var best = -1.0; var threshold = -1
        for (t in 0..254) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold.takeIf { it >= 0 }
    }
}

/** Rect mapping for the 90° "shadow" passes. Rotation is about the image, sizes given for the upright crop. */
object RotationGeometry {
    /** A rect in a crop rotated 90° clockwise, back to the upright crop of size [uprightW]×[uprightH]. */
    fun fromClockwise(r: IntRect, uprightW: Int, uprightH: Int): IntRect =
        IntRect(r.top, uprightH - r.right, r.bottom, uprightH - r.left).clamp(uprightW, uprightH)

    /** A rect in a crop rotated 90° counter-clockwise, back to the upright crop. */
    fun fromCounterClockwise(r: IntRect, uprightW: Int, uprightH: Int): IntRect =
        IntRect(uprightW - r.bottom, r.left, uprightW - r.top, r.right).clamp(uprightW, uprightH)
}

/**
 * ML Kit on a container crop. Two things the full-page pass cannot do:
 *
 *  1. the crop is scaled to the size ML Kit reads best ([CropScalePlan]);
 *  2. a crop that reads as nothing is read once more as black ink on white ([HighContrast]): narration in white
 *     or colour on dark art, and outlined or brush lettering, often read only that way;
 *  3. for Japanese, when the upright reading of a tall crop is weak (nothing, or a scatter of one- and
 *     two-character fragments — the recogniser's failure mode on vertical text), the crop is read again
 *     rotated ±90° so each column becomes a row, and the reading with the most CJK characters wins.
 *     Rotated results are mapped back and flagged vertical so the clusterer joins columns right-to-left.
 */
@Singleton
class MlKitCropRecognizer @Inject constructor(private val mlKit: MlKitTextDetector) : CropRecognizer {

    override suspend fun recognize(crop: Bitmap, lang: Lang): List<TextLine> {
        if (crop.width < 2 || crop.height < 2) return emptyList()
        var upright = pass(crop, lang, Rotation.NONE)
        if (upright.none { l -> l.text.any(Char::isLetter) }) contrastPass(crop, lang)?.let { upright = it }
        if (lang != Lang.JA || !isWeak(upright, crop)) return upright
        val best = listOf(Rotation.CCW, Rotation.CW)
            .map { pass(crop, lang, it) }
            .maxByOrNull { cjkCount(it) } ?: return upright
        return if (cjkCount(best) > cjkCount(upright)) best else upright
    }

    private enum class Rotation(val degrees: Float) { NONE(0f), CW(90f), CCW(-90f) }

    /** [crop] as black on white, read upright; null when that finds no letters either (or nothing to separate). */
    private suspend fun contrastPass(crop: Bitmap, lang: Lang): List<TextLine>? {
        val px = IntArray(crop.width * crop.height)
        crop.getPixels(px, 0, crop.width, 0, 0, crop.width, crop.height)
        val bw = HighContrast.binarize(px) ?: return null
        val bmp = Bitmap.createBitmap(bw, crop.width, crop.height, Bitmap.Config.ARGB_8888)
        return try {
            pass(bmp, lang, Rotation.NONE).takeIf { lines -> lines.any { l -> l.text.any(Char::isLetter) } }
        } finally { bmp.recycle() }
    }

    private suspend fun pass(crop: Bitmap, lang: Lang, rotation: Rotation): List<TextLine> {
        val scale = CropScalePlan.scaleFor(crop.width, crop.height)
        val scaled = if (scale == 1f) crop else {
            val w = max(1, ceil(crop.width * scale).toInt()); val h = max(1, ceil(crop.height * scale).toInt())
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).drawBitmap(crop, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        val input = if (rotation == Rotation.NONE) scaled else {
            val m = Matrix().apply { postRotate(rotation.degrees) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
        }
        try {
            val lines = mlKit.detect(input, lang)
            val sw = scaled.width; val sh = scaled.height
            return lines.map { line ->
                val inScaled = when (rotation) {
                    Rotation.NONE -> line.bbox
                    Rotation.CW -> RotationGeometry.fromClockwise(line.bbox, sw, sh)
                    Rotation.CCW -> RotationGeometry.fromCounterClockwise(line.bbox, sw, sh)
                }
                val inCrop = if (scale == 1f) inScaled else inScaled.scale(1f / scale).clamp(crop.width, crop.height)
                when (rotation) {
                    Rotation.NONE -> line.copy(bbox = inCrop)
                    // A column read as a row: clockwise puts the column's top on the right, so the row reads bottom-up.
                    Rotation.CW -> line.copy(bbox = inCrop, text = line.text.reversed(), vertical = true, angle = 0f)
                    Rotation.CCW -> line.copy(bbox = inCrop, vertical = true, angle = 0f)
                }
            }
        } finally {
            if (input !== scaled) input.recycle()
            if (scaled !== crop) scaled.recycle()
        }
    }

    /** No CJK at all, or a tall crop that came back only as horizontal one- and two-character bits. */
    private fun isWeak(lines: List<TextLine>, crop: Bitmap): Boolean {
        val cjk = cjkCount(lines)
        if (cjk == 0) return true
        val tall = crop.height >= crop.width * 1.3f
        return tall && lines.all { !it.vertical && it.text.count(TextNorm::isCjk) <= 2 }
    }

    private fun cjkCount(lines: List<TextLine>) = lines.sumOf { l -> l.text.count(TextNorm::isCjk) }
}
