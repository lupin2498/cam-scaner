package com.camscaner.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMarkTest {

    @Test
    fun rotatedFollowsThePageAndTurnsTheText() {
        val t = TextMark("Xin chào", 0.2f, 0.3f, 0.04f, 0xFF111111.toInt())
        val quarter = t.rotated(90)
        assertEquals(0.7f, quarter.cx, 1e-6f)
        assertEquals(0.2f, quarter.cy, 1e-6f)
        assertEquals(90, quarter.angle)
        val full = t.rotated(90).rotated(90).rotated(90).rotated(90)
        assertEquals(t.cx, full.cx, 1e-6f)
        assertEquals(t.cy, full.cy, 1e-6f)
        assertEquals(0, full.angle)
        assertEquals(t.rotated(270), t.rotated(-90))
        assertEquals(t, t.rotated(0))
    }

    @Test
    fun boxSwapsSidesWhenTurned() {
        val t = TextMark("ab\ncd", 0.5f, 0.5f, 0.05f)
        val (w, h) = t.box(1000f, 1000f) { s, px -> s.length * px * 0.5f }
        // two lines of two letters at 50 px: 50 px wide, 125 px tall
        assertEquals(0.05f, w, 1e-6f)
        assertEquals(0.125f, h, 1e-6f)
        val (tw, th) = t.rotated(90).box(1000f, 1000f) { s, px -> s.length * px * 0.5f }
        assertEquals(0.125f, tw, 1e-6f)
        assertEquals(0.05f, th, 1e-6f)
    }
}
