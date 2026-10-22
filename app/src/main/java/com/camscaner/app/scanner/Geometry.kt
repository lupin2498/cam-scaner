package com.camscaner.app.scanner

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A point. Corners are stored normalized to [0, 1] relative to the upright original image. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun distanceTo(o: Pt): Float = hypot(x - o.x, y - o.y)
    fun clamp(): Pt = Pt(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Four document corners in the order top-left, top-right, bottom-right, bottom-left. */
data class Corners(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: Pt): Corners = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    fun scaled(sx: Float, sy: Float): Corners =
        Corners(Pt(tl.x * sx, tl.y * sy), Pt(tr.x * sx, tr.y * sy), Pt(br.x * sx, br.y * sy), Pt(bl.x * sx, bl.y * sy))

    /** True when the polygon is convex and every interior angle is reasonable. */
    fun isValid(minAngle: Double = 20.0, maxAngle: Double = 160.0): Boolean {
        val p = points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return interiorAngles().all { it in minAngle..maxAngle }
    }

    fun interiorAngles(): List<Double> {
        val p = points
        return (0 until 4).map { i ->
            val prev = p[(i + 3) % 4]
            val cur = p[i]
            val next = p[(i + 1) % 4]
            val v1x = (prev.x - cur.x).toDouble()
            val v1y = (prev.y - cur.y).toDouble()
            val v2x = (next.x - cur.x).toDouble()
            val v2y = (next.y - cur.y).toDouble()
            val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-12)
            Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
        }
    }

    /** Shoelace area (in the same units as the points). */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun maxDistanceTo(o: Corners): Float = points.zip(o.points).maxOf { (a, b) -> a.distanceTo(b) }

    fun toFloatArray(): FloatArray = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)

    companion object {
        val FULL = Corners(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        fun fromFloatArray(a: FloatArray): Corners =
            Corners(Pt(a[0], a[1]), Pt(a[2], a[3]), Pt(a[4], a[5]), Pt(a[6], a[7]))

        /** Orders four arbitrary points clockwise starting from the top-left one. */
        fun ordered(pts: List<Pt>): Corners {
            require(pts.size == 4)
            val cx = pts.sumOf { it.x.toDouble() } / 4
            val cy = pts.sumOf { it.y.toDouble() } / 4
            val sorted = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Corners(r[0], r[1], r[2], r[3])
        }
    }
}

/**
 * Estimates the real width/height ratio of a rectangle photographed in perspective
 * (Zhang & He, "Whiteboard scanning and image enhancement", 2007).
 *
 * [c] is in pixels of an image of size [imageWidth] x [imageHeight]; the principal point is
 * assumed to be the image center. Falls back to the visible ratio when the estimate is unstable.
 */
fun estimateAspectRatio(c: Corners, imageWidth: Int, imageHeight: Int): Double {
    val visW = max(c.tl.distanceTo(c.tr), c.bl.distanceTo(c.br)).toDouble()
    val visH = max(c.tl.distanceTo(c.bl), c.tr.distanceTo(c.br)).toDouble()
    if (visW <= 0 || visH <= 0) return 1.0
    val visible = visW / visH

    val u0 = imageWidth / 2.0
    val v0 = imageHeight / 2.0
    val m1 = doubleArrayOf(c.tl.x.toDouble(), c.tl.y.toDouble(), 1.0)
    val m2 = doubleArrayOf(c.tr.x.toDouble(), c.tr.y.toDouble(), 1.0)
    val m3 = doubleArrayOf(c.bl.x.toDouble(), c.bl.y.toDouble(), 1.0)
    val m4 = doubleArrayOf(c.br.x.toDouble(), c.br.y.toDouble(), 1.0)

    val d2 = dot(cross(m2, m4), m3)
    val d3 = dot(cross(m3, m4), m2)
    if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return visible
    val k2 = dot(cross(m1, m4), m3) / d2
    val k3 = dot(cross(m1, m4), m2) / d3
    val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
    val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

    // The focal length follows from the vanishing points, but becomes meaningless when a pair of
    // sides is (nearly) parallel: with a few pixels of corner noise it comes out many times too
    // large or negative. Then a typical phone focal length is used in the same formula, which
    // stays valid in the parallel case.
    val longSide = max(imageWidth, imageHeight).toDouble()
    var f = Double.NaN
    if (abs(n2[2]) > 1e-6 && abs(n3[2]) > 1e-6) {
        val f2 = -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
            (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)) / (n2[2] * n3[2])
        if (f2 > 0) f = sqrt(f2)
    }
    if (f.isNaN() || f < MIN_FOCAL * longSide || f > MAX_FOCAL * longSide) f = NOMINAL_FOCAL * longSide
    fun quad(n: DoubleArray): Double {
        val x = (n[0] - u0 * n[2]) / f
        val y = (n[1] - v0 * n[2]) / f
        return x * x + y * y + n[2] * n[2]
    }
    val q3 = quad(n3)
    val ratio = if (q3 > 0) sqrt(quad(n2) / q3) else Double.NaN
    if (ratio.isNaN() || ratio < 0.2 || ratio > 5.0 || abs(ratio / visible - 1) > 0.6) return visible
    return ratio
}

// Focal length range accepted from the estimate, and the fallback, relative to the image's long
// side. Phone main cameras are around 0.7-1.0; a wider range lets corner noise through.
private const val MIN_FOCAL = 0.55
private const val MAX_FOCAL = 1.5
private const val NOMINAL_FOCAL = 0.8

/** Output size (width, height) in pixels for a perspective-corrected page with the given real ratio. */
fun correctedSize(c: Corners, ratio: Double, maxDim: Int): Pair<Int, Int> {
    val visW = max(c.tl.distanceTo(c.tr), c.bl.distanceTo(c.br)).toDouble()
    val visH = max(c.tl.distanceTo(c.bl), c.tr.distanceTo(c.br)).toDouble()
    var w: Double
    var h: Double
    if (ratio < visW / visH) {
        w = visW; h = w / ratio
    } else {
        h = visH; w = h * ratio
    }
    val s = minOf(1.0, maxDim / max(w, h))
    w *= s; h *= s
    return Pair(max(1, Math.round(w).toInt()), max(1, Math.round(h).toInt()))
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
)

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
