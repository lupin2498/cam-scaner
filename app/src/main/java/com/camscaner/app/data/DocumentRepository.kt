package com.camscaner.app.data

import android.content.Context
import android.graphics.Bitmap
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.PageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stores documents as folders: documents/<docId>/doc.json and pages/<pageId>/{original,page,thumb}.jpg.
 * The original photo is kept so crop and filters can be changed later without quality loss.
 */
class DocumentRepository(context: Context) {

    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { loadAll() }
    }

    private suspend fun loadAll() = mutex.withLock {
        val docs = root.listFiles()?.mapNotNull { dir ->
            runCatching { ScanDocument.fromJson(JSONObject(File(dir, META).readText())) }.getOrNull()
        }.orEmpty()
        _documents.value = docs.sortedByDescending { it.updatedAt }
        _loaded.value = true
    }

    fun observe(id: String): Flow<ScanDocument?> =
        documents.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun get(id: String): ScanDocument? = _documents.value.firstOrNull { it.id == id }

    fun docDir(docId: String) = File(root, docId)
    private fun pageDir(docId: String, pageId: String) = File(docDir(docId), "pages/$pageId")
    fun originalFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "original.jpg")
    fun pageFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "page.jpg")
    fun thumbFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "thumb.jpg")

    /**
     * Renders and stores [drafts] into document [docId] (a new document named [newName] when null).
     * Drafts with [DraftPage.existingPageId] replace that page, the others are appended.
     */
    suspend fun saveDrafts(
        docId: String?,
        newName: String,
        drafts: List<DraftPage>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.Default) {
        val id = docId ?: newId()
        val isNew = docId == null
        val addedPages = ArrayList<String>()
        val rendered = try {
            drafts.mapIndexed { index, draft ->
                val pageId = draft.existingPageId ?: newId().also { addedPages += it }
                val original = originalFile(id, pageId)
                if (draft.originalFile.absolutePath != original.absolutePath) {
                    original.parentFile?.mkdirs()
                    draft.originalFile.copyTo(original, overwrite = true)
                }
                val page = renderPage(
                    id,
                    ScanPage(pageId, draft.corners, draft.rotation, draft.filter, draft.adjustments, skew = draft.skew)
                )
                onProgress(index + 1, drafts.size)
                page
            }
        } catch (e: Throwable) {
            // nothing references the copied originals yet; do not leave megabytes behind on every retry
            if (isNew) docDir(id).deleteRecursively()
            else addedPages.forEach { pageDir(id, it).deleteRecursively() }
            throw e
        }
        mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = get(id)
            val doc = if (existing == null) {
                ScanDocument(id, newName, now, now, rendered)
            } else {
                val byId = rendered.associateBy { it.id }
                val kept = existing.pages.map { byId[it.id] ?: it }
                val added = rendered.filter { r -> existing.pages.none { it.id == r.id } }
                existing.copy(pages = kept + added, updatedAt = now)
            }
            persist(doc)
        }
        id
    }

    /** Changes a stored page (e.g. rotation) and re-renders it. */
    suspend fun updatePage(docId: String, pageId: String, transform: (ScanPage) -> ScanPage) {
        val current = get(docId)?.pages?.firstOrNull { it.id == pageId } ?: return
        val page = withContext(Dispatchers.Default) { renderPage(docId, transform(current)) }
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            persist(doc.copy(pages = doc.pages.map { if (it.id == pageId) page else it }, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deletePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            pageDir(docId, pageId).deleteRecursively()
            persist(doc.copy(pages = doc.pages.filterNot { it.id == pageId }, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun reorder(docId: String, pageIds: List<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            val byId = doc.pages.associateBy { it.id }
            val ordered = pageIds.mapNotNull { byId[it] } + doc.pages.filterNot { it.id in pageIds }
            persist(doc.copy(pages = ordered, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun rename(docId: String, name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            persist(doc.copy(name = name.trim().ifEmpty { doc.name }, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun delete(docId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            docDir(docId).deleteRecursively()
            _documents.value = _documents.value.filterNot { it.id == docId }
        }
    }

    private fun renderPage(docId: String, page: ScanPage): ScanPage {
        val bitmap = PageProcessor.render(
            originalFile(docId, page.id), page.corners, page.rotation, page.filter, page.adjustments, skew = page.skew,
        )
        try {
            ImageIO.saveJpeg(bitmap, pageFile(docId, page.id), 92)
            val thumb = thumbnail(bitmap, THUMB_SIZE)
            ImageIO.saveJpeg(thumb, thumbFile(docId, page.id), 82)
            if (thumb !== bitmap) thumb.recycle()
            return page.copy(width = bitmap.width, height = bitmap.height, updatedAt = System.currentTimeMillis())
        } finally {
            bitmap.recycle()
        }
    }

    private fun thumbnail(src: Bitmap, maxDim: Int): Bitmap {
        val s = maxDim.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** Must be called with [mutex] held. */
    private fun persist(doc: ScanDocument) {
        val dir = docDir(doc.id).apply { mkdirs() }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(doc.toJson().toString())
        if (!tmp.renameTo(File(dir, META))) {
            tmp.copyTo(File(dir, META), overwrite = true)
            tmp.delete()
        }
        _documents.value = (_documents.value.filterNot { it.id == doc.id } + doc).sortedByDescending { it.updatedAt }
    }

    companion object {
        private const val META = "doc.json"
        private const val THUMB_SIZE = 480

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
