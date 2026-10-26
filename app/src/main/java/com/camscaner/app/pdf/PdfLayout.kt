package com.camscaner.app.pdf

import com.camscaner.app.scanner.PaperPreset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where an image goes on a PDF page, in points with the origin at the bottom-left corner. */
data class Placement(val index: Int, val x: Float, val y: Float, val width: Float, val height: Float)

data class Sheet(val width: Float, val height: Float, val items: List<Placement>)

/** A page image to lay out: its size in pixels (already rotated) and the paper format it was cut to, if known. */
data class PageSize(val width: Int, val height: Int, val paper: PaperPreset? = null) {
    val ratio: Double get() = width.toDouble() / height
}

/**
 * Computes PDF pages for a list of page images. Pure geometry so it can be unit tested.
 */
object PdfLayout {

    private const val A4_W = 595.28f
    private const val A4_H = 841.89f
    private const val CARD_GAP = 36f

    /** Pages given as pixel sizes only (no known paper format). */
    @JvmName("planSizes")
    fun plan(
        sizes: List<Pair<Int, Int>>,
        paper: PaperSize,
        orientation: PageOrientation,
        margin: PageMargin,
        mergeIdCards: Boolean,
        pagesPerSheet: Int = 1,
    ): List<Sheet> = plan(sizes.map { PageSize(it.first, it.second) }, paper, orientation, margin, mergeIdCards, pagesPerSheet)

    /**
     * [pagesPerSheet] of 2 or 4 prints several pages on one sheet of [paper] (A4 when automatic);
     * otherwise each page gets a sheet, and cards (ID cards, passports, business cards) are printed
     * at their real size on A4, two per sheet when [mergeIdCards] is set.
     */
    fun plan(
        pages: List<PageSize>,
        paper: PaperSize,
        orientation: PageOrientation,
        margin: PageMargin,
        mergeIdCards: Boolean,
        pagesPerSheet: Int = 1,
    ): List<Sheet> {
        if (pagesPerSheet > 1) return nUp(pages, paper, orientation, margin, pagesPerSheet)
        val sheets = ArrayList<Sheet>()
        var i = 0
        while (i < pages.size) {
            val page = pages[i]
            val card = if (paper == PaperSize.AUTO) cardSize(page) else null
            if (card != null) {
                val next = pages.getOrNull(i + 1)
                val nextCard = if (mergeIdCards && next != null) cardSize(next) else null
                if (nextCard != null) {
                    sheets += cardSheet(listOf(i to card, (i + 1) to nextCard))
                    i += 2
                    continue
                }
                sheets += cardSheet(listOf(i to card))
                i++
                continue
            }
            sheets += singleSheet(i, page, paper, orientation, margin)
            i++
        }
        return sheets
    }

    /** Real size (width, height in points, in the page's orientation) of a page that is a card, else null. */
    private fun cardSize(page: PageSize): Pair<Float, Float>? {
        val preset = page.paper
        val landscape = page.ratio >= 1.0
        val (short, long) = when {
            preset != null && preset.isCard -> preset.widthPt to preset.heightPt
            preset == null && PaperSize.match(page.ratio) == PaperKind.ID_CARD -> PaperSize.ID_CARD_HEIGHT_PT to PaperSize.ID_CARD_WIDTH_PT
            else -> return null
        }
        return if (landscape) long to short else short to long
    }

