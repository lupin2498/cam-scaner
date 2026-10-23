package com.camscaner.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/** Measures and corrects the tilt of a perspective-corrected page ("straighten"). */
object PageAligner {

    /** Tilts smaller than this are left alone: correcting them only softens the image. */
    const val MIN_CORRECTION = 0.15f

    private const val MEASURE_SIZE = 1000
    private const val MAX_POINTS = 40_000

    /** Measures the tilt of the page's text lines; null when the page has no clear lines. */
    fun measure(page: Bitmap): SkewEstimate? = withMats {
        val rgba = mat()
        Utils.bitmapToMat(page, rgba)
        var gray = mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val scale = MEASURE_SIZE.toDouble() / max(gray.cols(), gray.rows())
        if (scale < 1.0) {
            val small = mat()
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            gray = small
        }
        // dark strokes against the local background: text, rules and drawings
        val ink = mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        val points = mat()
        Core.findNonZero(ink, points)
        val total = points.rows()
        if (total == 0) return@withMats null
        val raw = IntArray(total * 2)
        points.get(0, 0, raw)
        // an even subsample keeps the profile shape and bounds the work
        val step = max(1, (total + MAX_POINTS - 1) / MAX_POINTS)
        val count = (total + step - 1) / step
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        for (k in 0 until count) {
            val i = k * step
            xs[k] = raw[2 * i].toFloat()
            ys[k] = raw[2 * i + 1].toFloat()
        }
        SkewEstimator.estimate(xs, ys, count)
    }

    /** Clockwise correction to apply automatically, 0 when the page should be left as it is. */
    fun autoCorrection(page: Bitmap): Float {
        val estimate = runCatching { measure(page) }.getOrNull() ?: return 0f
        return if (abs(estimate.correction) < MIN_CORRECTION) 0f else estimate.correction
    }

    /**
     * Rotates [page] clockwise by [degrees] around its centre, keeping its size so the sheet keeps
     * its paper proportions; the uncovered corners are filled with the paper colour (repeating the
     * border would smear any sliver of background along the edge into them). Returns [page] itself
     * when there is nothing to do.
     */
    fun rotate(page: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 0.01f) return page
        return withMats {
            val src = mat()
            Utils.bitmapToMat(page, src)
            val w = src.cols()
            val h = src.rows()
            // OpenCV angles are counter-clockwise
            val m: Mat = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), -degrees.toDouble(), 1.0).track()
            val dst = mat()
            Imgproc.warpAffine(
                src, dst, m, Size(w.toDouble(), h.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, paperColor(src),
            )
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, out)
            out
        }
    }

    /** Typical colour of the page margins: the per-channel median of a band just inside the edges. */
    private fun MatScope.paperColor(rgba: Mat): Scalar {
        val scale = 128.0 / max(rgba.cols(), rgba.rows())
        val small = mat()
        Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()
        val px = ByteArray(w * h * 4)
        small.get(0, 0, px)
        // skip the outermost pixels, which may still show the table the sheet lay on
        val outer = max(1, (minOf(w, h) * 0.03).toInt())
        val inner = max(outer + 1, (minOf(w, h) * 0.10).toInt())
        val channels = Array(3) { ArrayList<Int>() }
        for (y in outer until h - outer) {
            for (x in outer until w - outer) {
                val inBand = x < inner || y < inner || x >= w - inner || y >= h - inner
                if (!inBand) continue
                val i = (y * w + x) * 4
                for (c in 0..2) channels[c] += px[i + c].toInt() and 0xFF
            }
        }
        if (channels[0].isEmpty()) return Scalar(255.0, 255.0, 255.0, 255.0)
        val median = channels.map { it.sorted()[it.size / 2].toDouble() }
        return Scalar(median[0], median[1], median[2], 255.0)
    }

    /**
     * Applies the page's straightening: [skew] is a clockwise angle, or null to measure it. Returns
     * the straightened page (recycling [warped] when a new bitmap was made) and the angle used.
     */
    fun straighten(warped: Bitmap, skew: Float?): Pair<Bitmap, Float> {
        val angle = (skew ?: autoCorrection(warped)).coerceIn(-SkewEstimator.MAX_ANGLE * 3, SkewEstimator.MAX_ANGLE * 3)
        val out = rotate(warped, angle)
        if (out !== warped) warped.recycle()
        return out to angle
    }
}
