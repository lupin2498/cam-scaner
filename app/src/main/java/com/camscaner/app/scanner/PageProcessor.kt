package com.camscaner.app.scanner

import android.graphics.Bitmap
import com.camscaner.app.pdf.PaperSize
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/** Turns a stored original photo plus the page parameters into the final page image. */
object PageProcessor {

    /** Longest side of a stored page image (≈ A4 at 275 dpi). */
    const val MAX_PAGE = 3200

    /**
     * Perspective-corrects [original] (upright) using normalized [corners].
     * The output aspect ratio is the real one of the sheet, snapped to a standard paper size
     * (A-series, Letter, Legal, ID card) when it is close enough.
     */
    fun warp(original: Bitmap, corners: Corners?, maxDim: Int = MAX_PAGE): Bitmap {
        val w = original.width
        val h = original.height
        if (corners == null || corners == Corners.FULL) {
            val copy = original.copy(Bitmap.Config.ARGB_8888, false)
            return ImageIO.scaleDown(copy, maxDim)
        }
        val px = corners.scaled(w.toFloat(), h.toFloat())
        val ratio = PaperSize.snapRatio(estimateAspectRatio(px, w, h))
        val (outW, outH) = correctedSize(px, ratio, maxDim)
        return withMats {
            val src = mat()
            Utils.bitmapToMat(original, src)
            val srcPts = MatOfPoint2f(
                Point(px.tl.x.toDouble(), px.tl.y.toDouble()),
                Point(px.tr.x.toDouble(), px.tr.y.toDouble()),
                Point(px.br.x.toDouble(), px.br.y.toDouble()),
                Point(px.bl.x.toDouble(), px.bl.y.toDouble())
            ).track()
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outW - 1.0, 0.0),
                Point(outW - 1.0, outH - 1.0),
                Point(0.0, outH - 1.0)
            ).track()
            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts).track()
            val dst = mat()
            Imgproc.warpPerspective(
                src, dst, transform, Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
            )
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, out)
            out
        }
    }

    fun render(
        original: Bitmap,
        corners: Corners?,
        rotation: Int,
        filter: ScanFilter,
        adjustments: Adjustments,
        maxDim: Int = MAX_PAGE,
    ): Bitmap {
        return enhanceAndRotate(warp(original, corners, maxDim), rotation, filter, adjustments)
    }

    fun render(
        originalFile: File,
        corners: Corners?,
        rotation: Int,
        filter: ScanFilter,
        adjustments: Adjustments,
        maxDim: Int = MAX_PAGE,
    ): Bitmap {
        val original = ImageIO.decodeSampled(originalFile, ImageIO.MAX_ORIGINAL)
            ?: error("Cannot decode ${originalFile.name}")
        // the full-size original is released before enhancement, which needs most of the memory
        val warped = try {
            warp(original, corners, maxDim)
        } finally {
            original.recycle()
        }
        return enhanceAndRotate(warped, rotation, filter, adjustments)
    }

    private fun enhanceAndRotate(warped: Bitmap, rotation: Int, filter: ScanFilter, adjustments: Adjustments): Bitmap {
        val enhanced = if (filter == ScanFilter.ORIGINAL && adjustments.isNeutral) warped
        else ImageEnhancer.enhance(warped, filter, adjustments).also { warped.recycle() }
        return ImageIO.rotate(enhanced, rotation)
    }
}
