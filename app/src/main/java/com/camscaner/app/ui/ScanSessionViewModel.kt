package com.camscaner.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import com.camscaner.app.ocr.AutoAligner
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.PaperFit
import com.camscaner.app.scanner.PaperPreset
import com.camscaner.app.scanner.ScanFilter
import com.camscaner.app.scanner.rotated
import com.camscaner.app.ui.camera.CameraMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** What the camera is shooting; kept here so the pages taken keep their meaning across screens. */
    var cameraMode by mutableStateOf(CameraMode.DOCUMENT)
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

    /** Name a new document gets unless the user types one; fixed when the scan starts. */
    private var defaultName by mutableStateOf(Share.defaultDocumentName(app))

    /** Name typed for the document during this scan; null keeps its current (or default) name. */
    var customName by mutableStateOf<String?>(null)
        private set

    /** Name shown for the document being scanned into, and saved with it. */
    val documentName: String
        get() = customName ?: targetDocId?.let { repository.get(it)?.name } ?: defaultName

    fun rename(name: String) {
        customName = name.trim().ifEmpty { null }
    }

    /**
     * Bumped when the scan is restarted or abandoned. Photo processing and imports outlive the
     * screens that started them, so they check it before adding pages to a scan that is gone.
     */
    private var generation = 0

    fun start(docId: String?, returnRoute: String? = null) {
        generation++
        clear()
        cameraMode = CameraMode.DOCUMENT
        // a result nobody picked up (the filter screen left early) must not leak into this scan
        savedDocId = null
        saveError = null
        saving = false
        cropReady = false
        targetDocId = docId
        this.returnRoute = returnRoute
        defaultName = Share.defaultDocumentName(scannerApp)
        customName = null
    }

    /** The user left the scan (camera closed): drop its pages and any photo still in progress. */
    fun abandon() {
        generation++
        clear()
        cropReady = false
    }

    fun clear() {
        pages.filter { it.existingPageId == null }.forEach { it.originalFile.delete() }
        pages.clear()
        currentIndex = 0
    }

    /**
     * Normalizes a captured/imported photo, detects the sheet and appends it to the session as
     * one page (or two for a book spread), unless the scan of generation [gen] has been abandoned
     * meanwhile. Returns false when the photo could not be used.
     */
    private suspend fun addPhoto(raw: File, gen: Int, mode: CameraMode = CameraMode.DOCUMENT, fromCamera: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            val out = File(workDir, "${DocumentRepository.newId()}.jpg")
            val size = runCatching { ImageIO.normalize(raw, out) }.getOrNull()
            raw.delete()
            if (size == null) {
                out.delete()
                return@withContext false
            }
            val (w, h) = size
            val drafts: List<DraftPage> = when (mode) {
                CameraMode.PHOTO -> listOf(
                    DraftPage(
                        id = DocumentRepository.newId(), originalFile = out, width = w, height = h,
                        detected = null, corners = null, filter = ScanFilter.ORIGINAL, skew = 0f,
                    )
                )
                CameraMode.BOOK -> {
                    val spread = if (settings.autoCrop) detect(out) else null
                    val (left, right) = PaperFit.split(spread)
                    listOf(left, right).map { half ->
                        DraftPage(
                            id = DocumentRepository.newId(), originalFile = out, width = w, height = h,
                            detected = spread, corners = half, filter = settings.defaultFilter,
                            skew = if (settings.autoStraighten) null else 0f,
                        )
                    }
                }
                else -> {
                    val detected = if (settings.autoCrop) detect(out) else null
                    // ID cards are always cut to the card; other photos to the format chosen in the camera
                    val paper = when {
                        mode == CameraMode.ID_CARD -> PaperPreset.ID_CARD
                        fromCamera -> settings.cameraPaper
                        else -> null
                    }
                    listOf(
                        DraftPage(
                            id = DocumentRepository.newId(), originalFile = out, width = w, height = h,
                            detected = detected,
                            corners = if (paper != null) PaperFit.fit(detected, paper, w, h) else detected,
                            filter = settings.defaultFilter,
                            skew = if (settings.autoStraighten) null else 0f,
                            paper = paper,
                        )
                    )
                }
            }
            val added = withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext false
                pages += drafts
                currentIndex = pages.lastIndex
                if (mode != CameraMode.PHOTO) drafts.forEach { alignLater(it, gen) }
                true
            }
            if (!added) out.delete()
            added
        }

    /**
     * Imports [uris] independently of the calling screen, so a configuration change cannot cut it
     * short; [cropReady] tells the navigation to open the crop screen.
     */
    fun importImages(uris: List<Uri>) {
        val gen = generation
        viewModelScope.launch {
            val imported = importUris(uris, gen)
            if (gen != generation) return@launch
            if (imported < uris.size) {
                Toast.makeText(scannerApp, scannerApp.getString(R.string.import_failed, uris.size - imported), Toast.LENGTH_LONG).show()
            }
            if (pages.isNotEmpty()) cropReady = true
        }
    }

    /**
     * Adds a photo taken by the camera in [mode]. Runs in the ViewModel so leaving the camera
     * meanwhile does not lose it. A single capture replaces the session and then opens the crop
     * screen; the second side of an ID card opens it too.
     */
    fun addCapture(file: File, single: Boolean, mode: CameraMode = cameraMode): Job {
        val gen = generation
        if (single && !mode.collects) clear()
        return viewModelScope.launch {
            addPhoto(file, gen, mode, fromCamera = true)
            if (gen != generation || pages.isEmpty()) return@launch
            val done = when {
                mode == CameraMode.ID_CARD -> pages.count { it.existingPageId == null } >= 2
                mode == CameraMode.BOOK -> false
                else -> single
            }
            if (done) cropReady = true
        }
    }

    fun consumeCropReady(): Boolean = cropReady.also { cropReady = false }

    /** Returns how many of [uris] could be read as images. */
    private suspend fun importUris(uris: List<Uri>, gen: Int): Int {
        importing = true
        var imported = 0
        try {
            for (uri in uris) {
                if (gen != generation) break
                val tmp = File(workDir, "import_${DocumentRepository.newId()}")
                val copied = withContext(Dispatchers.IO) {
                    runCatching { ImageIO.copyUri(scannerApp, uri, tmp) }.isSuccess
                }
                if (!copied) tmp.delete()
                else if (addPhoto(tmp, gen)) imported++
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
            skew = page.skew,
            marks = page.marks,
            paper = page.paper,
            existingPageId = pageId,
        )
    }

    /**
     * Fits the outline of page [index] to [paper]: the largest region with that format's
     * proportions inside the detected sheet (or the user's own outline, or the whole photo), and
     * locks the page to the format. Null frees the outline and brings the detected sheet back.
     */
    fun fitPaper(index: Int, paper: PaperPreset?) = update(index) { p ->
        if (paper == null) {
            p.copy(corners = p.detected, paper = null)
        } else {
            // an outline the user drew by hand counts as the sheet; a fitted one is replaced
            val own = p.paper == null && p.corners != null && p.corners != p.detected && p.corners != Corners.FULL
            val base = if (own) p.corners else p.detected
            p.copy(corners = PaperFit.fit(base, paper, p.width, p.height), paper = paper)
        }
    }

    fun update(index: Int, transform: (DraftPage) -> DraftPage) {
        if (index in pages.indices) pages[index] = transform(pages[index])
    }

    /** Turns page [index] by [delta] degrees clockwise (a multiple of 90), its annotations with it. */
    fun rotate(index: Int, delta: Int) = update(index) { it.turnedTo(it.rotation + delta) }

    /** This page with [rotation] (any multiple of 90); its annotations turn along. */
    private fun DraftPage.turnedTo(rotation: Int): DraftPage {
        val r = (rotation % 360 + 360) % 360
        return if (r == this.rotation) this else copy(rotation = r, marks = marks.map { it.rotated(r - this.rotation) })
    }

    fun remove(index: Int) {
        if (index !in pages.indices) return
        val removed = pages.removeAt(index)
        // the two halves of a book spread share one photo
        val shared = pages.any { it.originalFile.absolutePath == removed.originalFile.absolutePath }
        if (removed.existingPageId == null && !shared) removed.originalFile.delete()
        currentIndex = currentIndex.coerceAtMost(pages.lastIndex).coerceAtLeast(0)
    }

    /**
     * Splits page [index] (a photographed book spread) into its left and right halves as shown,
     * two pages sharing the photo. The second half becomes a new page; marks are dropped since
     * they no longer fit either half.
     */
    fun split(index: Int) {
        val p = pages.getOrNull(index) ?: return
        val (a, b) = PaperFit.split(p.corners ?: p.detected)
        // which half is on the left of the page as the user sees it (the photo may be turned)
        val (first, second) = if (a.rotated(p.rotation).tl.x <= b.rotated(p.rotation).tl.x) a to b else b to a
        pages[index] = p.copy(corners = first, marks = emptyList())
        pages.add(index + 1, p.copy(id = DocumentRepository.newId(), corners = second, marks = emptyList(), existingPageId = null))
        currentIndex = index
    }

    /** Background alignment of new pages, one at a time (OCR is heavy), by page id. */
    private val alignJobs = HashMap<String, Job>()
    private val alignMutex = Mutex()

    /**
     * Finds which way up a new page is from its text, in the background so the crop screen opens
     * at once; the result is applied unless the user has turned the page meanwhile.
     */
    private fun alignLater(draft: DraftPage, gen: Int) {
        if (!settings.autoAlign) return
        alignJobs[draft.id] = viewModelScope.launch {
            try {
                val rotation = alignMutex.withLock {
                    if (gen != generation || pages.none { it.id == draft.id }) return@withLock null
                    val started = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Default) {
                        runCatching { AutoAligner.analyze(draft.originalFile, draft.corners).rotation }.getOrNull()
                    }.also { Log.d(TAG, "aligned ${draft.id} -> $it in ${SystemClock.elapsedRealtime() - started} ms") }
                } ?: return@launch
                val i = pages.indexOfFirst { it.id == draft.id }
                if (gen == generation && i >= 0 && pages[i].rotation == draft.rotation) {
                    update(i) { it.turnedTo(rotation) }
                }
            } finally {
                alignJobs.remove(draft.id)
            }
        }
    }

    /**
     * Turns the page upright by its text and switches it to automatic straightening. Returns
     * false when no text orientation could be found (the page is still straightened).
     */
    suspend fun autoAlign(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        val alignment = withContext(Dispatchers.Default) {
            runCatching { AutoAligner.analyze(page.originalFile, page.corners) }.getOrNull()
        }
        // pages may have been removed, reordered or edited meanwhile; edits win
        val now = pages.indexOfFirst { it.id == page.id }
        if (now >= 0 && pages[now].rotation == page.rotation && pages[now].skew == page.skew) {
            update(now) { it.turnedTo(alignment?.rotation ?: it.rotation).copy(skew = null) }
        }
        return alignment?.rotation != null
    }

    /** Runs the detector on demand (e.g. "auto" pressed on a page that had none). */
    suspend fun autoDetect(index: Int): Corners? {
        val page = pages.getOrNull(index) ?: return null
        val corners = page.detected ?: withContext(Dispatchers.Default) { detect(page.originalFile) }
        // pages may have been removed or reordered while detecting
        val now = pages.indexOfFirst { it.id == page.id }
        update(now) {
            // a page locked to a paper format gets that format's region inside the sheet found
            val fitted = it.paper?.let { paper -> PaperFit.fit(corners, paper, it.width, it.height) }
            it.copy(detected = corners, corners = fitted ?: corners)
        }
        return corners
    }

    /**
     * Renders all pages into the target document under [documentName]. Runs in the ViewModel so
     * leaving or recreating the screen cannot interrupt it half-way; the result arrives in
     * [savedDocId] or [saveError].
     */
    fun save() {
        if (saving || pages.isEmpty()) return
        saving = true
        saveError = null
        saveProgress = 0 to pages.size
        viewModelScope.launch {
            try {
                // pages still being aligned are saved the right way up
                alignJobs.values.toList().joinAll()
                val drafts = pages.toList()
                val id = repository.saveDrafts(targetDocId, documentName, drafts, rename = customName) { done, total ->
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

private const val TAG = "ScanSession"
