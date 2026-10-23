package com.camscaner.app.ocr

import android.graphics.Bitmap
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

/** Result of aligning a page automatically. */
data class Alignment(
    /** Clockwise rotation (0, 90, 180, 270) that makes the text upright; null when unknown. */
    val rotation: Int?,
    /** Clockwise straightening measured on the page, in degrees (0 when none is needed). */
    val skew: Float,
)

/**
 * Automatic page alignment: levels the text lines (straighten) and finds which way up the page is
 * by reading it in each candidate orientation and keeping the one that yields confident text.
 */
object AutoAligner {

    private const val ANALYSIS_SIZE = 1200

    /** Fewer readable characters than this: no text to judge by (photo, drawing, other script). */
    private const val MIN_SCORE = 12f

    /** The winning orientation must read clearly better than the runner-up. */
    private const val MARGIN = 1.4f

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
        val estimate = runCatching { PageAligner.measure(page) }.getOrNull()
        val skew = estimate?.correction?.takeIf { abs(it) >= PageAligner.MIN_CORRECTION } ?: 0f
        val straight = PageAligner.rotate(page, skew)
        if (straight !== page) page.recycle()
        // ML Kit keeps reading the bitmap after a cancelled await; finish before recycling it
        return withContext(NonCancellable) {
            try {
                Alignment(orientation(straight, estimate?.sideways), skew)
            } finally {
                straight.recycle()
            }
        }
    }

    /**
     * Clockwise rotation that makes [page] upright, judged by text recognition, or null when the
     * page has no readable text or the result is ambiguous. [sideways] narrows the candidates
     * when the direction of the lines is already known.
     */
    suspend fun orientation(page: Bitmap, sideways: Boolean?): Int? {
        val candidates = when (sideways) {
            false -> listOf(0, 180)
            true -> listOf(90, 270)
            null -> listOf(0, 90, 180, 270)
        }
        val scores = candidates.map { it to score(page, it) }.sortedByDescending { it.second }
        val (best, bestScore) = scores[0]
        val runnerUp = scores[1].second
        // no text to judge by, or no clear winner: leave the orientation to the user
        if (bestScore < MIN_SCORE || bestScore < runnerUp * MARGIN) return null
        return best
    }

    /**
     * Confidence-weighted count of characters read on level lines when the page is turned
     * clockwise by [rotation]. Text read upside down or sideways comes out garbled, short or tilted.
     */
    private suspend fun score(page: Bitmap, rotation: Int): Float {
        val text = recognizer.process(InputImage.fromBitmap(page, rotation)).await()
        var score = 0f
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (abs(line.angle) > LEVEL_TOLERANCE) continue
                val chars = line.text.count { it.isLetterOrDigit() }
                score += chars * line.confidence
            }
        }
        return score
    }

    private const val LEVEL_TOLERANCE = 30f
}
