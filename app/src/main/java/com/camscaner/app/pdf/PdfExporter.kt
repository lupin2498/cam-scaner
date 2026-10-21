package com.camscaner.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.ocr.OcrEngine
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.ScanFilter
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.Calendar
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the PDF: page sizing, margins, compression per filter (1-bit for B&W pages,
 * JPEG otherwise), optional invisible OCR text layer, watermark, page numbers and encryption.
 */
class PdfExporter(private val repository: DocumentRepository) {

    suspend fun export(
        document: ScanDocument,
        options: PdfOptions,
        output: File,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.Default) {
        output.parentFile?.mkdirs()
        val pages = document.pages
        val sheets = PdfLayout.plan(
            sizes = pages.map { it.width.coerceAtLeast(1) to it.height.coerceAtLeast(1) },
            paper = options.paperSize,
            orientation = options.orientation,
            margin = options.margin,
            mergeIdCards = options.mergeIdCards,
        )
        PDDocument().use { pdf ->
            pdf.documentInformation.apply {
                title = options.fileName
                creator = CREATOR
                producer = CREATOR
                creationDate = Calendar.getInstance()
            }
            val needsUnicode = options.ocrLayer || options.watermark.isNotBlank()
            val font: PDFont = (if (needsUnicode) loadUnicodeFont(pdf) else null) ?: PDType1Font.HELVETICA
            val glyphCache = HashMap<Char, Boolean>()

            sheets.forEachIndexed { sheetIndex, sheet ->
                ensureActive()
                val pdPage = PDPage(PDRectangle(sheet.width, sheet.height))
                pdf.addPage(pdPage)
                PDPageContentStream(pdf, pdPage, PDPageContentStream.AppendMode.OVERWRITE, true, true).use { cs ->
                    for (item in sheet.items) {
                        val page = pages[item.index]
                        val file = repository.pageFile(document.id, page.id)
                        val bitmap = ImageIO.decodeSampled(file, options.quality.maxDim) ?: continue
                        try {
                            val image = if (page.filter == ScanFilter.BW) bilevelImage(pdf, bitmap)
                            else JPEGFactory.createFromImage(pdf, bitmap, options.quality.jpegQuality / 100f)
                            cs.drawImage(image, item.x, item.y, item.width, item.height)
                            if (options.ocrLayer) addTextLayer(cs, font, glyphCache, file, item)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (options.watermark.isNotBlank()) drawWatermark(cs, font, glyphCache, sheet, options.watermark)
                    if (options.pageNumbers) drawPageNumber(cs, sheet, sheetIndex + 1, sheets.size, options.margin)
                }
                onProgress((sheetIndex + 1f) / sheets.size)
            }

            if (options.password.isNotEmpty()) {
                val policy = StandardProtectionPolicy(options.password, options.password, AccessPermission())
                policy.encryptionKeyLength = 256
                pdf.protect(policy)
            }
            pdf.save(output)
        }
        output
    }

    /** Lossless 1-bit image: B&W scans stay razor sharp and tiny. */
    private fun bilevelImage(pdf: PDDocument, bitmap: Bitmap): PDImageXObject {
        val w = bitmap.width
        val h = bitmap.height
        val rowBytes = (w + 7) / 8
        val row = IntArray(w)
        val packed = ByteArray(rowBytes)
        val bytes = ByteArrayOutputStream(rowBytes * h / 8 + 1024)
        DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION)).use { out ->
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                packed.fill(0)
                for (x in 0 until w) {
                    val c = row[x]
                    val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    if (luma >= 128) packed[x shr 3] = (packed[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                out.write(packed)
            }
        }
        return PDImageXObject(pdf, ByteArrayInputStream(bytes.toByteArray()), COSName.FLATE_DECODE, w, h, 1, PDDeviceGray.INSTANCE)
    }

    /** Invisible text positioned over each recognised line, so the PDF is searchable and selectable. */
    private suspend fun addTextLayer(
        cs: PDPageContentStream,
        font: PDFont,
        glyphCache: MutableMap<Char, Boolean>,
        pageFile: File,
        item: Placement,
    ) {
        val bitmap = ImageIO.decodeSampled(pageFile, OCR_MAX_DIM) ?: return
        val result = try {
            OcrEngine.recognize(bitmap)
        } catch (e: Exception) {
            return
        } finally {
            bitmap.recycle()
        }
        for (line in result.lines) {
            val text = printable(line.text, font, glyphCache)
            if (text.isBlank()) continue
            val boxW = line.box.width() * item.width
            val boxH = line.box.height() * item.height
            if (boxW <= 1f || boxH <= 1f) continue
            val fontSize = boxH * 0.85f
            val textWidth = try {
                font.getStringWidth(text) / 1000f * fontSize
            } catch (e: Exception) {
                continue
            }
            if (textWidth <= 0f) continue
            val x = item.x + line.box.left * item.width
            val baseline = item.y + (1f - line.box.bottom) * item.height + boxH * 0.18f
            cs.beginText()
            cs.setFont(font, fontSize)
            cs.setRenderingMode(RenderingMode.NEITHER)
            cs.setHorizontalScaling(boxW / textWidth * 100f)
            cs.newLineAtOffset(x, baseline)
            cs.showText(text)
            cs.endText()
        }
    }

    private fun drawWatermark(cs: PDPageContentStream, font: PDFont, glyphCache: MutableMap<Char, Boolean>, sheet: Sheet, raw: String) {
        val text = printable(raw.trim(), font, glyphCache)
        if (text.isBlank()) return
        var size = min(sheet.width, sheet.height) / 9f
        val width0 = runCatching { font.getStringWidth(text) / 1000f }.getOrElse { return }
        val diagonal = kotlin.math.hypot(sheet.width, sheet.height) * 0.75f
        if (width0 * size > diagonal) size = diagonal / width0
        val textWidth = width0 * size
        val state = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.16f }
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(state)
        cs.setNonStrokingColor(0.35f)
        cs.beginText()
        cs.setFont(font, size)
        val angle = kotlin.math.atan2(sheet.height, sheet.width).toDouble()
        cs.setTextMatrix(Matrix.getRotateInstance(angle, sheet.width / 2f, sheet.height / 2f))
        cs.newLineAtOffset(-textWidth / 2f, -size / 3f)
        cs.showText(text)
        cs.endText()
        cs.restoreGraphicsState()
    }

    private fun drawPageNumber(cs: PDPageContentStream, sheet: Sheet, number: Int, total: Int, margin: PageMargin) {
        val font = PDType1Font.HELVETICA
        val size = 9f
        val text = "$number / $total"
        val w = font.getStringWidth(text) / 1000f * size
        val y = max(10f, margin.points / 2f - size / 2f)
        cs.saveGraphicsState()
        cs.setNonStrokingColor(0.45f)
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset((sheet.width - w) / 2f, y)
        cs.showText(text)
        cs.endText()
        cs.restoreGraphicsState()
    }

    /** Keeps only characters the font can encode; strips accents for the Latin-1 fallback font. */
    private fun printable(text: String, font: PDFont, cache: MutableMap<Char, Boolean>): String {
        val source = if (font is PDType1Font) stripAccents(text) else text
        val sb = StringBuilder(source.length)
        for (ch in source) {
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(' ')
                continue
            }
            val ok = cache.getOrPut(ch) {
                try {
                    font.encode(ch.toString())
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) sb.append(ch)
        }
        return sb.toString()
    }

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun loadUnicodeFont(pdf: PDDocument): PDFont? {
        for (path in FONT_CANDIDATES) {
            val f = File(path)
            if (!f.exists()) continue
            try {
                return PDType0Font.load(pdf, f)
            } catch (e: Exception) {
                // try the next one
            }
        }
        return null
    }

    companion object {
        private const val CREATOR = "Cam Scaner"
        private const val OCR_MAX_DIM = 2400
        private val FONT_CANDIDATES = listOf(
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
    }
}
