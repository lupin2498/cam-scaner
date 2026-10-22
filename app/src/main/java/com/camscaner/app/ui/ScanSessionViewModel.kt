package com.camscaner.app.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.DraftPage
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pages being scanned (camera → crop → filter → save). Lives at activity scope so all
 * screens of the flow share it.
 */
class ScanSessionViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {

    private val scannerApp = app as ScannerApp
    private val repository get() = scannerApp.documents
    private val settings get() = scannerApp.settings
    private val workDir = File(app.cacheDir, "scan").apply { mkdirs() }

    val pages = mutableStateListOf<DraftPage>()

    // Kept in the saved state: after the process is recreated the restored camera screen must
    // still add its pages to the document it was opened from.

    /** Document receiving the pages; null creates a new one. */
    var targetDocId: String?
        get() = state[KEY_TARGET]
        private set(value) {
            state[KEY_TARGET] = value
        }

    /** Navigation route to return to after saving; null opens the saved document. */
    var returnRoute: String?
        get() = state[KEY_RETURN]
        private set(value) {
            state[KEY_RETURN] = value
        }

    var currentIndex by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
        private set
    var saveProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** True from the start of a save until the screen has picked up its result. */
    var saving by mutableStateOf(false)
        private set
    /** Document just saved, waiting to be opened. */
    var savedDocId by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<Throwable?>(null)
        private set

    /** Set when an import or single capture is ready to crop, until the navigation picks it up. */
    var cropReady by mutableStateOf(false)
        private set

    fun start(docId: String?, returnRoute: String? = null) {
        clear()
        // a result nobody picked up (the filter screen left early) must not leak into this scan
        savedDocId = null
        saveError = null
        saving = false
        cropReady = false
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
        if (size == null) {
            out.delete()
            return@withContext null
        }
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

    /**
     * Imports [uris] independently of the calling screen, so a configuration change cannot cut it
     * short; [cropReady] tells the navigation to open the crop screen.
     */
    fun import(uris: List<Uri>) {
        viewModelScope.launch {
            val imported = importUris(uris)
            if (imported < uris.size) {
                Toast.makeText(scannerApp, scannerApp.getString(R.string.import_failed, uris.size - imported), Toast.LENGTH_LONG).show()
            }
            if (pages.isNotEmpty()) cropReady = true
        }
    }

    /**
     * Adds a photo taken by the camera. Runs in the ViewModel so leaving the camera meanwhile does not
     * lose it; a single capture replaces the session and then opens the crop screen.
     */
    fun addCapture(file: File, single: Boolean): Job = viewModelScope.launch {
        if (single) clear()
        addPhoto(file)
        if (single && pages.isNotEmpty()) cropReady = true
    }

    fun consumeCropReady(): Boolean = cropReady.also { cropReady = false }

    /** Returns how many of [uris] could be read as images. */
    private suspend fun importUris(uris: List<Uri>): Int {
        importing = true
        var imported = 0
        try {
            for (uri in uris) {
                val tmp = File(workDir, "import_${DocumentRepository.newId()}")
                val copied = withContext(Dispatchers.IO) {
                    runCatching { ImageIO.copyUri(scannerApp, uri, tmp) }.isSuccess
                }
                if (!copied) tmp.delete()
                else if (addPhoto(tmp) != null) imported++
            }
            currentIndex = 0
        } finally {
            importing = false
        }
        return imported
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
        // pages may have been removed or reordered while detecting
        val now = pages.indexOfFirst { it.id == page.id }
        update(now) { it.copy(detected = corners, corners = corners) }
        return corners
    }

    /**
     * Renders all pages into the target document. Runs in the ViewModel so leaving or recreating
     * the screen cannot interrupt it half-way; the result arrives in [savedDocId] or [saveError].
     */
    fun save(newName: String) {
        if (saving || pages.isEmpty()) return
        saving = true
        saveError = null
        saveProgress = 0 to pages.size
        val drafts = pages.toList()
        viewModelScope.launch {
            try {
                val id = repository.saveDrafts(targetDocId, newName, drafts) { done, total ->
                    saveProgress = done to total
                }
                clear()
                savedDocId = id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saving = false
                saveError = e
            } finally {
                saveProgress = null
            }
        }
    }

    fun consumeSaved(): String? = savedDocId.also {
        savedDocId = null
        saving = false
    }

    fun consumeSaveError(): Throwable? = saveError.also { saveError = null }

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

    private companion object {
        const val KEY_TARGET = "target_doc"
        const val KEY_RETURN = "return_route"
    }
}
