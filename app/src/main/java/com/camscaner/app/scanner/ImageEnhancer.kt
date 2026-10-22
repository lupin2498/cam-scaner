package com.camscaner.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Document beautification filters ("magic color", shadow removal, B&W…).
 *
 * Most filters divide the photo by an estimate of the paper illumination, which turns the
 * sheet uniformly white and removes shadows while keeping ink, photos and colored areas.
 */
object ImageEnhancer {

    fun enhance(src: Bitmap, filter: ScanFilter, adjustments: Adjustments = Adjustments()): Bitmap = withMats {
        val rgba = mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        var out = when (filter) {
            ScanFilter.ORIGINAL -> rgb
            ScanFilter.LIGHTEN -> lighten(rgb)
            ScanFilter.MAGIC -> magicColor(rgb)
            ScanFilter.NO_SHADOW -> noShadow(rgb)
            ScanFilter.GRAY -> grayscale(rgb)
            ScanFilter.BW -> blackAndWhite(rgb, adjustments)
            ScanFilter.BW_SOFT -> photocopy(rgb)
        }
        if (filter != ScanFilter.BW) out = adjust(out, adjustments)
        val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, result)
        result
    }

    private fun MatScope.lighten(rgb: Mat): Mat {
        val channels = split(rgb).map { stretch(it, 0.5, 99.5) }
        val merged = mat()
        Core.merge(channels, merged)
        Core.LUT(merged, gammaLut(0.85), merged)
        return sharpen(merged, 0.3, 1.0)
    }

    private fun MatScope.magicColor(rgb: Mat): Mat {
        val flat = flatten(rgb)
        Core.LUT(flat, gammaLut(1.5), flat)
        val hsv = mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_RGB2HSV)
        val parts = split(hsv)
        parts[1].convertTo(parts[1], -1, 1.3, 0.0)
        Core.merge(parts, hsv)
        val out = mat()
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2RGB)
        return sharpen(out, 0.5, 1.2)
    }

    private fun MatScope.noShadow(rgb: Mat): Mat {
        val flat = flatten(rgb)
        Core.LUT(flat, gammaLut(1.15), flat)
        return sharpen(flat, 0.3, 1.0)
    }

    private fun MatScope.grayscale(rgb: Mat): Mat {
        val gray = mat()
        Imgproc.cvtColor(flatten(rgb), gray, Imgproc.COLOR_RGB2GRAY)
        val stretched = stretch(gray, 1.0, 99.9)
        Core.LUT(stretched, gammaLut(1.35), stretched)
        return sharpen(stretched, 0.5, 1.0)
    }

    private fun MatScope.photocopy(rgb: Mat): Mat {
        val gray = mat()
        Imgproc.cvtColor(flatten(rgb), gray, Imgproc.COLOR_RGB2GRAY)
        val lut = Mat(1, 256, CvType.CV_8UC1).track()
        lut.put(0, 0, ByteArray(256) { i ->
            val v = if (i >= 210) 255.0 else 255.0 * (i / 210.0).pow(1.8)
            v.roundToInt().coerceIn(0, 255).toByte()
        })
        Core.LUT(gray, lut, gray)
        return sharpen(gray, 0.6, 1.0)
    }

    private fun MatScope.blackAndWhite(rgb: Mat, adjustments: Adjustments): Mat {
        val gray = mat()
        Imgproc.cvtColor(flatten(rgb), gray, Imgproc.COLOR_RGB2GRAY)
        // Brightness / contrast move the binarization thresholds instead of graying the result;
        // sharpening before thresholding keeps thin strokes crisp.
        val g = adjust(gray, adjustments)
        Imgproc.GaussianBlur(g, g, Size(3.0, 3.0), 0.0)
        val otsu = Imgproc.threshold(g, mat(), 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val block = odd(max(15, max(g.cols(), g.rows()) / 40))
        val adaptive = mat()
        Imgproc.adaptiveThreshold(g, adaptive, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, block, 15.0)

        val dark = mat()
        Core.compare(g, Scalar(min(otsu, 200.0)), dark, Core.CMP_LT)
        val localInk = mat()
        Core.bitwise_not(adaptive, localInk)
        val notPaper = mat()
        Core.compare(g, Scalar(225.0), notPaper, Core.CMP_LT)
        Core.bitwise_and(localInk, notPaper, localInk)
        Core.bitwise_or(dark, localInk, dark)
        val out = mat()
        Core.bitwise_not(dark, out)
        return out
    }

    /** Divides every channel by the estimated paper illumination. */
    private fun MatScope.flatten(rgb: Mat): Mat {
        val background = backgroundModel(rgb)
        val channels = split(rgb)
        val flat = channels.mapIndexed { i, c ->
            val d = mat()
            Core.divide(c, background[i], d, 255.0)
            d
        }
        val out = mat()
        Core.merge(flat, out)
        return out
    }

    /**
     * Per-channel illumination map at full resolution (8-bit). A local estimate (closing + median,
     * removes text) is trusted where it looks like paper — neutral and not too dark relative to a
     * smooth surface fit; elsewhere (colored blocks, photos) the smooth fit is used instead.
     */
    private fun MatScope.backgroundModel(rgb: Mat): List<Mat> {
        val w = rgb.cols()
        val h = rgb.rows()
        val s = min(1.0, 600.0 / max(w, h))
        val small = mat()
        Imgproc.resize(rgb, small, Size(max(1.0, (w * s).roundToInt().toDouble()), max(1.0, (h * s).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
        val sw = small.cols()
        val sh = small.rows()
        val longSide = max(sw, sh)
        // closing (dilate then erode) removes text without shifting the edges of shadows,
        // which a plain dilation would turn into a dark line along every shadow boundary
        val k = odd(max(3, longSide / 45)).toDouble()
        val medianSize = odd(max(5, longSide / 25))
        // a square element is separable, several times faster than an ellipse of the same size
        val se = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k, k)).track()

        val local = split(small).map { c ->
            val d = mat()
            Imgproc.morphologyEx(c, d, Imgproc.MORPH_CLOSE, se)
            Imgproc.medianBlur(d, d, medianSize)
            val f = mat()
            d.convertTo(f, CvType.CV_32F)
            f
        }
        val smooth = local.map { l ->
            val grid = mat()
            Imgproc.resize(l, grid, Size(GRID.toDouble(), GRID.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val values = FloatArray(GRID * GRID)
            grid.get(0, 0, values)
            val fitted = Mat(GRID, GRID, CvType.CV_32F).track()
            fitted.put(0, 0, SurfaceFit.fit(values, GRID, GRID))
            val up = mat()
            Imgproc.resize(fitted, up, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            up
        }

        val lMax = mat(); Core.max(local[0], local[1], lMax); Core.max(lMax, local[2], lMax)
        val lMin = mat(); Core.min(local[0], local[1], lMin); Core.min(lMin, local[2], lMin)
        val sMax = mat(); Core.max(smooth[0], smooth[1], sMax); Core.max(sMax, smooth[2], sMax)

        // saturation of the local background: (max - min) / max
        val sat = mat()
        Core.subtract(lMax, lMin, sat)
        val denom = mat()
        Core.max(lMax, Scalar(1.0), denom)
        Core.divide(sat, denom, sat)
        // brightness relative to the smooth paper model
        val ratio = mat()
        Core.max(sMax, Scalar(1.0), denom)
        Core.divide(lMax, denom, ratio)

        val neutral = mat()
        sat.convertTo(neutral, -1, -10.0, 2.0) // (0.2 - sat) / 0.1
        clamp01(neutral)
        val bright = mat()
        ratio.convertTo(bright, -1, 10.0, -3.0) // (ratio - 0.3) / 0.1
        clamp01(bright)
        val weight = mat()
        Core.multiply(neutral, bright, weight)
        Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), 2.0)

        return local.indices.map { i ->
            val guard = mat()
            Core.multiply(smooth[i], Scalar(0.85), guard)
            Core.max(guard, local[i], guard)
            // b = guard + weight * (local - guard)
            val diff = mat()
            Core.subtract(local[i], guard, diff)
            Core.multiply(diff, weight, diff)
            val b = mat()
            Core.add(guard, diff, b)
            Imgproc.GaussianBlur(b, b, Size(0.0, 0.0), 1.5)
            Core.max(b, Scalar(1.0), b)
            val b8 = mat()
            b.convertTo(b8, CvType.CV_8U)
            val full = mat()
            Imgproc.resize(b8, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            full
        }
    }

    private fun MatScope.adjust(src: Mat, a: Adjustments): Mat {
        var out = src
        if (a.brightness != 0f || a.contrast != 0f) {
            val alpha = 1.0 + a.contrast * 0.8
            val beta = a.brightness * 80.0 + 128.0 * (1.0 - alpha)
            val dst = mat()
            out.convertTo(dst, -1, alpha, beta)
            out = dst
        }
        if (a.sharpness > 0f) out = sharpen(out, a.sharpness * 1.5, 1.0)
        return out
    }

    private fun MatScope.sharpen(src: Mat, amount: Double, sigma: Double): Mat {
        val blur = mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), sigma)
        val out = mat()
        Core.addWeighted(src, 1.0 + amount, blur, -amount, 0.0, out)
        return out
    }

    /** Linear contrast stretch between two histogram percentiles of a single channel. */
    private fun MatScope.stretch(channel: Mat, lowPercent: Double, highPercent: Double): Mat {
        val hist = mat()
        Imgproc.calcHist(listOf(channel), MatOfInt(0).track(), Mat().track(), hist, MatOfInt(256).track(), MatOfFloat(0f, 256f).track())
        val counts = FloatArray(256)
        hist.get(0, 0, counts)
        val total = counts.sum().toDouble()
        if (total <= 0) return channel
        fun percentile(p: Double): Int {
            val target = total * p / 100.0
            var acc = 0.0
            for (i in 0 until 256) {
                acc += counts[i]
                if (acc >= target) return i
            }
            return 255
        }
        val lo = percentile(lowPercent)
        val hi = percentile(highPercent)
        if (hi - lo < 10) return channel
        val alpha = 255.0 / (hi - lo)
        val out = mat()
        channel.convertTo(out, -1, alpha, -lo * alpha)
        return out
    }

    private fun MatScope.split(src: Mat): MutableList<Mat> {
        val parts = ArrayList<Mat>()
        Core.split(src, parts)
        parts.forEach { it.track() }
        return parts
    }

    private fun MatScope.gammaLut(gamma: Double): Mat {
        val lut = Mat(1, 256, CvType.CV_8UC1).track()
        lut.put(0, 0, ByteArray(256) { i -> (255.0 * (i / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255).toByte() })
        return lut
    }

    private fun clamp01(m: Mat) {
        Core.min(m, Scalar(1.0), m)
        Core.max(m, Scalar(0.0), m)
    }

    private fun odd(v: Int): Int = if (v % 2 == 1) v else v + 1

    private const val GRID = 48
}
