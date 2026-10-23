package com.camscaner.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.ocr.AutoAligner
import com.camscaner.app.scanner.ImageEnhancer
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.PageAligner
import com.camscaner.app.scanner.PageProcessor
import com.camscaner.app.scanner.ScanFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class PipelineTest {

    @Test
    fun detectsTiltedSheet() {
        for ((rx, ry) in listOf(0.3 to 0.18, -0.25 to 0.1, 0.1 to -0.3, 0.0 to 0.0)) {
            val scene = TestImages.documentPhoto(rx = rx, ry = ry)
            val found = DocumentDetector.detect(scene.bitmap)
            assertNotNull("no document for tilt ($rx, $ry)", found)
            val error = found!!.maxDistanceTo(scene.corners)
            assertTrue("corner error $error for tilt ($rx, $ry)", error < 0.02f)
        }
    }

    @Test
    fun detectsCardAndLandscapeSheet() {
        val card = TestImages.documentPhoto(paperRatio = 85.6 / 53.98, rx = 0.2, ry = 0.1, distance = 2.6)
        val found = DocumentDetector.detect(card.bitmap)
        assertNotNull(found)
        assertTrue(found!!.maxDistanceTo(card.corners) < 0.02f)
    }

    @Test
    fun emptyTableHasNoDocument() {
        val bmp = android.graphics.Bitmap.createBitmap(800, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(90, 80, 70))
        assertEquals(null, DocumentDetector.detect(bmp))
    }

    @Test
    fun warpRestoresA4Proportions() {
        val scene = TestImages.documentPhoto(rx = 0.35, ry = 0.2)
        val corners = DocumentDetector.detect(scene.bitmap)!!
        val page = PageProcessor.render(scene.bitmap, corners, 0, ScanFilter.ORIGINAL, Adjustments())
        val ratio = page.width.toDouble() / page.height
        assertEquals(1 / sqrt(2.0), ratio, 0.01)
        val rotated = PageProcessor.render(scene.bitmap, corners, 90, ScanFilter.MAGIC, Adjustments())
        assertEquals(page.width, rotated.height)
        assertEquals(page.height, rotated.width)
    }

    @Test
    fun straightenLevelsTiltedPage() {
        val page = TestImages.flatPage()
        for (tilt in listOf(-6f, 2.5f, 11f)) {
            val tilted = PageAligner.rotate(page, tilt)
            val estimate = PageAligner.measure(tilted)
            assertNotNull("no lines found for tilt $tilt", estimate)
            // a page turned clockwise is corrected counter-clockwise
            assertEquals("tilt $tilt", -tilt, estimate!!.correction, 0.3f)
            assertFalse(estimate.sideways)
            val fixed = PageAligner.rotate(tilted, estimate.correction)
            assertEquals("after fixing $tilt", 0f, PageAligner.autoCorrection(fixed), 0.3f)
        }
    }

    @Test
    fun straightenIgnoresPagesWithoutText() {
        val photo = android.graphics.Bitmap.createBitmap(900, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.rgb(200, 190, 180))
        assertEquals(0f, PageAligner.autoCorrection(photo), 0f)
    }

    @Test
    fun autoAlignTurnsPagesUpright() = runBlocking {
        val page = TestImages.flatPage()
        for (turn in listOf(0, 90, 180, 270)) {
            // the page as it comes out of the warp when photographed turned clockwise
            val turned = ImageIO.rotate(page.copy(android.graphics.Bitmap.Config.ARGB_8888, false), turn)
            val alignment = AutoAligner.analyze(turned)
            assertEquals("page turned by $turn", (360 - turn) % 360, alignment.rotation)
        }
    }

    @Test
    fun renderAppliesStraightening() {
        val page = TestImages.flatPage()
        val tilted = PageAligner.rotate(page, 5f)
        val auto = PageProcessor.render(tilted, null, 0, ScanFilter.ORIGINAL, Adjustments(), 1400, skew = null)
        assertEquals(0f, PageAligner.autoCorrection(auto), 0.4f)
        // a manual angle is applied as given; 0 keeps the page as it is
        val kept = PageProcessor.render(tilted, null, 0, ScanFilter.ORIGINAL, Adjustments(), 1400, skew = 0f)
        assertEquals(-5f, PageAligner.measure(kept)!!.correction, 0.4f)
    }

    @Test
    fun everyFilterProducesAnImage() {
        val scene = TestImages.documentPhoto()
        val corners = DocumentDetector.detect(scene.bitmap)!!
        val warped = PageProcessor.warp(scene.bitmap, corners, 1400)
        for (filter in ScanFilter.entries) {
            for (adj in listOf(Adjustments(), Adjustments(0.3f, 0.4f, 0.5f), Adjustments(-0.5f, -0.5f, 0f))) {
                val out = ImageEnhancer.enhance(warped, filter, adj)
                assertEquals(warped.width, out.width)
                assertEquals(warped.height, out.height)
                out.recycle()
            }
        }
    }

    @Test
    fun magicFilterWhitensPaperAndKeepsColor() {
        val scene = TestImages.documentPhoto(rx = 0.0, ry = 0.0)
        val corners = DocumentDetector.detect(scene.bitmap)!!
        val warped = PageProcessor.warp(scene.bitmap, corners, 1400)
        val out = ImageEnhancer.enhance(warped, ScanFilter.MAGIC)
        // paper margin (top-left area, away from text) should be near white even under the shadow
        val margin = out.getPixel((out.width * 0.03).toInt(), (out.height * 0.97).toInt())
        assertTrue("paper under shadow not white: ${Integer.toHexString(margin)}", Color.red(margin) > 225 && Color.green(margin) > 225)
        // the red block stays red
        val red = out.getPixel((out.width * 0.7).toInt(), (out.height * 0.36).toInt())
        assertTrue("red block lost: ${Integer.toHexString(red)}", Color.red(red) > 150 && Color.green(red) < 110)
    }

    @Test
    fun blackAndWhiteIsBinary() {
        val scene = TestImages.documentPhoto()
        val warped = PageProcessor.warp(scene.bitmap, DocumentDetector.detect(scene.bitmap), 1200)
        val out = ImageEnhancer.enhance(warped, ScanFilter.BW)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        val gray = pixels.count { val r = Color.red(it); r in 1..254 }
        assertEquals(0, gray)
        val black = pixels.count { Color.red(it) == 0 }
        val fraction = black.toDouble() / pixels.size
        assertTrue("black fraction $fraction", fraction in 0.02..0.4)
    }

    @Test
    fun fullImageWhenNoCorners() {
        val scene = TestImages.documentPhoto()
        val page = PageProcessor.render(scene.bitmap, null, 0, ScanFilter.ORIGINAL, Adjustments(), 1000)
        assertTrue(abs(page.width.toDouble() / page.height - scene.bitmap.width.toDouble() / scene.bitmap.height) < 0.01)
    }
}
