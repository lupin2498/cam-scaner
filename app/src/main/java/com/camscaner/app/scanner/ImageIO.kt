package com.camscaner.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIO {

    /** Originals are stored upright, without EXIF rotation, and at most this size. */
    const val MAX_ORIGINAL = 4096

    private const val MAX_DECODE_PIXELS = 24_000_000L

    fun copyUri(context: Context, uri: Uri, target: File): File {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            FileOutputStream(target).use { input.copyTo(it) }
        }
        return target
    }

    /** Applies EXIF rotation, limits resolution and rewrites [source] as a plain JPEG at [target]. */
    fun normalize(source: File, target: File, maxDim: Int = MAX_ORIGINAL): Pair<Int, Int> {
        val transform = exifTransform(source)
        var bitmap = decodeSampled(source, maxDim) ?: error("Unsupported image")
        if (transform != null) bitmap = transform(bitmap, transform)
        saveJpeg(bitmap, target, 95)
        val size = bitmap.width to bitmap.height
        bitmap.recycle()
        return size
    }

    /** Rotation and mirroring stored in the EXIF orientation tag, or null for an upright image. */
    fun exifTransform(file: File): Matrix? = try {
        val exif = ExifInterface(file.absolutePath)
        val degrees = exif.rotationDegrees
        val flipped = exif.isFlipped
        if (degrees == 0 && !flipped) null
        // rotationDegrees assumes the mirror is applied first, then the rotation
        else Matrix().apply {
            if (flipped) setScale(-1f, 1f)
            postRotate(degrees.toFloat())
        }
    } catch (e: Exception) {
        null
    }

    fun readSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    /** Decodes [file] so that its longest side is at most [maxDim] pixels. */
    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val (w, h) = readSize(file)
        if (w <= 0 || h <= 0) return null
        // Largest power-of-two subsampling that keeps (almost) the target resolution, but never
        // decode more than ~24 MP: a 50 MP photo would need ~200 MB as a bitmap.
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDim * 9 / 10 ||
            (w / sample).toLong() * (h / sample) > MAX_DECODE_PIXELS
        ) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return scaleDown(decoded, maxDim)
    }

    fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxDim) return bitmap
        val s = maxDim.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * s).roundToInt().coerceAtLeast(1),
            (bitmap.height * s).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bitmap
        return transform(bitmap, Matrix().apply { postRotate(d.toFloat()) })
    }

    private fun transform(bitmap: Bitmap, matrix: Matrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        // compress() reports write errors (e.g. a full disk) only through its result; never let a
        // truncated file replace a good one
        val ok = FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!ok || tmp.length() == 0L) {
            tmp.delete()
            throw IOException("Cannot write ${file.name}")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
