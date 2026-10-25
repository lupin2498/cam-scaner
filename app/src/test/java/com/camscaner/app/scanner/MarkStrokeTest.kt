package com.camscaner.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkStrokeTest {

    private fun stroke(vararg p: Float, group: Int = 0) = MarkStroke(p.toList(), 0, 0.004f, group = group)

    @Test
    fun rotatedFollowsThePage() {
        val s = stroke(0.1f, 0.2f, 0.7f, 0.9f)
        // a quarter turn clockwise moves the top-left corner of the page to the top-right
        assertPoints(listOf(0.8f, 0.1f, 0.1f, 0.7f), s.rotated(90).points)
        assertPoints(listOf(0.9f, 0.8f, 0.3f, 0.1f), s.rotated(180).points)
        assertPoints(listOf(0.2f, 0.9f, 0.9f, 0.3f), s.rotated(270).points)
        assertPoints(s.rotated(270).points, s.rotated(-90).points)
        assertPoints(s.points, s.rotated(90).rotated(90).rotated(90).rotated(90).points)
        assertEquals(s, s.rotated(0))
    }

    @Test
    fun undoRemovesTheLastStrokeOrTheWholeSignature() {
        val pen = stroke(0.1f, 0.1f)
        val sig1 = stroke(0.5f, 0.5f, group = 1)
        val sig2 = stroke(0.6f, 0.5f, group = 1)
        val pen2 = stroke(0.2f, 0.2f)
        assertEquals(listOf(pen, sig1, sig2), MarkStroke.undo(listOf(pen, sig1, sig2, pen2)))
        assertEquals(listOf(pen), MarkStroke.undo(listOf(pen, sig1, sig2)))
        assertEquals(emptyList<MarkStroke>(), MarkStroke.undo(listOf(pen)))
        assertEquals(emptyList<MarkStroke>(), MarkStroke.undo(emptyList()))
    }

    @Test
    fun nextGroupIsUnused() {
        assertEquals(1, MarkStroke.nextGroup(emptyList()))
        assertEquals(1, MarkStroke.nextGroup(listOf(stroke(0f, 0f))))
        assertEquals(4, MarkStroke.nextGroup(listOf(stroke(0f, 0f, group = 3), stroke(0f, 0f, group = 1))))
    }

    private fun assertPoints(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-6f) }
    }
}
