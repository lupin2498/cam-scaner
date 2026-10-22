package com.camscaner.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.DraftPage
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.pdf.PageMargin
import com.camscaner.app.pdf.PaperSize
import com.camscaner.app.pdf.PdfOptions
import com.camscaner.app.pdf.PdfQuality
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.ScanFilter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PdfExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ScannerApp
    private val repository: DocumentRepository get() = app.documents
    private val outDir = File(context.cacheDir, "test-pdf")
    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    @After
    fun tearDown() = runBlocking {
        created.forEach { repository.delete(it) }
    }

    private fun draft(scene: TestImages.Scene, filter: ScanFilter): DraftPage {
        val file = TestImages.save(scene.bitmap, File(outDir, "${DocumentRepository.newId()}.jpg"))
        val corners = DocumentDetector.detect(scene.bitmap)
        return DraftPage(
            id = DocumentRepository.newId(),
            originalFile = file,
            width = scene.bitmap.width,
            height = scene.bitmap.height,
            detected = corners,
            corners = corners,
            filter = filter,
        )
    }

    private fun document(vararg drafts: DraftPage): ScanDocument = runBlocking {
        val id = repository.saveDrafts(null, "Test ${drafts.size}", drafts.toList())
        created += id
        repository.get(id)!!
    }

    private fun export(doc: ScanDocument, options: PdfOptions): File = runBlocking {
        app.pdfExporter.export(doc, options, File(outDir, "${options.fileName}.pdf"))
    }

    private fun pageSizes(file: File): List<Pair<Int, Int>> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } } }
        }

    @Test
    fun autoPaperSizeMatchesA4() {
        val doc = document(draft(TestImages.documentPhoto(), ScanFilter.MAGIC))
        val pdf = export(doc, PdfOptions(fileName = "auto"))
        val sizes = pageSizes(pdf)
        assertEquals(1, sizes.size)
        val (w, h) = sizes[0]
        assertTrue("page $w x $h", abs(w - 595) <= 1 && abs(h - 842) <= 1)
    }

    @Test
    fun marginsAndFixedSizes() {
        val doc = document(draft(TestImages.documentPhoto(), ScanFilter.GRAY))
        val letter = pageSizes(export(doc, PdfOptions(fileName = "letter", paperSize = PaperSize.LETTER, margin = PageMargin.LARGE)))
        assertEquals(612 to 792, letter[0])
        val fit = pageSizes(export(doc, PdfOptions(fileName = "fit", paperSize = PaperSize.FIT_IMAGE)))
        assertTrue(fit[0].first < fit[0].second)
    }

    @Test
    fun searchableTextLayer() {
        val doc = document(draft(TestImages.documentPhoto(rx = 0.1, ry = 0.05), ScanFilter.MAGIC))
        val pdf = export(doc, PdfOptions(fileName = "ocr", ocrLayer = true))
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("OCR text missing: $text", text.replace(" ", "").contains("12345"))
    }

    @Test
    fun passwordProtection() {
        val doc = document(draft(TestImages.documentPhoto(), ScanFilter.BW))
        val pdf = export(doc, PdfOptions(fileName = "locked", password = "1234"))
        try {
            PDDocument.load(pdf).close()
            fail("opened without password")
        } catch (e: InvalidPasswordException) {
            // expected
        }
        PDDocument.load(pdf, "1234").use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun watermarkPageNumbersAndQualities() {
        val doc = document(
            draft(TestImages.documentPhoto(), ScanFilter.MAGIC),
            draft(TestImages.documentPhoto(rx = -0.2), ScanFilter.NO_SHADOW),
        )
        val pdf = export(doc, PdfOptions(fileName = "marked", watermark = "BẢN SAO", pageNumbers = true, margin = PageMargin.SMALL))
        // rotated watermark text comes out one glyph per line, so compare without whitespace
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), "")
        assertTrue("watermark/page numbers missing: $text", text.contains("1/2") && text.contains("2/2") && text.contains("SAO"))
        val low = export(doc, PdfOptions(fileName = "low", quality = PdfQuality.LOW))
        val high = export(doc, PdfOptions(fileName = "high", quality = PdfQuality.ORIGINAL))
        assertTrue("low ${low.length()} >= high ${high.length()}", low.length() < high.length())
    }

    @Test
    fun blackAndWhitePagesAreSmall() {
        val scene = TestImages.documentPhoto()
        val color = export(document(draft(scene, ScanFilter.MAGIC)), PdfOptions(fileName = "color"))
        val bw = export(document(draft(scene, ScanFilter.BW)), PdfOptions(fileName = "bw"))
        assertTrue("bw ${bw.length()} color ${color.length()}", bw.length() < color.length())
    }

    @Test
    fun idCardsShareOnePage() {
        val front = TestImages.documentPhoto(paperRatio = 85.6 / 53.98, rx = 0.15, ry = 0.1, distance = 2.6)
        val back = TestImages.documentPhoto(paperRatio = 85.6 / 53.98, rx = -0.1, ry = 0.15, distance = 2.6)
        val doc = document(draft(front, ScanFilter.LIGHTEN), draft(back, ScanFilter.LIGHTEN))
        val merged = pageSizes(export(doc, PdfOptions(fileName = "cards", mergeIdCards = true)))
        assertEquals(1, merged.size)
        // PdfRenderer truncates the A4 size 595.28 x 841.89 pt
        assertEquals(595 to 841, merged[0])
        val separate = pageSizes(export(doc, PdfOptions(fileName = "cards2", mergeIdCards = false)))
        assertEquals(2, separate.size)
    }

    @Test
    fun reorderRotateAndDeletePages() = runBlocking {
        val doc = document(
            draft(TestImages.documentPhoto(), ScanFilter.MAGIC),
            draft(TestImages.documentPhoto(rx = -0.2), ScanFilter.GRAY),
            draft(TestImages.documentPhoto(ry = -0.2), ScanFilter.BW),
        )
        val ids = doc.pages.map { it.id }
        repository.reorder(doc.id, ids.reversed())
        assertEquals(ids.reversed(), repository.get(doc.id)!!.pages.map { it.id })

        val first = repository.get(doc.id)!!.pages.first()
        repository.updatePage(doc.id, first.id) { it.copy(rotation = 90) }
        val rotated = repository.get(doc.id)!!.pages.first()
        assertEquals(first.width, rotated.height)
        assertEquals(first.height, rotated.width)
        val pdf = export(repository.get(doc.id)!!, PdfOptions(fileName = "rotated"))
        val sizes = pageSizes(pdf)
        assertTrue("first page should be landscape: ${sizes[0]}", sizes[0].first > sizes[0].second)

        repository.deletePage(doc.id, ids[1])
        assertEquals(2, repository.get(doc.id)!!.pages.size)
        repository.rename(doc.id, "Đổi tên")
        assertEquals("Đổi tên", repository.get(doc.id)!!.name)
    }
}
