package com.camscaner.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.ocr.AutoAligner
import com.camscaner.app.data.ScanPage
import com.camscaner.app.scanner.ImageEnhancer
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.MarkStroke
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
    fun enhanceWhitensWarmShadowedPaperAndKeepsColor() {
        val shaded = TestImages.warmShadow(TestImages.flatPage())
        val out = ImageEnhancer.enhance(shaded, ScanFilter.ENHANCE)
        // blank paper in the lamp light and deep in the hand's shadow
        for ((fx, fy) in listOf(0.03 to 0.05, 0.97 to 0.5, 0.03 to 0.97, 0.5 to 0.985)) {
            val px = out.getPixel((out.width * fx).toInt(), (out.height * fy).toInt())
            assertTrue(
                "paper at ($fx, $fy) not white: ${Integer.toHexString(px)}",
                Color.red(px) > 230 && Color.green(px) > 230 && Color.blue(px) > 230,
            )
        }
        val red = out.getPixel((out.width * 0.7).toInt(), (out.height * 0.36).toInt())
        assertTrue("red block lost: ${Integer.toHexString(red)}", Color.red(red) > 150 && Color.green(red) < 110 && Color.blue(red) < 110)
        // the title stays black
        val dark = darkest(out, 0.08, 0.06, 0.6, 0.1)
        assertTrue("text washed out: $dark", dark < 60)
    }

    /** Lowest red value in a region given in fractions of the image (the darkest ink there). */
    private fun darkest(bmp: android.graphics.Bitmap, x0: Double, y0: Double, x1: Double, y1: Double): Int {
        var min = 255
        for (y in (bmp.height * y0).toInt() until (bmp.height * y1).toInt()) {
            for (x in (bmp.width * x0).toInt() until (bmp.width * x1).toInt()) min = minOf(min, Color.red(bmp.getPixel(x, y)))
        }
        return min
    }

    @Test
    fun strengthBlendsFilterWithThePage() {
        val shaded = TestImages.warmShadow(TestImages.flatPage())
        val x = (shaded.width * 0.03).toInt()
        val y = (shaded.height * 0.97).toInt()
        val before = Color.green(shaded.getPixel(x, y))
        val full = Color.green(ImageEnhancer.enhance(shaded, ScanFilter.ENHANCE).getPixel(x, y))
        val none = Color.green(ImageEnhancer.enhance(shaded, ScanFilter.ENHANCE, Adjustments(strength = 0f)).getPixel(x, y))
        val half = Color.green(ImageEnhancer.enhance(shaded, ScanFilter.ENHANCE, Adjustments(strength = 0.5f)).getPixel(x, y))
        assertEquals(before.toDouble(), none.toDouble(), 2.0)
        assertEquals((before + full) / 2.0, half.toDouble(), 3.0)
        assertTrue("filter has no effect: $before -> $full", full - before > 60)
    }

    @Test
    fun marksAreDrawnOnTheFinishedPage() {
        val page = TestImages.flatPage()
        val pen = MarkStroke(listOf(0.1f, 0.5f, 0.9f, 0.5f), Color.rgb(229, 57, 53), 0.01f)
        val marker = MarkStroke(listOf(0.05f, 0.08f, 0.95f, 0.08f), Color.rgb(255, 235, 59), 0.05f, highlighter = true)
        // marks are placed on the page as it comes out, after turning it
        val out = PageProcessor.render(page, null, 90, ScanFilter.ORIGINAL, Adjustments(), 2000, marks = listOf(pen, marker))
        assertEquals(page.height, out.width)
        val ink = out.getPixel(out.width / 2, out.height / 2)
        assertTrue("pen ink missing: ${Integer.toHexString(ink)}", Color.red(ink) > 180 && Color.green(ink) < 110)
        // highlighter tints the paper but never hides what is under it
        val tinted = out.getPixel(out.width / 2, (out.height * 0.06).toInt())
        assertTrue("highlighter missing: ${Integer.toHexString(tinted)}", Color.red(tinted) > 200 && Color.blue(tinted) < 200)
    }

    @Test
    fun marksAndStrengthSurviveStorage() {
        val page = ScanPage(
            id = "p",
            corners = null,
            adjustments = Adjustments(strength = 0.6f),
            marks = listOf(
                MarkStroke(listOf(0.1f, 0.2f, 0.3f, 0.4f), Color.BLUE, 0.004f, group = 2),
                MarkStroke(listOf(0.5f, 0.6f), Color.YELLOW, 0.02f, highlighter = true),
            ),
        )
        val back = ScanPage.fromJson(org.json.JSONObject(page.toJson().toString()))
        assertEquals(page.marks, back.marks)
        assertEquals(0.6f, back.adjustments.strength)
        assertEquals(1f, ScanPage.fromJson(ScanPage("q", null).toJson()).adjustments.strength)
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
