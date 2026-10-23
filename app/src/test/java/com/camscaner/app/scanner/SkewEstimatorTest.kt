package com.camscaner.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class SkewEstimatorTest {

    /**
     * Ink points of a text page (lines of "words" 40 px apart on a 700 x 1000 px page), turned
     * clockwise on screen by [degrees] around the page centre; [sideways] lays the page on its side.
     */
    private fun page(degrees: Double, sideways: Boolean = false, seed: Long = 1): Pair<FloatArray, FloatArray> {
        val random = Random(seed)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var y = 60.0
        while (y < 940) {
            var x = 50.0
            val end = 400 + random.nextInt(250)
            while (x < end) {
                val word = 20 + random.nextInt(60)
                for (k in 0 until word * 3) {
                    // letters fill a band about 14 px high on the line
                    xs += (x + random.nextDouble() * word).toFloat()
                    ys += (y + random.nextDouble() * 14).toFloat()
                }
                x += word + 12
            }
            y += 40
        }
        val t = Math.toRadians(degrees)
        val outX = FloatArray(xs.size)
        val outY = FloatArray(xs.size)
        for (i in xs.indices) {
            var px = xs[i] - 350.0
            var py = ys[i] - 500.0
            if (sideways) px = py.also { py = -px }
            // clockwise on screen with y pointing down
            outX[i] = (px * cos(t) - py * sin(t) + 700).toFloat()
            outY[i] = (px * sin(t) + py * cos(t) + 700).toFloat()
        }
        return outX to outY
    }

    @Test
    fun levelPageNeedsNoCorrection() {
        val (xs, ys) = page(0.0)
        val e = SkewEstimator.estimate(xs, ys)
        assertNotNull(e)
        assertEquals(0f, e!!.correction, 0.1f)
        assertFalse(e.sideways)
        assertTrue("confidence ${e.confidence}", e.confidence > 1.5f)
    }

    @Test
    fun tiltIsMeasuredWithItsDirection() {
        for (tilt in listOf(-12.0, -4.0, -0.8, 1.5, 7.0)) {
            val (xs, ys) = page(tilt)
            val e = SkewEstimator.estimate(xs, ys)!!
            // a page turned clockwise is corrected counter-clockwise
            assertEquals("tilt $tilt", -tilt.toFloat(), e.correction, 0.15f)
            assertFalse(e.sideways)
        }
    }

    @Test
    fun sidewaysPagesAreRecognised() {
        for (tilt in listOf(-5.0, 0.0, 3.0)) {
            val (xs, ys) = page(tilt, sideways = true)
            val e = SkewEstimator.estimate(xs, ys)!!
            assertTrue("tilt $tilt", e.sideways)
            assertEquals("tilt $tilt", -tilt.toFloat(), e.correction, 0.15f)
        }
    }

    @Test
    fun pagesWithoutLinesAreLeftAlone() {
        val random = Random(7)
        val n = 20_000
        val xs = FloatArray(n) { random.nextFloat() * 700 }
        val ys = FloatArray(n) { random.nextFloat() * 1000 }
        assertNull(SkewEstimator.estimate(xs, ys))
        assertNull(SkewEstimator.estimate(FloatArray(10), FloatArray(10)))
    }
}
