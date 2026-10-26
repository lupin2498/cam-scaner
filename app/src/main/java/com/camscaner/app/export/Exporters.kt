package com.camscaner.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.scanner.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Exports besides PDF: images to the gallery, one long image, text and Word files, printing. */
object Exporters {

    /** Copies every page image into the device gallery (Pictures/CamScaner). Returns how many were saved. */
    suspend fun saveToGallery(context: Context, doc: ScanDocument, repository: DocumentRepository): Int = withContext(Dispatchers.IO) {
        var saved = 0
        doc.pages.forEachIndexed { index, page ->
            val file = repository.pageFile(doc.id, page.id)
            if (!file.exists()) return@forEachIndexed
            val name = "${safe(doc.name)}_${index + 1}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CamScaner")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CamScaner").apply { mkdirs() }
                val target = File(dir, name)
                file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
            }
            saved++
        }
        saved
    }

    /**
     * All pages stacked into one tall image (as messaging apps show it), written as JPEG to [out].
     * The width is reduced so the whole picture stays below [MAX_LONG_PIXELS] pixels.
     */
    suspend fun longImage(doc: ScanDocument, repository: DocumentRepository, out: File): File = withContext(Dispatchers.Default) {
        val pages = doc.pages.filter { it.width > 0 && it.height > 0 }
        require(pages.isNotEmpty()) { "no pages" }
        val heightPerWidth = pages.sumOf { it.height.toDouble() / it.width } + GAP_FRACTION * (pages.size - 1)
        var width = min(LONG_WIDTH, pages.minOf { it.width })
        if (width * width * heightPerWidth > MAX_LONG_PIXELS) width = Math.sqrt(MAX_LONG_PIXELS / heightPerWidth).toInt()
        width = max(width, 320)
        val gap = (width * GAP_FRACTION).roundToInt()
        val heights = pages.map { (width.toDouble() * it.height / it.width).roundToInt().coerceAtLeast(1) }
        val total = heights.sum() + gap * (pages.size - 1)
        val canvasBitmap = Bitmap.createBitmap(width, total, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.rgb(236, 239, 238))
            var y = 0
            pages.forEachIndexed { i, page ->
                val bitmap = ImageIO.decodeSampled(repository.pageFile(doc.id, page.id), max(width, heights[i]))
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, Rect(0, y, width, y + heights[i]), null)
                    bitmap.recycle()
                }
                y += heights[i] + gap
            }
            ImageIO.saveJpeg(canvasBitmap, out, 88)
        } finally {
            canvasBitmap.recycle()
        }
        out
    }

    /** Writes the recognised text of every page as plain text. */
    suspend fun textFile(text: String, out: File): File = withContext(Dispatchers.IO) {
        out.parentFile?.mkdirs()
        out.writeText(text, Charsets.UTF_8)
        out
    }

    /** Writes a Word file with the recognised text and, with [withImages], the page images. */
    suspend fun docx(doc: ScanDocument, repository: DocumentRepository, out: File, withImages: Boolean): File = withContext(Dispatchers.IO) {
        out.parentFile?.mkdirs()
        val pages = doc.pages.map { page ->
            DocxPage(
                text = page.ocrText.orEmpty(),
                image = if (withImages) repository.pageFile(doc.id, page.id) else null,
                imageWidth = page.width,
                imageHeight = page.height,
            )
        }
        FileOutputStream(out).use { DocxWriter.write(pages, it) }
        out
    }

    /** Hands a PDF to the system print dialog. */
    fun print(context: Context, pdf: File, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(jobName, PdfPrintAdapter(pdf, jobName), PrintAttributes.Builder().build())
    }

    private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                file.inputStream().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "scan" }.take(60)

    private const val LONG_WIDTH = 1600
    private const val MAX_LONG_PIXELS = 36_000_000.0
    private const val GAP_FRACTION = 0.015
}
