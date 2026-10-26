package com.camscaner.app.pdf

import com.camscaner.app.scanner.PaperPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PdfLayoutTest {

    private fun plan(
        sizes: List<Pair<Int, Int>>,
        paper: PaperSize = PaperSize.AUTO,
        orientation: PageOrientation = PageOrientation.AUTO,
        margin: PageMargin = PageMargin.NONE,
        mergeIdCards: Boolean = true,
        pagesPerSheet: Int = 1,
    ) = PdfLayout.plan(sizes, paper, orientation, margin, mergeIdCards, pagesPerSheet)

    @Test
    fun paperIsRecognisedFromRatio() {
        assertEquals(PaperKind.A_SERIES, PaperSize.match(2480.0 / 3508.0))
        assertEquals(PaperKind.A_SERIES, PaperSize.match(3508.0 / 2480.0))
        assertEquals(PaperKind.LETTER_SIZE, PaperSize.match(8.5 / 11.0))
        assertEquals(PaperKind.LEGAL_SIZE, PaperSize.match(8.5 / 14.0))
        assertEquals(PaperKind.ID_CARD, PaperSize.match(85.6 / 53.98))
        assertNull(PaperSize.match(1.0))
    }

    @Test
    fun snapRatioKeepsOrientation() {
        assertEquals(1 / sqrt(2.0), PaperSize.snapRatio(0.70), 1e-9)
        assertEquals(sqrt(2.0), PaperSize.snapRatio(1.43), 1e-9)
        assertEquals(0.9, PaperSize.snapRatio(0.9), 1e-9)
    }

    @Test
    fun autoA4PhotoFillsA4Page() {
        val sheets = plan(listOf(2480 to 3500))
        assertEquals(1, sheets.size)
        val s = sheets[0]
        assertEquals(595.28f, s.width, 0.01f)
        assertEquals(841.89f, s.height, 0.01f)
        val p = s.items.single()
        assertEquals(0f, p.x, 0.01f)
        assertEquals(0f, p.y, 0.01f)
        assertEquals(s.width, p.width, 0.01f)
        assertEquals(s.height, p.height, 0.01f)
    }

    @Test
    fun autoA4PhotoWithMarginKeepsItsProportions() {
        val s = plan(listOf(2480 to 3508), margin = PageMargin.MEDIUM).single()
        assertEquals(595.28f, s.width, 0.01f)
        val p = s.items.single()
        assertEquals(2480f / 3508f, p.width / p.height, 0.002f)
        assertTrue(p.x >= 35.9f && p.y >= 35.9f)
    }

    @Test
    fun autoLandscapePhotoGetsLandscapePage() {
        val s = plan(listOf(3508 to 2480)).single()
        assertTrue(s.width > s.height)
    }

    @Test
    fun unknownRatioFollowsImage() {
        val s = plan(listOf(1000 to 1000)).single()
        assertEquals(s.width, s.height, 0.01f)
        val p = s.items.single()
        assertEquals(s.width, p.width, 0.01f)
    }

    @Test
    fun fixedPaperWithMarginCentersImage() {
        val s = plan(listOf(1000 to 1000), paper = PaperSize.A4, margin = PageMargin.MEDIUM).single()
        val p = s.items.single()
        assertEquals(595.28f - 72f, p.width, 0.01f)
        assertEquals(p.width, p.height, 0.01f)
        assertEquals(36f, p.x, 0.01f)
        assertEquals((841.89f - p.height) / 2f, p.y, 0.01f)
    }

    @Test
    fun twoIdCardsShareOneA4PageAtRealSize() {
        val card = 1712 to 1080
        val sheets = plan(listOf(card, card, 2480 to 3508))
        assertEquals(2, sheets.size)
        val cards = sheets[0]
        assertEquals(2, cards.items.size)
        cards.items.forEach {
            assertEquals(PaperSize.ID_CARD_WIDTH_PT, it.width, 0.01f)
            assertEquals(PaperSize.ID_CARD_HEIGHT_PT, it.height, 0.01f)
        }
        // front above back
        assertTrue(cards.items[0].y > cards.items[1].y)
        assertEquals(2, sheets[1].items.single().index)
    }

    @Test
    fun idCardsAreNotMergedWhenDisabled() {
        val card = 1712 to 1080
        assertEquals(2, plan(listOf(card, card), mergeIdCards = false).size)
    }

    @Test
    fun pageCutToA5IsPrintedOnAnA5Sheet() {
        val s = PdfLayout.plan(listOf(PageSize(1480, 2100, PaperPreset.A5)), PaperSize.AUTO, PageOrientation.AUTO, PageMargin.NONE, true).single()
        assertEquals(PaperPreset.A5.widthPt, s.width, 0.01f)
        assertEquals(PaperPreset.A5.heightPt, s.height, 0.01f)
        val p = s.items.single()
        assertEquals(s.width, p.width, 0.01f)
        assertEquals(s.height, p.height, 0.01f)
        // a fixed paper size still wins over the format the page was cut to
        val letter = PdfLayout.plan(listOf(PageSize(1480, 2100, PaperPreset.A5)), PaperSize.LETTER, PageOrientation.AUTO, PageMargin.NONE, true).single()
        assertEquals(612f, letter.width, 0.01f)
    }

    @Test
    fun cardsOfKnownFormatsArePrintedAtRealSize() {
        val passport = PageSize(1250, 880, PaperPreset.PASSPORT)
        val merged = PdfLayout.plan(listOf(passport, passport), PaperSize.AUTO, PageOrientation.AUTO, PageMargin.NONE, true)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].items.size)
        merged[0].items.forEach {
            assertEquals(125f * PaperPreset.PT_PER_MM, it.width, 0.01f)
            assertEquals(88f * PaperPreset.PT_PER_MM, it.height, 0.01f)
        }
        // a business card held upright is printed upright, alone on its page
        val card = PdfLayout.plan(listOf(PageSize(550, 900, PaperPreset.BUSINESS_CARD)), PaperSize.AUTO, PageOrientation.AUTO, PageMargin.NONE, false).single()
        val p = card.items.single()
        assertEquals(55f * PaperPreset.PT_PER_MM, p.width, 0.01f)
        assertEquals(90f * PaperPreset.PT_PER_MM, p.height, 0.01f)
        assertEquals(595.28f, card.width, 0.01f)
    }

    @Test
    fun twoPagesPerSheetLieSideBySideOnALandscapeSheet() {
        val a = 2480 to 3508
        val sheets = plan(listOf(a, a, a), pagesPerSheet = 2)
        assertEquals(2, sheets.size)
        val first = sheets[0]
        assertEquals(841.89f, first.width, 0.01f)
        assertEquals(595.28f, first.height, 0.01f)
        assertEquals(2, first.items.size)
        val (l, r) = first.items
        assertTrue("cells overlap", l.x + l.width <= r.x + 0.01f)
        assertEquals(l.y, r.y, 0.01f)
        assertEquals(2480f / 3508f, l.width / l.height, 0.002f)
        assertEquals(1, sheets[1].items.size)
        assertEquals(2, sheets[1].items.single().index)
    }

    @Test
    fun fourPagesPerSheetFillAGridOnAPortraitSheet() {
        val a = 2480 to 3508
        val sheet = plan(listOf(a, a, a, a), paper = PaperSize.LETTER, pagesPerSheet = 4).single()
        assertEquals(612f, sheet.width, 0.01f)
        assertEquals(4, sheet.items.size)
        val xs = sheet.items.map { it.x }.distinct()
        val ys = sheet.items.map { it.y }.distinct()
        assertEquals(2, xs.size)
        assertEquals(2, ys.size)
        sheet.items.forEach { assertTrue(it.x >= 0f && it.y >= 0f && it.x + it.width <= 612f && it.y + it.height <= 792f) }
    }
}
