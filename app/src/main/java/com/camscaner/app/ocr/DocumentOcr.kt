package com.camscaner.app.ocr

import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.scanner.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text recognition over whole documents, stored with their pages. */
object DocumentOcr {

    private const val OCR_SIZE = 2400

    /**
     * Makes sure every page of [doc] has its text recognised, reading the pages that have none yet,
     * and returns the document with the texts. [onProgress] gets (done, total) per page read.
     */
    suspend fun ensureText(
        doc: ScanDocument,
        repository: DocumentRepository,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanDocument {
        val missing = doc.pages.filter { it.ocrText == null }
        missing.forEachIndexed { i, page ->
            val text = withContext(Dispatchers.Default) {
                val bitmap = ImageIO.decodeSampled(repository.pageFile(doc.id, page.id), OCR_SIZE) ?: return@withContext ""
                try {
                    OcrEngine.recognize(bitmap).text
                } finally {
                    bitmap.recycle()
                }
            }
            repository.setOcrText(doc.id, page.id, text)
            onProgress(i + 1, missing.size)
        }
        return repository.get(doc.id) ?: doc
    }

    /** The texts of all pages, separated by a page marker line. */
    fun plainText(doc: ScanDocument): String =
        doc.pages.mapIndexed { i, p -> "— Trang ${i + 1} —\n${p.ocrText.orEmpty().trim()}" }.joinToString("\n\n")
}
