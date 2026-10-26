package com.camscaner.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PaperFitTest {

    private fun assertPt(expected: Pt, actual: Pt, eps: Float = 1e-4f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    /** Distance of [p] from the line through [a] and [b]. */
    private fun lineDistance(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return abs(dx * (a.y - p.y) - dy * (a.x - p.x)) / sqrt(dx * dx + dy * dy)
    }

    /** True when [p] lies inside (or on the edge of) the convex quadrilateral [q]. */
    private fun contains(q: Corners, p: Pt): Boolean {
        val pts = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (cross > 1e-6f) 1 else if (cross < -1e-6f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    @Test
    fun homographyMapsTheUnitSquareOntoTheQuad() {
        val q = Corners(Pt(0.1f, 0.2f), Pt(0.85f, 0.12f), Pt(0.9f, 0.88f), Pt(0.05f, 0.95f))
        val h = Homography.squareTo(q)
        assertPt(q.tl, h.map(0.0, 0.0))
        assertPt(q.tr, h.map(1.0, 0.0))
        assertPt(q.br, h.map(1.0, 1.0))
        assertPt(q.bl, h.map(0.0, 1.0))
        // edges stay edges and the centre of the sheet is where the diagonals cross
        assertTrue(lineDistance(h.map(0.3, 0.0), q.tl, q.tr) < 1e-4f)
        assertTrue(lineDistance(h.map(1.0, 0.6), q.tr, q.br) < 1e-4f)
        val c = h.map(0.5, 0.5)
        assertTrue(lineDistance(c, q.tl, q.br) < 1e-4f)
        assertTrue(lineDistance(c, q.tr, q.bl) < 1e-4f)
        // a parallelogram is mapped affinely: its centre is the mean of its corners
        val p = Corners(Pt(0.2f, 0.1f), Pt(0.8f, 0.2f), Pt(0.7f, 0.9f), Pt(0.1f, 0.8f))
        assertPt(Pt(0.45f, 0.5f), Homography.squareTo(p).map(0.5, 0.5))
    }

    @Test
    fun fitInsideAFrontalPhotoIsCentredWithTheFormatRatio() {
        // the whole 3:4 photo as the sheet: A4 is narrower, so the height is kept
        val a4 = PaperFit.fit(null, PaperPreset.A4, 3000, 4000)
        val w = (a4.tr.x - a4.tl.x) * 3000.0
        val h = (a4.bl.y - a4.tl.y) * 4000.0
        assertEquals(1 / sqrt(2.0), w / h, 1e-3)
        assertEquals(4000.0, h, 1e-3)
        assertEquals(a4.tl.x, 1f - a4.tr.x, 1e-5f)
        assertTrue(a4.isValid())
        // a square is wider, so the width is kept and the height reduced
        val sq = PaperFit.fit(null, PaperPreset.SQUARE, 3000, 4000)
        val ws = (sq.tr.x - sq.tl.x) * 3000.0
        val hs = (sq.bl.y - sq.tl.y) * 4000.0
        assertEquals(1.0, ws / hs, 1e-3)
        assertEquals(3000.0, ws, 1e-3)
        assertEquals(sq.tl.y, 1f - sq.bl.y, 1e-5f)
    }

    @Test
    fun fitKeepsTheOrientationOfTheSheet() {
        val landscape = Corners(Pt(0.1f, 0.3f), Pt(0.9f, 0.3f), Pt(0.9f, 0.7f), Pt(0.1f, 0.7f))
        val c = PaperFit.fit(landscape, PaperPreset.A4, 4000, 3000)
        val w = (c.tr.x - c.tl.x) * 4000.0
        val h = (c.bl.y - c.tl.y) * 3000.0
        assertEquals(sqrt(2.0), w / h, 1e-3)
        // the region stays inside the sheet, centred on it
        assertTrue(c.tl.x >= 0.1f - 1e-5f && c.tr.x <= 0.9f + 1e-5f)
        assertEquals(0.5f, (c.tl.y + c.bl.y) / 2f, 1e-5f)
        // an ID card held upright comes out portrait
        val upright = Corners(Pt(0.2f, 0.1f), Pt(0.8f, 0.1f), Pt(0.8f, 0.9f), Pt(0.2f, 0.9f))
        val card = PaperFit.fit(upright, PaperPreset.ID_CARD, 3000, 4000)
        val cw = (card.tr.x - card.tl.x) * 3000.0
        val ch = (card.bl.y - card.tl.y) * 4000.0
        assertEquals(53.98 / 85.6, cw / ch, 1e-3)
    }

    @Test
    fun fitUnderPerspectiveHasTheFormatRatio() {
        // an A4 sheet seen by a tilted pinhole camera (as in GeometryTest)
        val ratio = 1.0 / sqrt(2.0)
        val w = 3000
        val h = 4000
        val f = 3300.0
        for ((rx, ry) in listOf(0.35 to 0.2, -0.3 to 0.25, 0.25 to -0.35)) {
            val pts = listOf(-ratio / 2 to -0.5, ratio / 2 to -0.5, ratio / 2 to 0.5, -ratio / 2 to 0.5).map { (x, y) ->
                val y1 = y * cos(rx)
                val z1 = y * sin(rx)
                val x2 = x * cos(ry) + z1 * sin(ry)
                val z2 = -x * sin(ry) + z1 * cos(ry) + 2.2
                Pt((f * x2 / z2 + w / 2).toFloat() / w, (f * y1 / z2 + h / 2).toFloat() / h)
            }
            val sheet = Corners(pts[0], pts[1], pts[2], pts[3])
            // the same format leaves the sheet as it is
            val same = PaperFit.fit(sheet, PaperPreset.A5, w, h)
            assertTrue("A5 moved the outline", same.maxDistanceTo(sheet) < 0.02f)
            // a square inside the sheet measures as a square
            val square = PaperFit.fit(sheet, PaperPreset.SQUARE, w, h)
            val measured = estimateAspectRatio(square.scaled(w.toFloat(), h.toFloat()), w, h)
            assertTrue("square measured $measured for tilt ($rx, $ry)", abs(measured - 1.0) < 0.06)
            assertTrue(square.isValid())
            // and lies inside the sheet
            square.points.forEach { assertTrue("corner $it outside the sheet", contains(sheet, it)) }
        }
    }

    @Test
    fun splitHalvesShareTheMiddleEdge() {
        val (left, right) = PaperFit.split(null)
        assertPt(Pt(0f, 0f), left.tl)
        assertPt(Pt(0.5f, 0f), left.tr)
        assertPt(Pt(0.5f, 1f), left.br)
        assertPt(Pt(0.5f, 0f), right.tl)
        assertPt(Pt(1f, 1f), right.br)
        val spread = Corners(Pt(0.1f, 0.2f), Pt(0.9f, 0.15f), Pt(0.95f, 0.85f), Pt(0.05f, 0.9f))
        val (l, r) = PaperFit.split(spread)
        assertPt(l.tr, r.tl)
        assertPt(l.br, r.bl)
        assertPt(spread.tl, l.tl)
        assertPt(spread.br, r.br)
        val (top, bottom) = PaperFit.split(spread, verticalSplit = true)
        assertPt(top.bl, bottom.tl)
        assertPt(top.br, bottom.tr)
    }

    @Test
    fun guideFitsThePreview() {
        val a4 = PaperFit.guide(PaperPreset.A4, 1080f, 1920f)
        val w = (a4.tr.x - a4.tl.x) * 1080
        val h = (a4.bl.y - a4.tl.y) * 1920
        assertEquals(1080 * 0.86f, w, 0.5f)
        assertEquals(1 / sqrt(2.0), w.toDouble() / h, 1e-3)
        assertEquals(a4.tl.x, 1f - a4.tr.x, 1e-5f)
        // cards lie across a portrait preview
        val card = PaperFit.guide(PaperPreset.ID_CARD, 1080f, 1920f)
        val cw = (card.tr.x - card.tl.x) * 1080
        val ch = (card.bl.y - card.tl.y) * 1920
        assertEquals(85.6 / 53.98, cw.toDouble() / ch, 1e-3)
        // a format taller than the preview is limited by the height
        val legal = PaperFit.guide(PaperPreset.LEGAL, 1080f, 1200f)
        assertEquals(1200 * 0.86f, (legal.bl.y - legal.tl.y) * 1200, 0.5f)
    }

    @Test
    fun ratioFollowsTheOrientationOfTheRegion() {
        // real sheets are rounded to millimetres, so A4 is 210:297 rather than exactly 1:√2
        assertEquals(210.0 / 297.0, PaperPreset.A4.ratioFor(0.7), 1e-6)
        assertEquals(297.0 / 210.0, PaperPreset.A4.ratioFor(1.4), 1e-6)
        assertEquals(1 / sqrt(2.0), PaperPreset.A4.ratio, 1e-4)
        assertEquals(53.98 / 85.6, PaperPreset.ID_CARD.ratio, 1e-6)
        assertEquals(595.28f, PaperPreset.A4.widthPt, 0.05f)
        assertEquals(841.89f, PaperPreset.A4.heightPt, 0.05f)
        assertEquals(PaperPreset.LETTER, PaperPreset.fromName("LETTER"))
        assertEquals(null, PaperPreset.fromName("nope"))
    }
}
