package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.smnexstudio.panelglass.core.model.IntRect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The second detection pass: PP-OCRv6 tiny (PaddlePaddle, Apache-2.0, 1.8 MB, bundled) finds every piece of text on
 * the page, and what lies outside all of the comic detector's boxes is what that detector missed — mostly sound
 * effects (brush-lettered 저벅, 타닥, 반짝 went unseen by it on phone screenshots: 1 of 8 found, 7 of 8 by this one).
 *
 * Input: one 1×3×H×W float tensor, the page resized so its long side is at most [LIMIT] and both sides are multiples
 * of 32, BGR planes normalised with ImageNet mean/std (PaddleOCR's DetResizeForTest + NormalizeImage on an OpenCV
 * image). Output: 1×1×H×W text probability, turned into boxes by [DbPostProcess].
 */
@Singleton
class MissedTextDetector @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var initialized = false

    private fun ensureSession() {
        if (initialized) return
        initialized = true
        runCatching {
            val e = OrtEnvironment.getEnvironment().also { env = it }
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            session = e.createSession(bytes, OrtCpu.options(xnnpack = false))
        }.onFailure { android.util.Log.w(TAG, "Second-pass detector unavailable: ${it::class.simpleName}") }
    }

    /** Loads the model (the reader's pre-warm), so the first screen does not pay for it. */
    suspend fun warmUp() = mutex.withLock { ensureSession() }

    /** Text boxes in [bitmap]'s pixels, as free text; empty when the model cannot run. */
    suspend fun detect(bitmap: Bitmap): List<TextBox> = mutex.withLock {
        ensureSession()
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        runCatching { infer(bitmap, s, e) }.getOrElse {
            android.util.Log.w(TAG, "Second pass failed: ${it::class.simpleName}"); emptyList()
        }
    }

    private fun infer(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): List<TextBox> {
        val w = bitmap.width; val h = bitmap.height
        if (w < 32 || h < 32) return emptyList()
        val s = min(1f, LIMIT.toFloat() / max(w, h))
        val nw = max(32, (w * s / 32f).roundToInt() * 32); val nh = max(32, (h * s / 32f).roundToInt() * 32)
        val scaled = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).drawBitmap(bitmap, null, Rect(0, 0, nw, nh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(nw * nh)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        scaled.recycle()
        val plane = nw * nh
        val input = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plane) {
            val c = px[i]
            // BGR order, as PaddleOCR reads images with OpenCV; mean/std apply in that channel order.
            input.put(i, ((c and 0xFF) / 255f - 0.485f) / 0.229f)
            input.put(plane + i, (((c shr 8) and 0xFF) / 255f - 0.456f) / 0.224f)
            input.put(2 * plane + i, (((c shr 16) and 0xFF) / 255f - 0.406f) / 0.225f)
        }
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong()))
        val prob = try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } finally { tensor.close() }
        if (prob.size != plane) return emptyList()
        val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
        val found = DbPostProcess.boxes(prob, nw, nh).map { f ->
            TextBox(
                IntRect((f.box.left * sx).toInt(), (f.box.top * sy).toInt(), (f.box.right * sx).roundToInt(), (f.box.bottom * sy).roundToInt()).clamp(w, h),
                BoxKind.TEXT_FREE, f.score,
            )
        }
        return DbPostProcess.merge(found)
    }

    fun close() { runCatching { session?.close() }; session = null; initialized = false }

    companion object {
        private const val TAG = "MissedTextDetector"
        const val MODEL_ASSET = "ppocrv6-tiny-det.onnx"
        /** Long side the page is detected at: PaddleOCR's default; the desktop check found 7 of 8 effects there. */
        const val LIMIT = 960

        /**
         * The boxes the comic detector did not cover: centre outside every one of [primary] and less than
         * [MAX_COVERED] of its area inside any, at least [MIN_SIDE_OF_WIDTH] of the page width on its short side (the
         * small print drawn into art, a screen full of tiny text, is not lettering to translate), and not a box
         * spread over most of the page.
         */
        fun outside(candidates: List<TextBox>, primary: List<TextBox>, pageW: Int, pageH: Int): List<TextBox> {
            val minSide = max(12, (pageW * MIN_SIDE_OF_WIDTH).roundToInt())
            return candidates.filter { c ->
                val b = c.bbox
                minOf(b.width, b.height) >= minSide && b.area < pageW.toLong() * pageH * MAX_PAGE_SHARE &&
                    primary.none { p -> p.bbox.contains(b.centerX.toInt(), b.centerY.toInt()) || b.overlapFraction(p.bbox) >= MAX_COVERED }
            }
        }

        const val MAX_COVERED = 0.3f
        const val MIN_SIDE_OF_WIDTH = 0.02f
        const val MAX_PAGE_SHARE = 0.5f
    }
}

/**
 * PaddleOCR's DB post-processing on a text probability map, with axis-aligned boxes (the reader erases and letters
 * rectangles): pixels over [THRESH] in 4-connected components, kept when their mean probability reaches
 * [BOX_THRESH], each grown by `area × UNCLIP / perimeter` as PaddleOCR's unclip does (the map is a shrunk core of
 * the text).
 */
object DbPostProcess {
    const val THRESH = 0.3f
    const val BOX_THRESH = 0.6f
    const val UNCLIP = 1.5f
    const val MIN_SIDE = 3

    class Found(val box: IntRect, val score: Float)

    fun boxes(prob: FloatArray, w: Int, h: Int): List<Found> {
        require(prob.size == w * h)
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = ArrayList<Found>()
        for (start in prob.indices) {
            if (seen[start] || prob[start] <= THRESH) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var sum = 0.0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                sum += prob[p]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && !seen[p - 1] && prob[p - 1] > THRESH) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x < w - 1 && !seen[p + 1] && prob[p + 1] > THRESH) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > 0 && !seen[p - w] && prob[p - w] > THRESH) { seen[p - w] = true; queue[tail++] = p - w }
                if (y < h - 1 && !seen[p + w] && prob[p + w] > THRESH) { seen[p + w] = true; queue[tail++] = p + w }
            }
            val score = (sum / tail).toFloat()
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (score < BOX_THRESH || min(bw, bh) < MIN_SIDE) continue
            val grow = (bw.toFloat() * bh * UNCLIP / (2f * (bw + bh))).roundToInt()
            out += Found(IntRect(minX, minY, maxX + 1, maxY + 1).inflate(grow).clamp(w, h), score)
        }
        return out
    }

    /**
     * Boxes closer than [gapOfSide] of their smaller short side are one block: the characters of one effect (타 and 닥)
     * and the lines of one caption. The merged box keeps the higher score.
     */
    fun merge(boxes: List<TextBox>, gapOfSide: Float = 0.5f): List<TextBox> {
        val work = boxes.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in work.indices) for (j in i + 1 until work.size) {
                val a = work[i]; val b = work[j]
                val gap = (min(min(a.bbox.width, a.bbox.height), min(b.bbox.width, b.bbox.height)) * gapOfSide).roundToInt()
                if (a.bbox.inflate(gap).intersects(b.bbox)) {
                    work[i] = TextBox(a.bbox.union(b.bbox), BoxKind.TEXT_FREE, max(a.confidence, b.confidence))
                    work.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return work
    }
}
