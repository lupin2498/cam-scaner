package com.camscaner.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeometryTest {

    @Test
    fun orderedReturnsClockwiseFromTopLeft() {
        val tl = Pt(10f, 12f)
        val tr = Pt(90f, 8f)
        val br = Pt(95f, 120f)
        val bl = Pt(5f, 115f)
        val c = Corners.ordered(listOf(br, tl, bl, tr))
        assertEquals(Corners(tl, tr, br, bl), c)
    }

    @Test
    fun convexQuadIsValidAndBowTieIsNot() {
        assertTrue(Corners.FULL.isValid())
        val bowTie = Corners(Pt(0f, 0f), Pt(1f, 1f), Pt(1f, 0f), Pt(0f, 1f))
        assertFalse(bowTie.isValid())
        val sliver = Corners(Pt(0f, 0f), Pt(1f, 0f), Pt(0.5f, 0.02f), Pt(0f, 0.01f))
        assertFalse(sliver.isValid())
    }

    @Test
    fun areaOfUnitSquare() {
        assertEquals(1f, Corners.FULL.area(), 1e-6f)
    }

    @Test
    fun aspectRatioOfFrontalRectangleIsVisibleRatio() {
        val c = Corners(Pt(100f, 100f), Pt(400f, 100f), Pt(400f, 524f), Pt(100f, 524f))
        assertEquals(300.0 / 424.0, estimateAspectRatio(c, 1000, 800), 1e-3)
    }

    @Test
    fun aspectRatioRecoveredUnderPerspective() {
        // A4 sheet seen by a pinhole camera tilted around two axes.
        val ratio = 1.0 / sqrt(2.0)
        val w = 4000
        val h = 3000
        val f = 3300.0
        for ((rx, ry) in listOf(0.35 to 0.2, -0.3 to 0.25, 0.25 to -0.35)) {
            val pts = listOf(-ratio / 2 to -0.5, ratio / 2 to -0.5, ratio / 2 to 0.5, -ratio / 2 to 0.5).map { (x, y) ->
                // rotate around X then Y, push 2.2 units away
                val y1 = y * cos(rx)
                val z1 = y * sin(rx)
                val x2 = x * cos(ry) + z1 * sin(ry)
                val z2 = -x * sin(ry) + z1 * cos(ry) + 2.2
                Pt((f * x2 / z2 + w / 2).toFloat(), (f * y1 / z2 + h / 2).toFloat())
            }
            val c = Corners(pts[0], pts[1], pts[2], pts[3])
            val est = estimateAspectRatio(c, w, h)
            assertTrue("estimated $est for tilt ($rx, $ry)", abs(est / ratio - 1) < 0.02)
        }
    }

    @Test
    fun aspectRatioStableForForwardTiltWithCornerNoise() {
        // The most common handheld shot: tilted forward only, so the left and right sides
        // converge and the top and bottom stay parallel; corners carry a few pixels of error.
        val ratio = 1.0 / sqrt(2.0)
        val w = 3000
        val h = 4000
        val random = java.util.Random(42)
        var bad = 0
        val runs = 300
        repeat(runs) {
            val tilt = Math.toRadians(5.0 + 20.0 * random.nextDouble()) * (if (random.nextBoolean()) 1 else -1)
            val f = h * (0.75 + 0.2 * random.nextDouble())
            val distance = f / h * 1.9
            fun noise() = (random.nextDouble() * 12 - 6).toFloat()
            val pts = listOf(-ratio / 2 to -0.5, ratio / 2 to -0.5, ratio / 2 to 0.5, -ratio / 2 to 0.5).map { (x, y) ->
                val y1 = y * cos(tilt)
                val z = y * sin(tilt) + distance
                Pt((f * x / z + w / 2).toFloat() + noise(), (f * y1 / z + h / 2).toFloat() + noise())
            }
            val est = estimateAspectRatio(Corners(pts[0], pts[1], pts[2], pts[3]), w, h)
            if (abs(est / ratio - 1) > 0.05) bad++
        }
        assertTrue("$bad of $runs estimates off by more than 5%", bad <= runs / 10)
    }

    @Test
    fun correctedSizeRespectsMaxDimension() {
        val c = Corners(Pt(0f, 0f), Pt(3000f, 0f), Pt(3000f, 4000f), Pt(0f, 4000f))
        val (w, h) = correctedSize(c, 0.75, 2000)
        assertEquals(1500, w)
        assertEquals(2000, h)
    }

    @Test
    fun surfaceFitIgnoresDarkOutliers() {
        val n = 20
        val values = FloatArray(n * n) { i ->
            val x = (i % n) / (n - 1f)
            200f + 40f * x
        }
        // a dark block (e.g. a photo) must not pull the illumination model down
        for (y in 5 until 12) for (x in 5 until 12) values[y * n + x] = 60f
        val fit = SurfaceFit.fit(values, n, n)
        val center = fit[8 * n + 8]
        val expected = 200f + 40f * (8 / (n - 1f))
        assertEquals(expected, center, 4f)
    }
}