    private fun singleSheet(index: Int, page: PageSize, paper: PaperSize, orientation: PageOrientation, margin: PageMargin): Sheet {
        val ratio = page.ratio.toFloat()
        val m = margin.points
        var exactFit = false
        val preset = page.paper
        val (baseW, baseH) = when {
            paper.isFixed -> paper.widthPt to paper.heightPt
            paper == PaperSize.AUTO && preset != null && !preset.isCard -> {
                // the page was cut to this format, so the PDF page is that very sheet
                exactFit = true
                preset.widthPt to preset.heightPt
            }
            paper == PaperSize.AUTO -> {
                val kind = PaperSize.match(ratio.toDouble())
                val std = kind?.paper
                if (std != null) {
                    exactFit = true
                    std.widthPt to std.heightPt
                } else {
                    fitImagePage(ratio, m)
                }
            }
            else -> fitImagePage(ratio, m)
        }
        val landscape = when (orientation) {
            PageOrientation.AUTO -> ratio > 1f
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
        }
        val pageW = if (landscape) max(baseW, baseH) else min(baseW, baseH)
        val pageH = if (landscape) min(baseW, baseH) else max(baseW, baseH)

        val boxW = pageW - 2 * m
        val boxH = pageH - 2 * m
        val boxRatio = boxW / boxH
        // When the photo is recognised as the same standard sheet and there is no margin, fill the
        // page exactly (the difference is below the snap tolerance) so the PDF looks like a flatbed
        // scan. With margins the box no longer has the sheet's ratio, so the image keeps its own.
        val fill = exactFit && m == 0f && orientation == PageOrientation.AUTO &&
            abs(ratio / boxRatio - 1f) < PaperSize.SNAP_TOLERANCE
        val (iw, ih) = when {
            fill -> boxW to boxH
            ratio > boxRatio -> boxW to boxW / ratio
            else -> boxH * ratio to boxH
        }
        val x = (pageW - iw) / 2f
        val y = (pageH - ih) / 2f
        return Sheet(pageW, pageH, listOf(Placement(index, x, y, iw, ih)))
    }

    /** Page that follows the image proportions; the long side matches A4's long side. */
    private fun fitImagePage(ratio: Float, margin: Float): Pair<Float, Float> {
        val contentLong = A4_H - 2 * margin
        val (cw, ch) = if (ratio >= 1f) contentLong to contentLong / ratio else contentLong * ratio to contentLong
        return (cw + 2 * margin) to (ch + 2 * margin)
    }

    /** Cards are printed at their real size, stacked on an A4 portrait page (front + back). */
    private fun cardSheet(cards: List<Pair<Int, Pair<Float, Float>>>): Sheet {
        val items = ArrayList<Placement>()
        val totalH = cards.sumOf { it.second.second.toDouble() }.toFloat() + CARD_GAP * (cards.size - 1)
        var top = (A4_H - totalH) / 2f
        for ((index, size) in cards) {
            val (cw, ch) = size
            val x = (A4_W - cw) / 2f
            val y = A4_H - top - ch
            items += Placement(index, x, y, cw, ch)
            top += ch + CARD_GAP
        }
        return Sheet(A4_W, A4_H, items)
    }

    /**
     * Several pages per sheet: two side by side on a landscape sheet, or four in a grid on a
     * portrait one (a fixed orientation turns the grid along). Every page keeps its proportions
     * inside its cell.
     */
    private fun nUp(pages: List<PageSize>, paper: PaperSize, orientation: PageOrientation, margin: PageMargin, perSheet: Int): List<Sheet> {
        val base = if (paper.isFixed) paper else PaperSize.A4
        val m = margin.points
        val gap = max(m, 10f)
        val landscape = when (orientation) {
            PageOrientation.AUTO -> perSheet == 2
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
        }
        val pageW = if (landscape) max(base.widthPt, base.heightPt) else min(base.widthPt, base.heightPt)
        val pageH = if (landscape) min(base.widthPt, base.heightPt) else max(base.widthPt, base.heightPt)
        val (cols, rows) = when {
            perSheet == 2 && landscape -> 2 to 1
            perSheet == 2 -> 1 to 2
            else -> 2 to 2
        }
        val cellW = (pageW - 2 * m - gap * (cols - 1)) / cols
        val cellH = (pageH - 2 * m - gap * (rows - 1)) / rows
        return pages.indices.chunked(cols * rows).map { chunk ->
            val items = chunk.mapIndexed { k, index ->
                val ratio = pages[index].ratio.toFloat()
                val col = k % cols
                val row = k / cols
                val (iw, ih) = if (ratio > cellW / cellH) cellW to cellW / ratio else cellH * ratio to cellH
                val x = m + col * (cellW + gap) + (cellW - iw) / 2f
                val top = m + row * (cellH + gap) + (cellH - ih) / 2f
                Placement(index, x, pageH - top - ih, iw, ih)
            }
            Sheet(pageW, pageH, items)
        }
    }
}
