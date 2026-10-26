package com.camscaner.app.scanner

import androidx.annotation.StringRes
import com.camscaner.app.R
import com.camscaner.app.pdf.PaperKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sheet and card formats the crop region can be fitted to. Sizes are in millimetres; sheets are
 * given portrait, cards landscape (the way they are usually held), but a page keeps whichever
 * orientation it was photographed in.
 */
enum class PaperPreset(
    @StringRes val label: Int,
    val widthMm: Float,
    val heightMm: Float,
    /** Family recognised by the automatic PDF page size, when there is one. */
    val kind: PaperKind?,
    /** Cards are printed at their real size on a larger page; sheets become the PDF page itself. */
    val isCard: Boolean = false,
) {
    A4(R.string.paper_a4, 210f, 297f, PaperKind.A_SERIES),
    A5(R.string.paper_a5, 148f, 210f, PaperKind.A_SERIES),
    A6(R.string.paper_a6, 105f, 148f, PaperKind.A_SERIES),
    A3(R.string.paper_a3, 297f, 420f, PaperKind.A_SERIES),
    B5(R.string.paper_b5, 176f, 250f, null),
    LETTER(R.string.paper_letter, 215.9f, 279.4f, PaperKind.LETTER_SIZE),
    LEGAL(R.string.paper_legal, 215.9f, 355.6f, PaperKind.LEGAL_SIZE),
    ID_CARD(R.string.paper_id_card, 85.6f, 53.98f, PaperKind.ID_CARD, isCard = true),
    PASSPORT(R.string.paper_passport, 125f, 88f, null, isCard = true),
    BUSINESS_CARD(R.string.paper_business_card, 90f, 55f, null, isCard = true),
    PHOTO_4X6(R.string.paper_photo_4x6, 102f, 152f, null),
    SQUARE(R.string.paper_square, 100f, 100f, null);

    /** Short side over long side. */
    val ratio: Double get() = min(widthMm, heightMm).toDouble() / max(widthMm, heightMm)

    /** Width in PDF points when the format is used portrait. */
    val widthPt: Float get() = min(widthMm, heightMm) * PT_PER_MM
    val heightPt: Float get() = max(widthMm, heightMm) * PT_PER_MM

    /**
     * The width/height ratio this format has in the orientation closest to [visible] (a measured
     * width/height ratio): portrait when the region is taller than wide, landscape otherwise.
     */
    fun ratioFor(visible: Double): Double = if (visible <= 1.0) ratio else 1.0 / ratio

    companion object {
        const val PT_PER_MM = 72f / 25.4f

        fun fromName(name: String?): PaperPreset? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Projective map of the unit square onto a quadrilateral (Heckbert, "Fundamentals of Texture
 * Mapping and Image Warping"): (0,0) ↦ top-left, (1,0) ↦ top-right, (1,1) ↦ bottom-right,
 * (0,1) ↦ bottom-left. It lets regions be measured and placed in the plane of the sheet.
 */
class Homography private constructor(
    private val a: Double, private val b: Double, private val c: Double,
    private val d: Double, private val e: Double, private val f: Double,
    private val g: Double, private val h: Double,
) {
    /** Image position of the sheet-plane point ([u], [v]) in [0, 1]². */
    fun map(u: Double, v: Double): Pt {
        val den = g * u + h * v + 1.0
        return Pt(((a * u + b * v + c) / den).toFloat(), ((d * u + e * v + f) / den).toFloat())
    }

    /** The quadrilateral the sheet-plane rectangle [u0]..[u1] × [v0]..[v1] is seen as. */
    fun quad(u0: Double, v0: Double, u1: Double, v1: Double): Corners =
        Corners(map(u0, v0), map(u1, v0), map(u1, v1), map(u0, v1))

    companion object {
        fun squareTo(q: Corners): Homography {
            val x0 = q.tl.x.toDouble(); val y0 = q.tl.y.toDouble()
            val x1 = q.tr.x.toDouble(); val y1 = q.tr.y.toDouble()
            val x2 = q.br.x.toDouble(); val y2 = q.br.y.toDouble()
            val x3 = q.bl.x.toDouble(); val y3 = q.bl.y.toDouble()
            val sx = x0 - x1 + x2 - x3
            val sy = y0 - y1 + y2 - y3
            if (abs(sx) < 1e-9 && abs(sy) < 1e-9) {
                // a parallelogram: plain affine map
                return Homography(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0)
            }
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            if (abs(det) < 1e-12) return Homography(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0)
            val g = (sx * dy2 - dx2 * sy) / det
            val h = (dx1 * sy - sx * dy1) / det
            return Homography(
                x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                g, h,
            )
        }
    }
}

/** Fits crop regions to standard paper formats. */
object PaperFit {

    /**
     * The largest region with the proportions of [paper] inside [base] (the detected sheet, or the
     * whole photo when null), centred on it and seen in the same perspective. The result keeps
     * whichever orientation [base] has. [imageWidth] × [imageHeight] is the photo in pixels.
     */
    fun fit(base: Corners?, paper: PaperPreset, imageWidth: Int, imageHeight: Int): Corners {
        val quad = base ?: Corners.FULL
        val px = quad.scaled(imageWidth.toFloat(), imageHeight.toFloat())
        val visible = estimateAspectRatio(px, imageWidth, imageHeight)
        val target = paper.ratioFor(visible)
        // in the plane of the sheet the base region is a rectangle of ratio `visible`
        val k = target / visible
        val homography = Homography.squareTo(quad)
        return if (k <= 1.0) {
            homography.quad(0.5 - k / 2, 0.0, 0.5 + k / 2, 1.0)
        } else {
            val hh = 1.0 / k
            homography.quad(0.0, 0.5 - hh / 2, 1.0, 0.5 + hh / 2)
        }
    }

    /**
     * The two pages of an open book photographed as one spread: the left and right halves of
     * [spread] in the plane of the sheet. With [verticalSplit] the spread is divided top/bottom instead.
     */
    fun split(spread: Corners?, verticalSplit: Boolean = false): Pair<Corners, Corners> {
        val h = Homography.squareTo(spread ?: Corners.FULL)
        return if (verticalSplit) h.quad(0.0, 0.0, 1.0, 0.5) to h.quad(0.0, 0.5, 1.0, 1.0)
        else h.quad(0.0, 0.0, 0.5, 1.0) to h.quad(0.5, 0.0, 1.0, 1.0)
    }

    /**
     * Normalized frame with the proportions of [paper] centred in a preview of [viewWidth] × [viewHeight],
     * covering [fill] of the limiting side; sheets are shown portrait and cards landscape on a
     * portrait preview, and the other way round on a landscape one.
     */
    fun guide(paper: PaperPreset, viewWidth: Float, viewHeight: Float, fill: Float = 0.86f): Corners {
        val portraitView = viewHeight >= viewWidth
        val landscapeFormat = paper.isCard
        val ratio = if (portraitView != landscapeFormat) paper.ratio else 1.0 / paper.ratio // w/h
        var w = viewWidth * fill
        var h = (w / ratio).toFloat()
        if (h > viewHeight * fill) {
            h = viewHeight * fill
            w = (h * ratio).toFloat()
        }
        val left = (viewWidth - w) / 2f / viewWidth
        val top = (viewHeight - h) / 2f / viewHeight
        val right = left + w / viewWidth
        val bottom = top + h / viewHeight
        return Corners(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))
    }
}
