package com.camscaner.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIO {

    /** Originals are stored upright, without EXIF rotation, and at most this size. */
    const val MAX_ORIGINAL = 4096

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
        val rotation = exifRotation(source)
        var bitmap = decodeSampled(source, maxDim) ?: error("Unsupported image")
        if (rotation != 0) bitmap = rotate(bitmap, rotation)
        saveJpeg(bitmap, target, 95)
        val size = bitmap.width to bitmap.height
        bitmap.recycle()
        return size
    }

    fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
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
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
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
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
