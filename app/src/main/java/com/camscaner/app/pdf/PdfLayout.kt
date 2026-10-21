package com.camscaner.app.pdf

import kotlin.math.max
import kotlin.math.min

/** Where an image goes on a PDF page, in points with the origin at the bottom-left corner. */
data class Placement(val index: Int, val x: Float, val y: Float, val width: Float, val height: Float)

data class Sheet(val width: Float, val height: Float, val items: List<Placement>)

/**
 * Computes PDF pages for a list of page images (sizes in pixels, already rotated).
 * Pure geometry so it can be unit tested.
 */
object PdfLayout {

    private const val A4_W = 595.28f
    private const val A4_H = 841.89f
    private const val ID_CARD_GAP = 36f

    fun plan(
        sizes: List<Pair<Int, Int>>,
        paper: PaperSize,
        orientation: PageOrientation,
        margin: PageMargin,
        mergeIdCards: Boolean,
    ): List<Sheet> {
        val sheets = ArrayList<Sheet>()
        var i = 0
        while (i < sizes.size) {
            val (w, h) = sizes[i]
            val ratio = w.toDouble() / h
            val isCard = paper == PaperSize.AUTO && PaperSize.match(ratio) == PaperKind.ID_CARD
            if (isCard && mergeIdCards) {
                val next = sizes.getOrNull(i + 1)
                val nextIsCard = next != null &&
                    PaperSize.match(next.first.toDouble() / next.second) == PaperKind.ID_CARD
                if (nextIsCard) {
                    sheets += idCardSheet(listOf(i to ratio, (i + 1) to next!!.first.toDouble() / next.second))
                    i += 2
                    continue
                }
                sheets += idCardSheet(listOf(i to ratio))
                i++
                continue
            }
            sheets += singleSheet(i, w, h, paper, orientation, margin)
            i++
        }
        return sheets
    }

    private fun singleSheet(index: Int, w: Int, h: Int, paper: PaperSize, orientation: PageOrientation, margin: PageMargin): Sheet {
        val ratio = w.toFloat() / h
        val m = margin.points
        var exactFit = false
        val (baseW, baseH) = when {
            paper.isFixed -> paper.widthPt to paper.heightPt
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
        // When the photo is recognised as the same standard sheet, fill the page exactly
        // (the difference is below the snap tolerance) so the PDF looks like a flatbed scan.
        val fill = exactFit && orientation == PageOrientation.AUTO &&
            kotlin.math.abs(ratio / boxRatio - 1f) < PaperSize.SNAP_TOLERANCE * 2
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

    /** ID cards are printed at their real size, stacked on an A4 portrait page (front + back). */
    private fun idCardSheet(cards: List<Pair<Int, Double>>): Sheet {
        val items = ArrayList<Placement>()
        val sizes = cards.map { (_, r) ->
            if (r >= 1) PaperSize.ID_CARD_WIDTH_PT to PaperSize.ID_CARD_HEIGHT_PT
            else PaperSize.ID_CARD_HEIGHT_PT to PaperSize.ID_CARD_WIDTH_PT
        }
        val totalH = sizes.sumOf { it.second.toDouble() }.toFloat() + ID_CARD_GAP * (sizes.size - 1)
        var top = (A4_H - totalH) / 2f
        cards.forEachIndexed { k, (index, _) ->
            val (cw, ch) = sizes[k]
            val x = (A4_W - cw) / 2f
            val y = A4_H - top - ch
            items += Placement(index, x, y, cw, ch)
            top += ch + ID_CARD_GAP
        }
        return Sheet(A4_W, A4_H, items)
    }
}
