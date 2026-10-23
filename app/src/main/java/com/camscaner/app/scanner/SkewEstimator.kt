package com.camscaner.app.scanner

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** How much the lines of a page are tilted. */
data class SkewEstimate(
    /** Clockwise rotation in degrees that makes the lines level. */
    val correction: Float,
    /** The lines run top to bottom: the page lies on its side. */
    val sideways: Boolean,
    /** Sharpness of the best projection relative to a typical one; text pages score about 2. */
    val confidence: Float,
)

/**
 * Finds the dominant line direction of a page from its ink pixels with projection profiles: when
 * the points are projected across the lines at the right angle, each text line or table rule
 * falls into a few bins, which maximizes the sum of squared bin counts.
 */
object SkewEstimator {

    /** Tilts beyond this are not text skew but a crooked crop or a rotated page. */
    const val MAX_ANGLE = 15f

    /** Below this the profile has no clear lines (photo, noise, blank page). */
    const val MIN_CONFIDENCE = 1.35f

    private const val MIN_POINTS = 200
    private const val COARSE_STEP = 0.5f
    private const val FINE_STEP = 0.05f
    private const val BIN = 2f

    /**
     * Estimates the tilt from ink pixel coordinates (in pixels, any order), or returns null when
     * the page has no clear lines.
     */
    fun estimate(xs: FloatArray, ys: FloatArray, count: Int = xs.size): SkewEstimate? {
        if (count < MIN_POINTS) return null
        val horizontal = search(xs, ys, count)
        // swapping the axes measures lines running top to bottom
        val vertical = search(ys, xs, count)
        val sideways = vertical.confidence > horizontal.confidence
        val best = if (sideways) vertical else horizontal
        if (best.confidence < MIN_CONFIDENCE) return null
        // The projection angle that levels the lines is the counter-clockwise correction; swapping
        // the axes mirrors the image, which reverses the direction.
        val clockwise = if (sideways) best.angle else -best.angle
        return SkewEstimate(clockwise, sideways, best.confidence)
    }

    private class Search(val angle: Float, val confidence: Float)

    private fun search(xs: FloatArray, ys: FloatArray, count: Int): Search {
        var maxX = 0f
        var maxY = 0f
        for (i in 0 until count) {
            maxX = max(maxX, xs[i])
            maxY = max(maxY, ys[i])
        }
        val bins = IntArray(((maxY + 2 * maxX) / BIN).toInt() + 4)
        fun score(degrees: Float): Double {
            val t = Math.toRadians(degrees.toDouble())
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()
            bins.fill(0)
            for (i in 0 until count) {
                // y·cos − x·sin is constant along a line tilted by the angle; offset keeps it ≥ 0
                val p = ys[i] * c - xs[i] * s + maxX
                bins[(p / BIN).toInt()]++
            }
            var sum = 0.0
            for (b in bins) if (b != 0) sum += b.toDouble() * b
            return sum
        }

        val steps = (2 * MAX_ANGLE / COARSE_STEP).toInt()
        val coarse = DoubleArray(steps + 1) { score(-MAX_ANGLE + it * COARSE_STEP) }
        var bestIndex = 0
        for (i in coarse.indices) if (coarse[i] > coarse[bestIndex]) bestIndex = i
        val median = coarse.sorted()[coarse.size / 2]
        var bestAngle = -MAX_ANGLE + bestIndex * COARSE_STEP
        var bestScore = coarse[bestIndex]
        val fineSteps = (COARSE_STEP / FINE_STEP).toInt()
        val center = bestAngle
        for (k in -fineSteps..fineSteps) {
            val a = center + k * FINE_STEP
            val s = score(a)
            if (s > bestScore) {
                bestScore = s
                bestAngle = a
            }
        }
        val confidence = if (median > 0) (coarse[bestIndex] / median).toFloat() else 0f
        return Search(bestAngle, confidence)
    }
}
