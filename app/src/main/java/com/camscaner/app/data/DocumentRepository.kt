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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stores documents as folders: documents/<docId>/doc.json and pages/<pageId>/{original,page,thumb}.jpg,
 * plus documents/folders.json. The original photo is kept so crop and filters can be changed
 * later without quality loss. Deleted documents stay in the trash for [TRASH_DAYS] days.
 */
class DocumentRepository(context: Context) {

    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val foldersFile = File(root, FOLDERS)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _all = MutableStateFlow<List<ScanDocument>>(emptyList())
    /** Every stored document, the trash included. */
    val all: StateFlow<List<ScanDocument>> = _all.asStateFlow()

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    /** Documents in use (not in the trash), most recently changed first. */
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()

    private val _trash = MutableStateFlow<List<ScanDocument>>(emptyList())
    /** Documents in the trash, most recently deleted first. */
    val trash: StateFlow<List<ScanDocument>> = _trash.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { loadAll() }
    }

    private suspend fun loadAll() = mutex.withLock {
        val docs = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { ScanDocument.fromJson(JSONObject(File(dir, META).readText())) }.getOrNull()
        }.orEmpty()
        // the trash empties itself after a while
        val limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600_000L
        val (expired, kept) = docs.partition { it.deletedAt != null && it.deletedAt < limit }
        expired.forEach { docDir(it.id).deleteRecursively() }
        setAll(kept)
        _folders.value = runCatching {
            val arr = JSONArray(foldersFile.readText())
            List(arr.length()) { Folder.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _loaded.value = true
    }

    fun observe(id: String): Flow<ScanDocument?> =
        all.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun get(id: String): ScanDocument? = _all.value.firstOrNull { it.id == id }

    fun docDir(docId: String) = File(root, docId)
    private fun pageDir(docId: String, pageId: String) = File(docDir(docId), "pages/$pageId")
    fun originalFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "original.jpg")
    fun pageFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "page.jpg")
    fun thumbFile(docId: String, pageId: String) = File(pageDir(docId, pageId), "thumb.jpg")

    /** Bytes used by the document's files. */
    fun sizeOf(docId: String): Long = docDir(docId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Renders and stores [drafts] into document [docId] (a new document named [newName] when null,
     * filed in [folderId]); a non-blank [rename] also renames an existing document.
     * Drafts with [DraftPage.existingPageId] replace that page, the others are appended.
     */
    suspend fun saveDrafts(
        docId: String?,
        newName: String,
        drafts: List<DraftPage>,
        rename: String? = null,
        folderId: String? = null,
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
                    ScanPage(
                        pageId, draft.corners, draft.rotation, draft.filter, draft.adjustments,
                        skew = draft.skew, marks = draft.marks, texts = draft.texts, paper = draft.paper,
                    )
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
                ScanDocument(id, newName, now, now, rendered, folderId = folderId)
            } else {
                val byId = rendered.associateBy { it.id }
                // a re-rendered page keeps the text recognised on it only if nothing about it changed
                val kept = existing.pages.map { old -> byId[old.id]?.let { new -> if (sameLook(old, new)) new.copy(ocrText = old.ocrText) else new } ?: old }
                val added = rendered.filter { r -> existing.pages.none { it.id == r.id } }
                val name = rename?.trim().orEmpty().ifEmpty { existing.name }
                existing.copy(name = name, pages = kept + added, updatedAt = now)
            }
            persist(doc)
        }
        id
    }

    private fun sameLook(a: ScanPage, b: ScanPage) =
        a.corners == b.corners && a.rotation == b.rotation && a.skew == b.skew && a.paper == b.paper && a.marks == b.marks && a.texts == b.texts

    /** Changes a stored page (e.g. rotation) and re-renders it. */
    suspend fun updatePage(docId: String, pageId: String, transform: (ScanPage) -> ScanPage) {
        val current = get(docId)?.pages?.firstOrNull { it.id == pageId } ?: return
        val page = withContext(Dispatchers.Default) { renderPage(docId, transform(current)) }
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            persist(doc.copy(pages = doc.pages.map { if (it.id == pageId) page else it }, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Stores the text recognised on a page without touching its image. */
    suspend fun setOcrText(docId: String, pageId: String, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            persist(doc.copy(pages = doc.pages.map { if (it.id == pageId) it.copy(ocrText = text) else it }))
        }
    }

    suspend fun deletePage(docId: String, pageId: String) = deletePages(docId, listOf(pageId))

    suspend fun deletePages(docId: String, pageIds: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            pageIds.forEach { pageDir(docId, it).deleteRecursively() }
            persist(doc.copy(pages = doc.pages.filterNot { it.id in pageIds }, updatedAt = System.currentTimeMillis()))
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

    /** Moves the document to the trash, where it can be restored for a while. */
    suspend fun delete(docId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            persist(doc.copy(deletedAt = System.currentTimeMillis()))
        }
    }

    suspend fun restore(docId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock
            // its folder may be gone meanwhile
            val folder = doc.folderId?.takeIf { f -> _folders.value.any { it.id == f } }
            persist(doc.copy(deletedAt = null, folderId = folder, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Removes the document and its files for good. */
    suspend fun deleteForever(docId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            docDir(docId).deleteRecursively()
            setAll(_all.value.filterNot { it.id == docId })
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val gone = _all.value.filter { it.deletedAt != null }
            gone.forEach { docDir(it.id).deleteRecursively() }
            setAll(_all.value.filter { it.deletedAt == null })
        }
    }

    /** Appends the pages of [sourceIds] to [targetId] (in that order) and removes the sources. */
    suspend fun merge(targetId: String, sourceIds: List<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = get(targetId) ?: return@withLock
            var pages = target.pages
            val removed = ArrayList<String>()
            for (sourceId in sourceIds) {
                if (sourceId == targetId) continue
                val source = get(sourceId) ?: continue
                source.pages.forEach { movePageDir(sourceId, it.id, targetId) }
                pages = pages + source.pages
                docDir(sourceId).deleteRecursively()
                removed += sourceId
            }
            setAll(_all.value.filterNot { it.id in removed })
            persist(target.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Moves (or with [copy] duplicates) [pageIds] of [fromId] into [toId], or into a new document
     * named [newName] when [toId] is null. Returns the id of the receiving document.
     */
    suspend fun movePages(fromId: String, pageIds: List<String>, toId: String?, copy: Boolean, newName: String = ""): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val from = get(fromId) ?: return@withLock null
                val moving = pageIds.mapNotNull { id -> from.pages.firstOrNull { it.id == id } }
                if (moving.isEmpty()) return@withLock null
                val now = System.currentTimeMillis()
                val target = toId?.let { get(it) } ?: ScanDocument(newId(), newName.ifBlank { from.name }, now, now, emptyList(), folderId = from.folderId)
                val received = moving.map { page ->
                    if (copy) {
                        val id = newId()
                        pageDir(fromId, page.id).copyRecursively(pageDir(target.id, id), overwrite = true)
                        page.copy(id = id, updatedAt = now)
                    } else {
                        movePageDir(fromId, page.id, target.id)
                        page
                    }
                }
                if (!copy && fromId != target.id) {
                    persist(from.copy(pages = from.pages.filterNot { it.id in pageIds }, updatedAt = now))
                }
                val current = if (fromId == target.id) get(target.id) ?: target else target
                persist(current.copy(pages = current.pages + received, updatedAt = now))
                target.id
            }
        }

    /** A copy of the whole document, named after it. */
    suspend fun duplicate(docId: String, name: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = get(docId) ?: return@withLock null
            val now = System.currentTimeMillis()
            val id = newId()
            val pages = doc.pages.map { page ->
                val pageId = newId()
                pageDir(docId, page.id).copyRecursively(pageDir(id, pageId), overwrite = true)
                page.copy(id = pageId, updatedAt = now)
            }
            persist(ScanDocument(id, name, now, now, pages, folderId = doc.folderId))
            id
        }
    }

    private fun movePageDir(fromId: String, pageId: String, toId: String) {
        val src = pageDir(fromId, pageId)
        val dst = pageDir(toId, pageId)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
    }

    // ---- folders ----

    suspend fun createFolder(name: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folder = Folder(newId(), name.trim().ifEmpty { "Thư mục" }, System.currentTimeMillis())
            _folders.value = _folders.value + folder
            persistFolders()
            folder.id
        }
    }

    suspend fun renameFolder(folderId: String, name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _folders.value = _folders.value.map { if (it.id == folderId) it.copy(name = name.trim().ifEmpty { it.name }) else it }
            persistFolders()
        }
    }

    /** Removes the folder; its documents move to the top level. */
    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _folders.value = _folders.value.filterNot { it.id == folderId }
            persistFolders()
            _all.value.filter { it.folderId == folderId }.forEach { persist(it.copy(folderId = null)) }
        }
    }

    suspend fun moveToFolder(docIds: Collection<String>, folderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            docIds.forEach { id -> get(id)?.let { persist(it.copy(folderId = folderId, updatedAt = System.currentTimeMillis())) } }
        }
    }

    private fun persistFolders() {
        foldersFile.writeText(JSONArray().apply { _folders.value.forEach { put(it.toJson()) } }.toString())
    }

    // ---- backup ----

    /** Writes every document, page image and folder into a zip. */
    suspend fun writeBackup(out: OutputStream, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        ZipOutputStream(out.buffered()).use { zip ->
            files.forEachIndexed { i, f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress((i + 1f) / files.size)
            }
        }
    }

    /**
     * Restores a backup written by [writeBackup]; documents already present are replaced, folders
     * merged. Returns how many documents the backup held, or -1 when it was not a backup.
     */
    suspend fun readBackup(input: InputStream): Int = withContext(Dispatchers.IO) {
        val staging = File(root.parentFile, "restore_${newId()}").apply { mkdirs() }
        try {
            var docs = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(staging, entry.name)
                    // never write outside the staging folder, whatever the zip says
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        if (target.name == META) docs++
                    }
                    zip.closeEntry()
                }
            }
            if (docs == 0) return@withContext -1
            mutex.withLock {
                staging.listFiles()?.forEach { entry ->
                    if (entry.isDirectory && File(entry, META).exists()) {
                        val dst = File(root, entry.name)
                        dst.deleteRecursively()
                        if (!entry.renameTo(dst)) entry.copyRecursively(dst, overwrite = true)
                    }
                }
                val restored = File(staging, FOLDERS)
                if (restored.exists()) {
                    val arr = JSONArray(restored.readText())
                    val incoming = List(arr.length()) { Folder.fromJson(arr.getJSONObject(it)) }
                    val known = _folders.value.map { it.id }.toSet()
                    _folders.value = _folders.value + incoming.filterNot { it.id in known }
                    persistFolders()
                }
            }
            loadAll()
            docs
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun renderPage(docId: String, page: ScanPage): ScanPage {
        val bitmap = PageProcessor.render(
            originalFile(docId, page.id), page.corners, page.rotation, page.filter, page.adjustments,
            skew = page.skew, marks = page.marks, paper = page.paper, texts = page.texts,
        )
        try {
            ImageIO.saveJpeg(bitmap, pageFile(docId, page.id), 92)
            val thumb = thumbnail(bitmap, THUMB_SIZE)
            ImageIO.saveJpeg(thumb, thumbFile(docId, page.id), 82)
            if (thumb !== bitmap) thumb.recycle()
            // the look changed, so text read from the old image no longer matches it
            return page.copy(width = bitmap.width, height = bitmap.height, updatedAt = System.currentTimeMillis(), ocrText = null)
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
        setAll(_all.value.filterNot { it.id == doc.id } + doc)
    }

    /** Must be called with [mutex] held; keeps the three lists in step. */
    private fun setAll(docs: List<ScanDocument>) {
        val sorted = docs.sortedByDescending { it.updatedAt }
        _all.value = sorted
        _documents.value = sorted.filter { it.deletedAt == null }
        _trash.value = sorted.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    }

    companion object {
        private const val META = "doc.json"
        private const val FOLDERS = "folders.json"
        private const val THUMB_SIZE = 480
        const val TRASH_DAYS = 30

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
