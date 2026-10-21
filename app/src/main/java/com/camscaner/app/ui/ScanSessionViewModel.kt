package com.camscaner.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.DraftPage
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pages being scanned (camera → crop → filter → save). Lives at activity scope so all
 * screens of the flow share it.
 */
class ScanSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val scannerApp = app as ScannerApp
    private val repository get() = scannerApp.documents
    private val settings get() = scannerApp.settings
    private val workDir = File(app.cacheDir, "scan").apply { mkdirs() }

    val pages = mutableStateListOf<DraftPage>()

    /** Document receiving the pages; null creates a new one. */
    var targetDocId by mutableStateOf<String?>(null)
        private set

    /** Navigation route to return to after saving; null opens the saved document. */
    var returnRoute: String? = null
        private set

    var currentIndex by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
        private set
    var saveProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    fun start(docId: String?, returnRoute: String? = null) {
        clear()
        targetDocId = docId
        this.returnRoute = returnRoute
    }

    fun clear() {
        pages.filter { it.existingPageId == null }.forEach { it.originalFile.delete() }
        pages.clear()
        currentIndex = 0
    }

    /** Normalizes a captured/imported photo, detects the sheet and appends it to the session. */
    suspend fun addPhoto(raw: File): DraftPage? = withContext(Dispatchers.Default) {
        val out = File(workDir, "${DocumentRepository.newId()}.jpg")
        val size = runCatching { ImageIO.normalize(raw, out) }.getOrNull()
        raw.delete()
        if (size == null) return@withContext null
        val detected = if (settings.autoCrop) detect(out) else null
        val draft = DraftPage(
            id = DocumentRepository.newId(),
            originalFile = out,
            width = size.first,
            height = size.second,
            detected = detected,
            corners = detected,
            filter = settings.defaultFilter,
        )
        withContext(Dispatchers.Main) {
            pages += draft
            currentIndex = pages.lastIndex
        }
        draft
    }

    suspend fun importUris(uris: List<Uri>) {
        importing = true
        try {
            for (uri in uris) {
                val tmp = File(workDir, "import_${DocumentRepository.newId()}")
                val copied = withContext(Dispatchers.IO) {
                    runCatching { ImageIO.copyUri(scannerApp, uri, tmp) }.isSuccess
                }
                if (copied) addPhoto(tmp)
            }
            currentIndex = 0
        } finally {
            importing = false
        }
    }

    /** Loads an existing page so its crop or filter can be changed. */
    fun editExisting(docId: String, pageId: String, returnRoute: String) {
        start(docId, returnRoute)
        val page = repository.get(docId)?.pages?.firstOrNull { it.id == pageId } ?: return
        val original = repository.originalFile(docId, pageId)
        val (w, h) = ImageIO.readSize(original)
        pages += DraftPage(
            id = pageId,
            originalFile = original,
            width = w,
            height = h,
            detected = page.corners,
            corners = page.corners,
            rotation = page.rotation,
            filter = page.filter,
            adjustments = page.adjustments,
            existingPageId = pageId,
        )
    }

    fun update(index: Int, transform: (DraftPage) -> DraftPage) {
        if (index in pages.indices) pages[index] = transform(pages[index])
    }

    fun remove(index: Int) {
        if (index !in pages.indices) return
        val removed = pages.removeAt(index)
        if (removed.existingPageId == null) removed.originalFile.delete()
        currentIndex = currentIndex.coerceAtMost(pages.lastIndex).coerceAtLeast(0)
    }

    /** Runs the detector on demand (e.g. "auto" pressed on a page that had none). */
    suspend fun autoDetect(index: Int): Corners? {
        val page = pages.getOrNull(index) ?: return null
        val corners = page.detected ?: withContext(Dispatchers.Default) { detect(page.originalFile) }
        update(index) { it.copy(detected = corners, corners = corners) }
        return corners
    }

    /** Renders all pages into the target document and returns its id. */
    suspend fun save(newName: String): String {
        saveProgress = 0 to pages.size
        try {
            val id = repository.saveDrafts(targetDocId, newName, pages.toList()) { done, total ->
                saveProgress = done to total
            }
            clear()
            return id
        } finally {
            saveProgress = null
        }
    }

    private fun detect(file: File): Corners? {
        val bitmap = ImageIO.decodeSampled(file, 1024) ?: return null
        return try {
            DocumentDetector.detect(bitmap)
        } catch (e: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun onCleared() {
        clear()
    }
}
