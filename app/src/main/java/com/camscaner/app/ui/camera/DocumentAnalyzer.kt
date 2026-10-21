package com.camscaner.app.ui.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.Pt

/** Detection result for a preview frame, in upright normalized coordinates. */
data class FrameDetection(val corners: Corners?, val frameWidth: Int, val frameHeight: Int)

/** Runs the document detector on the luminance plane of preview frames (~12 fps). */
class DocumentAnalyzer(private val onResult: (FrameDetection) -> Unit) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < MIN_INTERVAL_MS) return
            lastRun = now
            val plane = image.planes[0]
            val gray = DocumentDetector.grayFromPlane(plane.buffer, image.width, image.height, plane.rowStride)
            val corners = try {
                DocumentDetector.detectGray(gray)
            } finally {
                gray.release()
            }
            val rotation = image.imageInfo.rotationDegrees
            val upright = corners?.let { rotate(it, rotation) }
            val (w, h) = if (rotation % 180 == 0) image.width to image.height else image.height to image.width
            onResult(FrameDetection(upright, w, h))
        } catch (e: Throwable) {
            // A failed frame is simply skipped.
        } finally {
            image.close()
        }
    }

    private fun rotate(c: Corners, degrees: Int): Corners {
        val pts = c.points.map { p ->
            when (degrees) {
                90 -> Pt(1f - p.y, p.x)
                180 -> Pt(1f - p.x, 1f - p.y)
                270 -> Pt(p.y, 1f - p.x)
                else -> p
            }
        }
        return Corners.ordered(pts)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 80L
    }
}

/** Decides when a detected document has been held still long enough to auto-capture. */
class StabilityTracker(private val holdMillis: Long = 1200L) {

    private var last: Corners? = null
    private var stableSince = 0L
    private var armed = true

    /** Returns progress in [0, 1]; 1 means "capture now". */
    fun update(corners: Corners?, now: Long = SystemClock.elapsedRealtime()): Float {
        val previous = last
        last = corners
        if (corners == null || corners.area() < MIN_AREA) {
            stableSince = 0L
            armed = true
            return 0f
        }
        if (previous == null || corners.maxDistanceTo(previous) > MAX_JITTER) {
            stableSince = now
            armed = true
            return 0f
        }
        if (!armed) return 0f
        return ((now - stableSince).toFloat() / holdMillis).coerceIn(0f, 1f)
    }

    /** Called after a capture: the document has to move (next page) before the next one. */
    fun disarm() {
        armed = false
    }

    private companion object {
        const val MIN_AREA = 0.18f
        const val MAX_JITTER = 0.025f
    }
}
