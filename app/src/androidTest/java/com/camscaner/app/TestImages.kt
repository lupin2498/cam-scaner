package com.camscaner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.Pt
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic "phone photos" of documents with known geometry. */
object TestImages {

    const val MARKER = "HOA DON 12345"

    data class Scene(val bitmap: Bitmap, val corners: Corners, val paperRatio: Double)

    /**
     * A sheet with text lying on a table, photographed by a pinhole camera tilted by
     * [rx]/[ry] radians. [paperRatio] is width/height of the sheet.
     */
    fun documentPhoto(
        width: Int = 1200,
        height: Int = 1600,
        paperRatio: Double = 1 / sqrt(2.0),
        rx: Double = 0.3,
        ry: Double = 0.18,
        distance: Double = 1.9,
    ): Scene {
        val pw = 1000
        val ph = (pw / paperRatio).toInt()
        val paper = paper(pw, ph)

        val f = 1.15 * height
        // the long side of the sheet is one unit
        val sy = 0.5 / maxOf(paperRatio, 1.0)
        val sx = paperRatio * sy
        val pts = listOf(-sx to -sy, sx to -sy, sx to sy, -sx to sy).map { (x, y) ->
            val y1 = y * cos(rx)
            val z1 = y * sin(rx)
            val x2 = x * cos(ry) + z1 * sin(ry)
            val z2 = -x * sin(ry) + z1 * cos(ry) + distance
            floatArrayOf((f * x2 / z2 + width / 2).toFloat(), (f * y1 / z2 + height / 2).toFloat())
        }

        val scene = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scene)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(58, 52, 47), Color.rgb(118, 104, 92), Shader.TileMode.CLAMP)
        })
        val dst = floatArrayOf(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[2][0], pts[2][1], pts[3][0], pts[3][1])
        val src = floatArrayOf(0f, 0f, pw.toFloat(), 0f, pw.toFloat(), ph.toFloat(), 0f, ph.toFloat())
        val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        canvas.drawBitmap(paper, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // soft shadow over part of the sheet
        canvas.drawRect(0f, height * 0.62f, width * 0.55f, height.toFloat(), Paint().apply { color = Color.argb(70, 0, 0, 0) })
        paper.recycle()

        val corners = Corners(
            Pt(dst[0] / width, dst[1] / height),
            Pt(dst[2] / width, dst[3] / height),
            Pt(dst[4] / width, dst[5] / height),
            Pt(dst[6] / width, dst[7] / height),
        )
        return Scene(scene, corners, paperRatio)
    }

    /** The printed sheet itself, flat and level (as after perspective correction). */
    fun flatPage(width: Int = 1000, ratio: Double = 1 / sqrt(2.0)): Bitmap = paper(width, (width / ratio).toInt())

    /** [scene] photographed with the camera turned: the photo is rotated clockwise by [degrees]. */
    fun turned(scene: Scene, degrees: Int): Scene {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val bmp = Bitmap.createBitmap(scene.bitmap, 0, 0, scene.bitmap.width, scene.bitmap.height, m, true)
        fun map(p: Pt): Pt = when ((degrees % 360 + 360) % 360) {
            90 -> Pt(1f - p.y, p.x)
            180 -> Pt(1f - p.x, 1f - p.y)
            270 -> Pt(p.y, 1f - p.x)
            else -> p
        }
        val c = scene.corners
        return Scene(bmp, Corners.ordered(listOf(map(c.tl), map(c.tr), map(c.br), map(c.bl))), scene.paperRatio)
    }

    private fun paper(pw: Int, ph: Int): Bitmap {
        val paper = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(paper)
        c.drawColor(Color.rgb(246, 245, 240))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = pw / 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(MARKER, pw * 0.08f, ph * 0.1f, title)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 25, 25)
            textSize = pw / 28f
        }
        var y = ph * 0.18f
        var i = 1
        while (y < ph * 0.92f) {
            c.drawText("Dong $i: thong tin van ban mau de kiem tra", pw * 0.08f, y, body)
            y += body.textSize * 1.8f
            i++
        }
        c.drawRect(pw * 0.55f, ph * 0.3f, pw * 0.85f, ph * 0.42f, Paint().apply { color = Color.rgb(200, 40, 40) })
        return paper
    }

    fun save(bitmap: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }

    /** Stores a screenshot in /sdcard/Pictures so CI can pull it after the run. */
    fun screenshot(name: String) {
        shell("mkdir -p /sdcard/Pictures/camscaner")
        shell("screencap -p /sdcard/Pictures/camscaner/$name.png")
    }

    /** Runs a shell command as the shell user and waits for it to finish. */
    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }
}
