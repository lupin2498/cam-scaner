package com.camscaner.app

import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.DraftPage
import com.camscaner.app.export.Exporters
import com.camscaner.app.scanner.DocumentDetector
import com.camscaner.app.scanner.ScanFilter
import com.camscaner.app.ui.ScanSessionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Document storage: trash, folders, merging and moving pages, backups, exports. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ScannerApp
    private val repository: DocumentRepository get() = app.documents
    private val outDir = File(context.cacheDir, "test-repo")

    @Before
    fun setUp() = runBlocking {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val end = System.currentTimeMillis() + 10_000
        while (!repository.loaded.value && System.currentTimeMillis() < end) Thread.sleep(50)
        repository.all.value.forEach { repository.deleteForever(it.id) }
        repository.folders.value.forEach { repository.deleteFolder(it.id) }
    }

    @After
    fun tearDown() = runBlocking {
        repository.all.value.forEach { repository.deleteForever(it.id) }
        repository.folders.value.forEach { repository.deleteFolder(it.id) }
    }

    private fun draft(rx: Double = 0.2): DraftPage {
        val scene = TestImages.documentPhoto(rx = rx, ry = 0.1)
        val file = TestImages.save(scene.bitmap, File(outDir, "${DocumentRepository.newId()}.jpg"))
        val corners = DocumentDetector.detect(scene.bitmap)
        return DraftPage(
            id = DocumentRepository.newId(), originalFile = file, width = scene.bitmap.width, height = scene.bitmap.height,
            detected = corners, corners = corners, filter = ScanFilter.ENHANCE,
        )
    }

    private fun document(name: String, pages: Int): String = runBlocking {
        repository.saveDrafts(null, name, (0 until pages).map { draft(0.1 * it) })
    }

    /** Editing a stored page and splitting it never deletes the photo the stored page still uses. */
    @Test
    fun editingAStoredPageKeepsItsOriginal() = runBlocking {
        val id = document("Tach trang", 1)
        val pageId = repository.get(id)!!.pages[0].id
        val original = repository.originalFile(id, pageId)
        assertTrue(original.exists())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var session: ScanSessionViewModel
        instrumentation.runOnMainSync { session = ScanSessionViewModel(app, SavedStateHandle()) }

        // split, then give up
        instrumentation.runOnMainSync {
            session.editExisting(id, pageId, "page")
            session.split(0)
        }
        assertEquals(2, session.pages.size)
        assertEquals(session.pages[0].originalFile, session.pages[1].originalFile)
        instrumentation.runOnMainSync { session.abandon() }
        assertTrue("giving up the edit deleted the stored photo", original.exists())

        // split, then take both halves out of the session
        instrumentation.runOnMainSync {
            session.editExisting(id, pageId, "page")
            session.split(0)
            session.remove(0)
            session.remove(0)
        }
        assertTrue("removing the halves deleted the stored photo", original.exists())

        // split and save: the new half gets its own copy and the session is cleared as after saving
        instrumentation.runOnMainSync {
            session.editExisting(id, pageId, "page")
            session.split(0)
        }
        repository.saveDrafts(id, "", session.pages.toList())
        instrumentation.runOnMainSync { session.clear() }
        val doc = repository.get(id)!!
        assertEquals(2, doc.pages.size)
        doc.pages.forEach { assertTrue("page ${it.id} lost its photo", repository.originalFile(id, it.id).exists()) }
    }

    @Test
    fun trashKeepsDocumentsUntilRestoredOrRemoved() = runBlocking {
        val id = document("Thung rac", 1)
        repository.delete(id)
        assertTrue(repository.documents.value.none { it.id == id })
        assertEquals(id, repository.trash.value.single().id)
        assertNotNull(repository.get(id)?.deletedAt)
        assertTrue(repository.pageFile(id, repository.get(id)!!.pages[0].id).exists())

        repository.restore(id)
        assertNull(repository.get(id)!!.deletedAt)
        assertTrue(repository.trash.value.isEmpty())
        assertEquals(id, repository.documents.value.single().id)

        repository.delete(id)
        repository.emptyTrash()
        assertNull(repository.get(id))
        assertFalse(repository.docDir(id).exists())
    }

    @Test
    fun foldersFileDocumentsAndFreeThemWhenDeleted() = runBlocking {
        val a = document("A", 1)
        val b = document("B", 1)
        val folder = repository.createFolder("Công việc")
        assertEquals("Công việc", repository.folders.value.single().name)
        repository.moveToFolder(listOf(a), folder)
        assertEquals(folder, repository.get(a)!!.folderId)
        assertNull(repository.get(b)!!.folderId)
        repository.renameFolder(folder, "Việc nhà")
        assertEquals("Việc nhà", repository.folders.value.single().name)
        // a document in the trash whose folder disappears comes back at the top level
        repository.delete(a)
        repository.deleteFolder(folder)
        assertTrue(repository.folders.value.isEmpty())
        repository.restore(a)
        assertNull(repository.get(a)!!.folderId)
    }

    @Test
    fun mergeAppendsPagesAndRemovesTheSources() = runBlocking {
        val target = document("Dich", 1)
        val s1 = document("Nguon 1", 2)
        val s2 = document("Nguon 2", 1)
        val expected = repository.get(target)!!.pages.map { it.id } + repository.get(s1)!!.pages.map { it.id } + repository.get(s2)!!.pages.map { it.id }
        repository.merge(target, listOf(s1, s2))
        val merged = repository.get(target)!!
        assertEquals(expected, merged.pages.map { it.id })
        merged.pages.forEach { assertTrue(repository.pageFile(target, it.id).exists()) }
        assertNull(repository.get(s1))
        assertNull(repository.get(s2))
        assertFalse(repository.docDir(s1).exists())
    }

    @Test
    fun pagesMoveCopyAndStartNewDocuments() = runBlocking {
        val from = document("Tu", 3)
        val to = document("Den", 1)
        val pages = repository.get(from)!!.pages
        // move one page
        repository.movePages(from, listOf(pages[0].id), to, copy = false)
        assertEquals(2, repository.get(from)!!.pages.size)
        assertEquals(listOf(repository.get(to)!!.pages[0].id, pages[0].id), repository.get(to)!!.pages.map { it.id })
        assertTrue(repository.pageFile(to, pages[0].id).exists())
        assertFalse(repository.pageFile(from, pages[0].id).exists())
        // copy one page: the source keeps it and the copy gets a new id
        repository.movePages(from, listOf(pages[1].id), to, copy = true)
        assertEquals(2, repository.get(from)!!.pages.size)
        assertEquals(3, repository.get(to)!!.pages.size)
        assertTrue(repository.get(to)!!.pages.none { it.id == pages[1].id })
        assertTrue(repository.get(to)!!.pages.all { repository.pageFile(to, it.id).exists() })
        // a new document from the rest
        val created = repository.movePages(from, listOf(pages[2].id), null, copy = false, newName = "Moi")!!
        assertEquals("Moi", repository.get(created)!!.name)
        assertEquals(listOf(pages[2].id), repository.get(created)!!.pages.map { it.id })
        assertEquals(listOf(pages[1].id), repository.get(from)!!.pages.map { it.id })
    }

    @Test
    fun duplicateCopiesEveryPage() = runBlocking {
        val id = document("Goc", 2)
        val copy = repository.duplicate(id, "Goc (bản sao)")!!
        val original = repository.get(id)!!
        val duplicate = repository.get(copy)!!
        assertEquals("Goc (bản sao)", duplicate.name)
        assertEquals(2, duplicate.pages.size)
        assertTrue(duplicate.pages.none { d -> original.pages.any { it.id == d.id } })
        duplicate.pages.forEach { assertTrue(repository.pageFile(copy, it.id).exists()) }
    }

    @Test
    fun recognisedTextIsStoredAndSearched() = runBlocking {
        val id = document("Hop dong", 1)
        val page = repository.get(id)!!.pages[0]
        repository.setOcrText(id, page.id, "Hợp đồng thuê nhà số 12345")
        assertTrue(repository.get(id)!!.matches("thuê nhà"))
        assertTrue(repository.get(id)!!.matches("hop dong"))
        assertFalse(repository.get(id)!!.matches("hóa đơn"))
        // rotating re-renders the page, so the old text no longer applies
        repository.updatePage(id, page.id) { it.copy(rotation = 90) }
        assertNull(repository.get(id)!!.pages[0].ocrText)
    }

    @Test
    fun backupRoundTrip() = runBlocking {
        val id = document("Sao luu", 2)
        val folder = repository.createFolder("Lưu trữ")
        repository.moveToFolder(listOf(id), folder)
        val bytes = ByteArrayOutputStream()
        repository.writeBackup(bytes)
        assertTrue(bytes.size() > 10_000)
        val before = repository.get(id)!!

        repository.deleteForever(id)
        repository.deleteFolder(folder)
        assertNull(repository.get(id))

        val count = repository.readBackup(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, count)
        val after = repository.get(id)!!
        assertEquals(before.pages.map { it.id }, after.pages.map { it.id })
        assertEquals(folder, after.folderId)
        assertEquals("Lưu trữ", repository.folders.value.single().name)
        after.pages.forEach { assertTrue(repository.pageFile(id, it.id).exists()) }
        // something else is not a backup
        assertEquals(-1, repository.readBackup(ByteArrayInputStream(ByteArray(100))))
    }

    @Test
    fun longImageAndWordExports() = runBlocking {
        val id = document("Xuat", 3)
        val doc = repository.get(id)!!
        val long = Exporters.longImage(doc, repository, File(outDir, "long.jpg"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(long.absolutePath, bounds)
        assertTrue("${bounds.outWidth} x ${bounds.outHeight}", bounds.outHeight > bounds.outWidth * 3)

        repository.setOcrText(id, doc.pages[0].id, "Trang đầu 12345")
        val docx = Exporters.docx(repository.get(id)!!, repository, File(outDir, "out.docx"), withImages = true)
        val names = ArrayList<String>()
        var documentXml = ""
        ZipInputStream(docx.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                if (e.name == "word/document.xml") documentXml = String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        assertTrue(names.containsAll(listOf("word/document.xml", "word/media/image0.jpg", "word/media/image2.jpg")))
        assertTrue(documentXml.contains("Trang đầu 12345"))
    }
}
