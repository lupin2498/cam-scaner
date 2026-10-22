package com.camscaner.app.ui.camera

import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityTrackerTest {

    private fun page(dx: Float = 0f) = Corners(Pt(0.1f + dx, 0.1f), Pt(0.8f + dx, 0.1f), Pt(0.8f + dx, 0.9f), Pt(0.1f + dx, 0.9f))

    /** Feeds [corners] every 80 ms from [start] for [millis]; returns the last progress and time. */
    private fun StabilityTracker.feed(corners: Corners?, start: Long, millis: Long): Pair<Float, Long> {
        var t = start
        var p = 0f
        while (t <= start + millis) {
            p = update(corners, t)
            t += 80
        }
        return p to t
    }

    @Test
    fun steadyPageTriggersAfterHold() {
        val tracker = StabilityTracker(1200)
        val (p, _) = tracker.feed(page(), 0, 1400)
        assertEquals(1f, p, 0f)
    }

    @Test
    fun droppedFramesAfterCaptureDoNotCaptureTheSamePageAgain() {
        val tracker = StabilityTracker(1200)
        val (p, t) = tracker.feed(page(), 0, 1400)
        assertEquals(1f, p, 0f)
        tracker.disarm()
        // focus hunting during the capture loses the outline for a frame
        tracker.update(null, t)
        val (after, _) = tracker.feed(page(), t + 80, 5000)
        assertEquals(0f, after, 0f)
    }

    @Test
    fun nextPageOrRemovedPageRearms() {
        val tracker = StabilityTracker(1200)
        val (_, t) = tracker.feed(page(), 0, 1400)
        tracker.disarm()
        // a different page position
        val (moved, t2) = tracker.feed(page(dx = 0.15f), t, 1400)
        assertEquals(1f, moved, 0f)
        tracker.disarm()
        // the page is taken away for a while, then put back
        val (_, t3) = tracker.feed(null, t2, 800)
        val (back, _) = tracker.feed(page(dx = 0.15f), t3, 1400)
        assertTrue(back >= 1f)
    }
}
