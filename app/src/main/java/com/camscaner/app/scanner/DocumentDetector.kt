package com.camscaner.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the paper sheet in a photo: several edge/threshold passes produce quadrilateral
 * candidates, which are ranked by area and by how much of their outline lies on real edges.
 */
object DocumentDetector {

    private const val DETECT_SIZE = 480.0
    private const val MIN_AREA_FRACTION = 0.10
    private const val MIN_EDGE_SUPPORT = 0.6

    /** Returns normalized corners, or null when no document is found. */
    fun detect(bitmap: Bitmap): Corners? = withMats {
        val rgba = mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        detectGray(gray)
    }

    /** [gray] is a single channel 8-bit image of any size. Returns normalized corners. */
    fun detectGray(gray: Mat): Corners? = withMats {
        val w = gray.cols()
        val h = gray.rows()
        if (w < 16 || h < 16) return@withMats null
        val scale = min(1.0, DETECT_SIZE / max(w, h))
        val small = if (scale < 1.0) {
            mat().also {
                Imgproc.resize(gray, it, Size((w * scale).roundToInt().toDouble(), (h * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
        } else gray
        val sw = small.cols()
        val sh = small.rows()
        val minArea = sw * sh * MIN_AREA_FRACTION

        val blur = mat()
        Imgproc.GaussianBlur(small, blur, Size(5.0, 5.0), 0.0)

        val pixels = ByteArray(sw * sh)
        blur.get(0, 0, pixels)
        val median = median(pixels)
        val lower = max(0.0, 0.66 * median)
        val upper = min(255.0, 1.33 * median)

        val supportEdges = mat()
        Imgproc.Canny(blur, supportEdges, 20.0, 60.0)
        Imgproc.dilate(supportEdges, supportEdges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).track())
        val support = ByteArray(sw * sh)
        supportEdges.get(0, 0, support)

        val candidates = ArrayList<Corners>()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).track()
        for ((a, b) in listOf(lower to upper, 20.0 to 60.0, 50.0 to 150.0)) {
            val edges = mat()
            Imgproc.Canny(blur, edges, a, b)
            Imgproc.dilate(edges, edges, kernel3)
            collectQuads(edges, minArea, candidates)
        }
        val otsu = mat()
        Imgproc.threshold(blur, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(
            otsu, otsu, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)).track()
        )
        collectQuads(otsu, minArea, candidates)

        var best: Corners? = null
        var bestScore = 0f
        for (q in candidates) {
            val s = edgeSupport(support, sw, sh, q)
            if (s < MIN_EDGE_SUPPORT) continue
            val score = q.area() * s * s
            if (score > bestScore) {
                best = q
                bestScore = score
            }
        }
        best?.scaled(1f / sw, 1f / sh)
    }

    private fun MatScope.collectQuads(binary: Mat, minArea: Double, out: MutableList<Corners>) {
        val w = binary.cols()
        val h = binary.rows()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { it.track() }
        val sorted = contours.map { it to Imgproc.contourArea(it) }.sortedByDescending { it.second }.take(10)
        for ((contour, area) in sorted) {
            if (area < minArea) break
            val hull = hullPoints(contour) ?: continue
            val hull2f = MatOfPoint2f(*hull).track()
            val perimeter = Imgproc.arcLength(hull2f, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.04, 0.05, 0.06)) {
                val approx = MatOfPoint2f().track()
                Imgproc.approxPolyDP(hull2f, approx, eps * perimeter, true)
                val n = approx.rows()
                if (n == 4) {
                    val pts = approx.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) }
                    val quad = Corners.ordered(pts)
                    val onBorder = quad.points.count { it.x <= 2 || it.y <= 2 || it.x >= w - 3 || it.y >= h - 3 }
                    if (onBorder < 2 && quad.area() >= minArea && quad.isValid(45.0, 135.0)) out += quad
                    break
                }
                if (n < 4) break
            }
        }
    }

    private fun MatScope.hullPoints(contour: MatOfPoint): Array<Point>? {
        val indices = MatOfInt().track()
        Imgproc.convexHull(contour, indices)
        val all = contour.toArray()
        val idx = indices.toArray()
        if (idx.size < 4) return null
        return Array(idx.size) { all[idx[it]] }
    }

    /** Fraction of points sampled along the quad outline that fall on an edge pixel. */
    private fun edgeSupport(edges: ByteArray, w: Int, h: Int, q: Corners): Float {
        var hit = 0
        var total = 0
        val p = q.points
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val n = max(8, (a.distanceTo(b) / 3f).toInt())
            for (k in 0 until n) {
                val t = 0.05f + 0.9f * k / (n - 1)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                total++
                if (x in 0 until w && y in 0 until h && edges[y * w + x].toInt() != 0) hit++
            }
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    private fun median(pixels: ByteArray): Double {
        val hist = IntArray(256)
        for (b in pixels) hist[b.toInt() and 0xFF]++
        val half = pixels.size / 2
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc > half) return i.toDouble()
        }
        return 127.0
    }

    /** Wraps the luminance plane of a camera frame without copying. */
    fun grayFromPlane(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Mat =
        Mat(height, width, CvType.CV_8UC1, buffer, rowStride.toLong())
}
