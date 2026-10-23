package com.camscaner.app.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.PageAligner
import com.camscaner.app.scanner.PageProcessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Result of aligning a page automatically. */
data class Alignment(
    /** Clockwise rotation (0, 90, 180, 270) that makes the text upright; null when unknown. */
    val rotation: Int?,
    /** Clockwise straightening measured on the page, in degrees (0 when none is needed). */
    val skew: Float,
)

/**
 * Automatic page alignment: levels the text lines (straighten) and finds which way up the page is
 * from the direction ML Kit reads its lines in. The recognizer reads text in any orientation and
 * reports each line's angle, so one pass tells upright (0°), upside-down (180°) and sideways pages
 * apart.
 */
object AutoAligner {

    private const val ANALYSIS_SIZE = 1200

    /** Fewer readable characters than this: no text to judge by (photo, drawing, other script). */
    private const val MIN_SCORE = 12f

    /** The winning direction must hold clearly more text than the runner-up. */
    private const val MARGIN = 1.5f

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Aligns the photo [originalFile] cropped to [corners] (null keeps the whole photo). */
    suspend fun analyze(originalFile: File, corners: Corners?): Alignment {
        val original = ImageIO.decodeSampled(originalFile, ANALYSIS_SIZE * 5 / 4) ?: return Alignment(null, 0f)
        val warped = try {
            PageProcessor.warp(original, corners, ANALYSIS_SIZE)
        } finally {
            original.recycle()
        }
        return analyze(warped)
    }

    /** Aligns a perspective-corrected page; [page] is recycled. */
    suspend fun analyze(page: Bitmap): Alignment {
        // sparse or faint text may have no clear line profile; it can still be read
        val estimate = runCatching { PageAligner.measure(page) }.getOrNull()
        val skew = estimate?.correction?.takeIf { abs(it) >= PageAligner.MIN_CORRECTION } ?: 0f
        val straight = PageAligner.rotate(page, skew)
        if (straight !== page) page.recycle()
        // ML Kit keeps reading the bitmap after a cancelled await; finish before recycling it
        return withContext(NonCancellable) {
            try {
                Alignment(orientation(straight), skew)
            } finally {
                straight.recycle()
            }
        }
    }

    /**
     * Clockwise rotation that makes [page] upright, or null when the page has no readable text or
     * its lines do not agree on a direction.
     */
    suspend fun orientation(page: Bitmap): Int? {
        val started = SystemClock.elapsedRealtime()
        val text = recognizer.process(InputImage.fromBitmap(page, 0)).await()
        // text weighted by how readable it is, per reading direction: 0°, 90°, 180° and 270°
        val votes = FloatArray(4)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val letters = line.text.count { it.isLetterOrDigit() }
                val visible = line.text.count { !it.isWhitespace() }
                if (letters < 2 || visible == 0) continue
                // garbled readings are heavy in punctuation and stray marks
                val clean = letters.toFloat() / visible
                // some models report no confidence; then every line counts fully
                val confidence = if (line.confidence > 0f) line.confidence else 1f
                val quarter = Math.floorMod((line.angle / 90f).roundToInt(), 4)
                votes[quarter] += letters * confidence * clean * clean
            }
        }
        val ranked = votes.indices.sortedByDescending { votes[it] }
        val best = ranked[0]
        val runnerUp = votes[ranked[1]]
        // text running at best·90° clockwise is set upright by turning the page back by as much
        val result = if (votes[best] < MIN_SCORE || votes[best] < runnerUp * MARGIN) null else (360 - best * 90) % 360
        Log.d(
            TAG,
            "orientation ${page.width}x${page.height}: votes ${votes.joinToString { "%.0f".format(it) }} " +
                "-> $result in ${SystemClock.elapsedRealtime() - started} ms",
        )
        return result
    }

    private const val TAG = "AutoAligner"
}
