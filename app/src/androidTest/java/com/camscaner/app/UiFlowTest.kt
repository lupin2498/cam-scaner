package com.camscaner.app

import android.Manifest
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.camscaner.app.ui.Share
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ScannerApp
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun clean() = runBlocking {
        // a cold emulator may show "launcher isn't responding" on top of the app
        TestImages.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        waitForRepository()
        app.documents.all.value.forEach { app.documents.deleteForever(it.id) }
        app.documents.folders.value.forEach { app.documents.deleteFolder(it.id) }
        app.settings.batchMode = false
        app.settings.autoCapture = false
        app.settings.defaultFilter = com.camscaner.app.scanner.ScanFilter.ENHANCE
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun waitForRepository() {
        val end = System.currentTimeMillis() + 10_000
        while (!app.documents.loaded.value && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    private fun ComposeTestRule.waitText(text: String, timeout: Long = 20_000) =
        waitOrDump(hasText(text, substring = true), "text '$text'", timeout)

    private fun ComposeTestRule.waitDescription(text: String, timeout: Long = 20_000) =
        waitOrDump(hasContentDescription(text), "description '$text'", timeout)

    /** The shutter is disabled until the camera is bound. */
    private fun waitShutterReady() =
        compose.waitOrDump(hasContentDescription(str(R.string.camera_capture)) and isEnabled(), "shutter enabled", 30_000)

    /** Waits for a node; on timeout saves a screenshot and the semantics tree for the CI log. */
    private fun ComposeTestRule.waitOrDump(matcher: SemanticsMatcher, what: String, timeout: Long) {
        try {
            waitUntilAtLeastOneExists(matcher, timeout)
        } catch (e: Throwable) {
            TestImages.screenshot("fail_" + what.filter { it.isLetterOrDigit() }.take(24))
            val tree = runCatching { onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }.getOrElse { "tree unavailable: $it" }
            Log.e(TAG, "Timed out waiting for $what\n$tree")
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /**
     * The test clock drives composition, so let pending frames run before capturing the screen,
     * then give the last frame time to reach the display.
     */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        TestImages.screenshot(name)
    }

    /** Filter of the first page in the activity's scan session. */
    private fun filterOfDraft(): com.camscaner.app.scanner.ScanFilter? {
        var filter: com.camscaner.app.scanner.ScanFilter? = null
        scenario?.onActivity { activity ->
            val session = androidx.lifecycle.ViewModelProvider(activity)[com.camscaner.app.ui.ScanSessionViewModel::class.java]
            filter = session.pages.firstOrNull()?.filter
        }
        return filter
    }

    /** Waits until no busy indicator (indeterminate progress) is shown, e.g. a preview being rendered. */
    private fun waitUntilIdleProgress(timeout: Long = 30_000) {
        val busy = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        compose.waitUntil(timeout) { compose.onAllNodes(busy).fetchSemanticsNodes().isEmpty() }
    }

    /** First page of the activity's scan session. */
    private fun firstDraft(): com.camscaner.app.data.DraftPage? = draft(0)

    /** Page [index] of the activity's scan session. */
    private fun draft(index: Int): com.camscaner.app.data.DraftPage? {
        var page: com.camscaner.app.data.DraftPage? = null
        scenario?.onActivity { activity ->
            val session = androidx.lifecycle.ViewModelProvider(activity)[com.camscaner.app.ui.ScanSessionViewModel::class.java]
            page = session.pages.getOrNull(index)
        }
        return page
    }

    private companion object {
        const val TAG = "CamScanerTest"
    }

    /** A page photographed upside down is turned upright on import; straighten panel and auto-align. */
    @Test
    fun upsideDownImportIsAlignedAndStraightened() {
        val scene = TestImages.turned(TestImages.documentPhoto(rx = 0.15, ry = 0.08), 180)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "exports/upside_down.jpg"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)

        compose.waitDescription(str(R.string.crop_editor), 30_000)
        // the orientation is found in the background
        compose.waitUntil(60_000) { firstDraft()?.rotation == 180 }
        assertEquals(null, firstDraft()?.skew)
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitText(str(R.string.filter_original))

        // turn the page the wrong way, then let auto-align fix it again
        compose.onNodeWithText(str(R.string.crop_rotate_left)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.rotation == 90 }
        compose.onNodeWithText(str(R.string.filter_straighten)).performScrollTo().performClick()
        compose.waitText(str(R.string.filter_straighten_auto))
        waitUntilIdleProgress()
        shot("17_straighten")
        compose.onAllNodesWithText(str(R.string.filter_auto_align)).onFirst().performClick()
        // the confirmation snackbar is too short-lived to poll for; the page itself must be upright
        compose.waitUntil(60_000) { firstDraft()?.rotation == 180 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(str(R.string.filter_aligning)).fetchSemanticsNodes().isEmpty() }

        // a manual angle replaces the automatic one
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.waitUntil(5_000) { firstDraft()?.skew == 3f }
        Thread.sleep(500)
        waitUntilIdleProgress()
        shot("18_straighten_manual")
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val doc = app.documents.documents.value.single()
        val page = doc.pages.single()
        assertEquals(180, page.rotation)
        assertEquals(3f, page.skew)
        // the saved image is turned clockwise by the manual 3°, so levelling it takes -3°
        val saved = android.graphics.BitmapFactory.decodeFile(app.documents.pageFile(doc.id, page.id).absolutePath)
        val tilt = com.camscaner.app.scanner.PageAligner.measure(saved)
        assertTrue("measured $tilt", tilt != null && kotlin.math.abs(tilt.correction + 3f) < 0.6f)
    }

    @Test
    fun homeShowsEmptyStateAndSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText(str(R.string.home_empty_title))
        shot("01_home_empty")
        // settings live in the "more" menu of the home screen
        compose.onNodeWithContentDescription(str(R.string.home_more)).performClick()
        compose.onNodeWithText(str(R.string.home_settings)).performClick()
        compose.waitText(str(R.string.settings_default_filter))
        shot("02_settings")
        compose.onNodeWithText(str(R.string.filter_bw)).performClick()
        assertEquals(com.camscaner.app.scanner.ScanFilter.BW, app.settings.defaultFilter)
        app.settings.defaultFilter = com.camscaner.app.scanner.ScanFilter.ENHANCE
    }

    /** Shared image → crop → filter → save → document → export PDF. */
    @Test
    fun importFilterSaveAndExport() {
        val scene = TestImages.documentPhoto()
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "exports/shared_test.jpg"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)

        compose.waitText(str(R.string.crop_title))
        compose.waitDescription(str(R.string.crop_editor))
        Thread.sleep(800)
        shot("03_crop")

        // dragging a corner moves it by the whole drag, past the touch slop
        val before = firstDraft()!!.corners!!
        val editor = compose.onNodeWithContentDescription(str(R.string.crop_editor))
        val size = editor.fetchSemanticsNode().size
        val pad = 28 * context.resources.displayMetrics.density
        val scale = minOf((size.width - 2 * pad) / scene.bitmap.width, (size.height - 2 * pad) / scene.bitmap.height)
        val dw = scene.bitmap.width * scale
        val dh = scene.bitmap.height * scale
        val start = Offset((size.width - dw) / 2f + before.tl.x * dw, (size.height - dh) / 2f + before.tl.y * dh)
        editor.performTouchInput { swipe(start, start + Offset(150f, 200f), 600) }
        compose.waitForIdle()
        val after = firstDraft()!!.corners!!
        val movedX = (after.tl.x - before.tl.x) * dw
        val movedY = (after.tl.y - before.tl.y) * dh
        assertTrue("corner moved by ($movedX, $movedY) of (150, 200)", movedX in 120f..155f && movedY in 160f..205f)
        assertEquals(before.br, after.br)
        // "all" keeps the whole photo, "auto" brings back the detected sheet
        compose.onNodeWithText(str(R.string.crop_full)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.corners == null }
        compose.onNodeWithText(str(R.string.crop_auto)).performClick()
        compose.waitUntil(10_000) { firstDraft()?.corners == before }
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()

        compose.waitDescription(str(R.string.save))
        compose.waitText(str(R.string.filter_original))
        Thread.sleep(1500)
        waitUntilIdleProgress()
        shot("04_filter_enhance")
        // the filter strip scrolls horizontally; B&W is past the screen edge on a phone
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(str(R.string.filter_bw)))
        compose.waitForIdle()
        Thread.sleep(500)
        compose.onNodeWithText(str(R.string.filter_bw)).performClick()
        compose.waitUntil(10_000) { app.documents.documents.value.isEmpty() && filterOfDraft() == com.camscaner.app.scanner.ScanFilter.BW }
        Thread.sleep(1500)
        waitUntilIdleProgress()
        shot("05_filter_bw")
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()

        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        compose.waitText(str(R.string.doc_reorder_hint))
        Thread.sleep(800)
        shot("06_document")
        val docs = app.documents.documents.value
        assertEquals(1, docs.size)
        assertEquals(1, docs[0].pages.size)
        assertEquals(com.camscaner.app.scanner.ScanFilter.BW, docs[0].pages[0].filter)

        compose.onNodeWithText(str(R.string.doc_export_pdf)).performClick()
        compose.waitText(str(R.string.export_start))
        Thread.sleep(800)
        shot("07_export_options")
        compose.onNodeWithText(str(R.string.export_start)).performClick()
        compose.waitText(str(R.string.export_done, "").substringBefore("(").trim(), 60_000)
        Thread.sleep(500)
        shot("08_export_done")
        val pdf = File(context.cacheDir, "exports").listFiles()!!.filter { it.extension == "pdf" }
        assertTrue("no pdf in ${pdf.map { it.name }}", pdf.isNotEmpty())
    }

    @Test
    fun pageViewerRotateAndOcr() {
        // a document created through the repository, then opened from the home list
        val scene = TestImages.documentPhoto(rx = 0.1, ry = 0.05)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "scan/viewer_test.jpg"))
        val draft = com.camscaner.app.data.DraftPage(
            id = "p1", originalFile = file, width = scene.bitmap.width, height = scene.bitmap.height,
            detected = scene.corners, corners = scene.corners,
        )
        val docId = runBlocking { app.documents.saveDrafts(null, "Hoa don thang 10", listOf(draft)) }
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitText("Hoa don thang 10")
        shot("09_home_list")
        compose.onNodeWithText("Hoa don thang 10").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithText("1").performClick()
        compose.waitText(str(R.string.page_ocr))
        Thread.sleep(800)
        shot("10_page_viewer")

        val before = app.documents.get(docId)!!.pages[0]
        compose.onNodeWithText(str(R.string.page_rotate)).performClick()
        compose.waitUntil(20_000) { app.documents.get(docId)!!.pages[0].rotation == 90 }
        val after = app.documents.get(docId)!!.pages[0]
        assertEquals(before.width, after.height)

        compose.onNodeWithText(str(R.string.page_ocr)).performClick()
        compose.waitText(str(R.string.copy), 60_000)
        shot("11_ocr")
        compose.onNodeWithText("12345", substring = true).assertExists()
    }

    @Test
    fun cameraCaptureFlow() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitDescription(str(R.string.home_scan))
        compose.onNodeWithContentDescription(str(R.string.home_scan)).performClick()
        compose.waitText(str(R.string.camera_mode_batch))
        waitShutterReady()
        Thread.sleep(1500)
        shot("12_camera")
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitDescription(str(R.string.crop_editor), 30_000)
        Thread.sleep(1000)
        shot("13_camera_crop")
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitDescription(str(R.string.save))
        Thread.sleep(1500)
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 30_000)
        shot("14_camera_saved")
        assertEquals(1, app.documents.documents.value.size)
    }

    @Test
    fun renameDocumentFromHome() {
        val scene = TestImages.documentPhoto()
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "scan/rename_test.jpg"))
        val draft = com.camscaner.app.data.DraftPage(
            id = "p2", originalFile = file, width = scene.bitmap.width, height = scene.bitmap.height,
            detected = null, corners = null,
        )
        val docId = runBlocking { app.documents.saveDrafts(null, "Ten cu", listOf(draft)) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Ten cu")
        compose.onNodeWithText("Ten cu").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithContentDescription(str(R.string.rename)).performClick()
        compose.waitText(str(R.string.rename_title))
        compose.onNode(hasSetTextAction()).performTextReplacement("Ten moi")
        compose.onNodeWithText(str(R.string.save)).performClick()
        compose.waitUntil(10_000) { app.documents.get(docId)?.name == "Ten moi" }
    }

    private fun seedDocument(name: String, pages: Int = 1): String {
        val drafts = (0 until pages).map { i ->
            val scene = TestImages.documentPhoto(rx = 0.1 * i, ry = 0.05)
            val file = TestImages.save(scene.bitmap, File(context.cacheDir, "scan/seed_${name.hashCode()}_$i.jpg"))
            com.camscaner.app.data.DraftPage(
                id = "seed$i", originalFile = file, width = scene.bitmap.width, height = scene.bitmap.height,
                detected = scene.corners, corners = scene.corners,
            )
        }
        return runBlocking { app.documents.saveDrafts(null, name, drafts) }
    }

    /** Page viewer → re-crop → filter → save returns to the viewer with the page updated. */
    @Test
    fun recropAndRefilterExistingPage() {
        val docId = seedDocument("Sua lai trang")
        val before = app.documents.get(docId)!!.pages[0]
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Sua lai trang")
        compose.onNodeWithText("Sua lai trang").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithText("1").performClick()
        compose.waitText(str(R.string.page_recrop))
        compose.onNodeWithText(str(R.string.page_recrop)).performClick()

        compose.waitText(str(R.string.crop_full))
        compose.onNodeWithText(str(R.string.crop_full)).performClick()
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitText(str(R.string.filter_original))
        // the title is the document's name; renaming it here renames the document on saving
        compose.waitText("Sua lai trang")
        compose.onNodeWithContentDescription(str(R.string.filter_rename)).performClick()
        compose.waitText(str(R.string.rename_title))
        compose.onNode(hasSetTextAction()).performTextReplacement("Trang da sua")
        compose.onNodeWithText(str(R.string.save)).performClick()
        compose.waitText("Trang da sua")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(str(R.string.filter_gray)))
        compose.onNodeWithText(str(R.string.filter_gray)).performClick()
        Thread.sleep(1000)
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()

        // back on the page viewer, not a new document
        compose.waitText(str(R.string.page_ocr), 30_000)
        shot("15_after_recrop")
        val docs = app.documents.documents.value
        assertEquals(1, docs.size)
        assertEquals("Trang da sua", docs[0].name)
        val after = docs[0].pages.single()
        assertEquals(before.id, after.id)
        assertEquals(null, after.corners)
        assertEquals(com.camscaner.app.scanner.ScanFilter.GRAY, after.filter)
        assertTrue(after.updatedAt > before.updatedAt)
    }

    @Test
    fun deletePageFromViewer() {
        val docId = seedDocument("Xoa trang", pages = 2)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Xoa trang")
        compose.onNodeWithText("Xoa trang").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithText("2").performClick()
        compose.waitText(str(R.string.page_ocr))
        compose.onNodeWithText(str(R.string.delete)).performClick()
        compose.waitText(str(R.string.page_delete_title))
        compose.onNode(hasText(str(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { app.documents.get(docId)!!.pages.size == 1 }
    }

    /** Filter screen tools: rename, compare, filter strength, pen, signature and text extraction. */
    @Test
    fun filterToolsMarkupSignatureAndText() {
        val scene = TestImages.documentPhoto(rx = 0.1, ry = 0.05)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "exports/tools_test.jpg"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)

        compose.waitDescription(str(R.string.crop_editor), 30_000)
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitText(str(R.string.filter_original))
        waitUntilIdleProgress()

        // the title is the document name: a new scan gets the default one, tapping renames it
        compose.waitText(str(R.string.default_doc_name, "").trim())
        compose.onNodeWithContentDescription(str(R.string.filter_rename)).performClick()
        compose.waitText(str(R.string.rename_title))
        compose.onNode(hasSetTextAction()).performTextReplacement("Hop dong thue nha")
        compose.onNodeWithText(str(R.string.save)).performClick()
        compose.waitText("Hop dong thue nha")

        // holding "compare" shows the page without its filter
        compose.onNodeWithText(str(R.string.filter_compare)).performTouchInput { down(center) }
        compose.waitDescription(str(R.string.filter_compare), 5_000)
        shot("19_compare")
        compose.onNodeWithText(str(R.string.filter_compare)).performTouchInput { up() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(str(R.string.filter_compare)).fetchSemanticsNodes().isEmpty()
        }

        // tapping the chosen filter again shows its strength
        compose.onNodeWithText(str(R.string.filter_enhance)).performClick()
        compose.waitText(str(R.string.filter_strength))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitUntil(5_000) { firstDraft()?.adjustments?.strength == 0.5f }
        Thread.sleep(300)
        waitUntilIdleProgress()
        shot("20_strength")

        // a pen stroke across the top of the page
        compose.onNodeWithText(str(R.string.filter_markup)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.markup_hint))
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.7f, height * 0.33f), 400)
        }
        compose.waitForIdle()
        shot("21_markup")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.marks?.size == 1 }

        // a signature: drawn on the pad (two strokes), then placed on the page
        compose.onNodeWithText(str(R.string.filter_sign)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.sign_pad_title))
        compose.onNodeWithContentDescription(str(R.string.sign_pad_title)).performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.6f), Offset(width * 0.5f, height * 0.3f), 300)
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.8f, height * 0.65f), 300)
        }
        compose.waitForIdle()
        shot("22_signature_pad")
        compose.onNodeWithText(str(R.string.sign_place)).performClick()
        compose.waitText(str(R.string.sign_hint))
        compose.waitForIdle()
        shot("23_signature_place")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.marks?.size == 3 }
        val marks = firstDraft()!!.marks
        assertEquals(0, marks[0].group)
        assertTrue("signature strokes not grouped: ${marks.map { it.group }}", marks.drop(1).all { it.group == 1 })

        // the text of the page, ready to copy or share
        compose.onNodeWithText(str(R.string.filter_extract_text)).performScrollTo().performClick()
        compose.waitText("12345", 60_000)
        shot("24_extract_text")
        compose.onNodeWithText(str(R.string.close)).performClick()

        Thread.sleep(300)
        waitUntilIdleProgress()
        shot("25_filter_marked")
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val doc = app.documents.documents.value.single()
        assertEquals("Hop dong thue nha", doc.name)
        val page = doc.pages.single()
        assertEquals(3, page.marks.size)
        assertEquals(0.5f, page.adjustments.strength)
        // the red pen stroke is part of the saved page image
        val saved = android.graphics.BitmapFactory.decodeFile(app.documents.pageFile(doc.id, page.id).absolutePath)
        val stroke = page.marks[0]
        val n = stroke.points.size / 2
        val red = (0 until n).count { i ->
            val x = (stroke.points[2 * i] * (saved.width - 1)).toInt()
            val y = (stroke.points[2 * i + 1] * (saved.height - 1)).toInt()
            val px = saved.getPixel(x, y)
            // JPEG blurs the colour of a thin line; it stays clearly red, unlike text or paper
            android.graphics.Color.red(px) - android.graphics.Color.green(px) > 60
        }
        assertTrue("red ink at $red of $n stroke points", red > n / 2)
    }

    /** Several pages: marks belong to their page and turn with it, a signature is moved and undone at once. */
    @Test
    fun marksOnSeveralPages() {
        val uris = (0 until 2).map { i ->
            val scene = TestImages.documentPhoto(rx = 0.05 * (i + 1), ry = 0.05)
            Share.uriFor(context, TestImages.save(scene.bitmap, File(context.cacheDir, "exports/batch_$i.jpg")))
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/jpeg")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)

        compose.waitDescription(str(R.string.crop_editor), 30_000)
        compose.waitUntil(20_000) { draft(1) != null }
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        // exact match: the crop screen title also contains "1/2"
        compose.waitOrDump(hasText("1/2"), "page 1/2", 20_000)
        waitUntilIdleProgress()

        // a stroke on page 1; turning the page turns the stroke with it
        compose.onNodeWithText(str(R.string.filter_markup)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.markup_hint))
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.4f), Offset(width * 0.6f, height * 0.42f), 300)
        }
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { draft(0)?.marks?.size == 1 }
        val stroke = draft(0)!!.marks[0]
        val turned = (draft(0)!!.rotation + 270) % 360
        compose.onNodeWithText(str(R.string.crop_rotate_left)).performClick()
        compose.waitUntil(5_000) { draft(0)?.rotation == turned }
        assertEquals(stroke.rotated(270), draft(0)!!.marks.single())

        // page 2 gets a signature, dragged from the centre towards the bottom right
        compose.onNodeWithContentDescription(str(R.string.page_next)).performClick()
        // exact match: the crop screen title also contains "2/2"
        compose.waitOrDump(hasText("2/2"), "page 2/2", 20_000)
        waitUntilIdleProgress()
        compose.onNodeWithText(str(R.string.filter_sign)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.sign_pad_title))
        compose.onNodeWithContentDescription(str(R.string.sign_pad_title)).performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.6f), Offset(width * 0.5f, height * 0.3f), 300)
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.8f, height * 0.6f), 300)
        }
        compose.onNodeWithText(str(R.string.sign_place)).performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(center, Offset(center.x + width * 0.2f, center.y + height * 0.25f), 500)
        }
        compose.waitForIdle()
        shot("26_signature_moved")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { draft(1)?.marks?.isNotEmpty() == true }
        val signature = draft(1)!!.marks
        assertTrue(signature.all { it.group == 1 })
        val xs = signature.flatMap { m -> m.points.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = signature.flatMap { m -> m.points.filterIndexed { i, _ -> i % 2 == 1 } }
        assertTrue("signature not moved: x ${xs.average()} y ${ys.average()}", xs.average() > 0.55 && ys.average() > 0.6)
        assertEquals(1, draft(0)!!.marks.size)

        // one undo in the pen editor removes the whole signature
        compose.onNodeWithText(str(R.string.filter_markup)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.markup_hint))
        compose.onNodeWithText(str(R.string.markup_undo)).performClick()
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { draft(1)?.marks?.isEmpty() == true }

        waitUntilIdleProgress()
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val pages = app.documents.documents.value.single().pages
        assertEquals(2, pages.size)
        assertEquals(turned, pages[0].rotation)
        assertEquals(listOf(stroke.rotated(270)), pages[0].marks)
        assertTrue(pages[1].marks.isEmpty())
    }

    /** A saved page keeps its marks: they turn with the page in the viewer and the filter screen adds to them. */
    @Test
    fun existingPageKeepsItsMarks() {
        val scene = TestImages.documentPhoto(rx = 0.1, ry = 0.05)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "scan/marks_test.jpg"))
        val mark = com.camscaner.app.scanner.MarkStroke(listOf(0.2f, 0.2f, 0.8f, 0.25f), android.graphics.Color.RED, 0.006f)
        val draft = com.camscaner.app.data.DraftPage(
            id = "m1", originalFile = file, width = scene.bitmap.width, height = scene.bitmap.height,
            detected = scene.corners, corners = scene.corners, marks = listOf(mark),
        )
        val docId = runBlocking { app.documents.saveDrafts(null, "Co chu ky", listOf(draft)) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Co chu ky")
        compose.onNodeWithText("Co chu ky").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithText("1").performClick()
        compose.waitText(str(R.string.page_ocr))

        // turning the page in the viewer turns its marks
        compose.onNodeWithText(str(R.string.page_rotate)).performClick()
        compose.waitUntil(20_000) { app.documents.get(docId)!!.pages[0].rotation == 90 }
        assertEquals(listOf(mark.rotated(90)), app.documents.get(docId)!!.pages[0].marks)

        // the filter screen shows them and adds new ones
        compose.waitUntil(10_000) { compose.onAllNodesWithText(str(R.string.page_rotating)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(str(R.string.page_filter)).performClick()
        compose.waitText(str(R.string.filter_original))
        compose.waitText("Co chu ky")
        assertEquals(listOf(mark.rotated(90)), firstDraft()!!.marks)
        waitUntilIdleProgress()
        // a pen stroke, then a highlighter stroke
        compose.onNodeWithText(str(R.string.filter_markup)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.markup_hint))
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.6f), Offset(width * 0.6f, height * 0.62f), 300)
        }
        compose.onNodeWithText(str(R.string.markup_highlighter)).performClick()
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(Offset(width * 0.25f, height * 0.45f), Offset(width * 0.75f, height * 0.45f), 300)
        }
        shot("27_markup_highlighter")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.marks?.size == 3 }
        val drawn = firstDraft()!!.marks
        assertTrue("pen stroke drawn as highlighter", !drawn[1].highlighter)
        assertTrue("highlighter stroke drawn as pen", drawn[2].highlighter)
        // undo takes back the last stroke only
        waitUntilIdleProgress()
        compose.onNodeWithText(str(R.string.filter_markup)).performScrollTo().performClick()
        compose.waitDescription(str(R.string.markup_hint))
        compose.onNodeWithText(str(R.string.markup_undo)).performClick()
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.marks?.size == 2 }
        assertEquals(drawn.take(2), firstDraft()!!.marks)
        waitUntilIdleProgress()
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()

        compose.waitText(str(R.string.page_ocr), 30_000)
        compose.waitUntil(10_000) { app.documents.get(docId)!!.pages[0].marks.size == 2 }
        val doc = app.documents.get(docId)!!
        assertEquals("Co chu ky", doc.name)
        assertEquals(90, doc.pages[0].rotation)
        assertEquals(mark.rotated(90), doc.pages[0].marks[0])
    }

    /** "Add with camera" from a document appends the capture to that document. */
    @Test
    fun addPageWithCameraToExistingDocument() {
        val docId = seedDocument("Them trang")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Them trang")
        compose.onNodeWithText("Them trang").performClick()
        compose.waitText(str(R.string.doc_add_camera))
        compose.onNodeWithText(str(R.string.doc_add_camera)).performClick()
        waitShutterReady()
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitDescription(str(R.string.crop_editor), 30_000)
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitDescription(str(R.string.save))
        Thread.sleep(1000)
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitUntil(30_000) { app.documents.get(docId)?.pages?.size == 2 }
        compose.waitText(str(R.string.doc_reorder_hint))
        shot("16_two_pages")
        assertEquals(1, app.documents.documents.value.size)
    }

    /** True when [p] lies inside the convex quadrilateral [q]. */
    private fun inside(q: com.camscaner.app.scanner.Corners, p: com.camscaner.app.scanner.Pt): Boolean {
        val pts = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (cross > 1e-5f) 1 else if (cross < -1e-5f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Paper formats fit the outline inside the detected sheet; "Chia đôi" turns a page into two. */
    @Test
    fun paperFormatsAndSplitOnTheCropScreen() {
        val scene = TestImages.documentPhoto(rx = 0.2, ry = 0.1)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "exports/paper_test.jpg"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)
        compose.waitDescription(str(R.string.crop_editor), 30_000)
        val detected = firstDraft()!!.detected!!

        compose.onNodeWithText(str(R.string.paper_square)).performScrollTo().performClick()
        compose.waitUntil(5_000) { firstDraft()?.paper == com.camscaner.app.scanner.PaperPreset.SQUARE }
        val fitted = firstDraft()!!.corners!!
        assertTrue("outline unchanged", fitted != detected)
        fitted.points.forEach { assertTrue("corner $it outside the sheet", inside(detected, it)) }
        Thread.sleep(500)
        shot("28_crop_paper")
        compose.onNodeWithText(str(R.string.paper_free)).performScrollTo().performClick()
        compose.waitUntil(5_000) { firstDraft()?.paper == null && firstDraft()?.corners == detected }

        // the page is split into its left and right halves, which share the photo
        compose.onNodeWithText(str(R.string.crop_split)).performClick()
        compose.waitUntil(5_000) { draft(1) != null }
        assertEquals(draft(0)!!.originalFile, draft(1)!!.originalFile)
        assertTrue(draft(0)!!.corners!!.tr.x <= draft(1)!!.corners!!.tr.x)
        // the "split" snackbar must be gone before the tool row is tapped
        compose.waitUntil(10_000) { compose.onAllNodesWithText(str(R.string.crop_split_done)).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitDescription(str(R.string.save))
        waitUntilIdleProgress()
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val doc = app.documents.documents.value.single()
        assertEquals(2, doc.pages.size)
        // each half of the A4 sheet is close to an A5
        doc.pages.forEach { assertEquals(1 / kotlin.math.sqrt(2.0), it.width.toDouble() / it.height, 0.06) }
    }

    /** Folders, selecting several documents, the trash and searching recognised text. */
    @Test
    fun foldersSelectionTrashAndSearch() {
        val a = seedDocument("Tai lieu A")
        val b = seedDocument("Tai lieu B")
        runBlocking { app.documents.setOcrText(b, app.documents.get(b)!!.pages[0].id, "hop dong thue nha") }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Tai lieu A")

        // a new folder; the list then shows that (empty) folder
        compose.onNodeWithText(str(R.string.home_new_folder)).performClick()
        compose.waitText(str(R.string.home_folder_name))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Cong viec")
        compose.onNodeWithText(str(R.string.save)).performClick()
        compose.waitUntil(10_000) { app.documents.folders.value.size == 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tai lieu A").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(str(R.string.home_folders_all)).performClick()
        compose.waitText("Tai lieu A")

        // long press selects; the selection is filed in the folder
        compose.onNodeWithText("Tai lieu A").performTouchInput { longClick() }
        compose.waitText(str(R.string.home_selected, 1))
        compose.onNodeWithContentDescription(str(R.string.home_move_to_folder)).performClick()
        compose.waitText(str(R.string.home_no_folder))
        compose.onNode(hasText("Cong viec") and hasAnyAncestor(isDialog())).performClick()
        val folderId = app.documents.folders.value.single().id
        compose.waitUntil(10_000) { app.documents.get(a)?.folderId == folderId }
        compose.onNodeWithText("Cong viec").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tai lieu B").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tai lieu A").assertExists()
        shot("29_home_folders")

        // B goes to the trash and comes back
        compose.onNodeWithText(str(R.string.home_folders_all)).performClick()
        compose.waitText("Tai lieu B")
        compose.onNodeWithText("Tai lieu B").performTouchInput { longClick() }
        compose.waitText(str(R.string.home_selected, 1))
        compose.onNodeWithContentDescription(str(R.string.delete)).performClick()
        compose.waitText(str(R.string.home_delete_title))
        compose.onNode(hasText(str(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { app.documents.trash.value.size == 1 && app.documents.documents.value.size == 1 }
        compose.onNodeWithContentDescription(str(R.string.home_more)).performClick()
        compose.onNodeWithText(str(R.string.home_trash), substring = true).performClick()
        compose.waitText(str(R.string.trash_title))
        compose.waitText("Tai lieu B")
        shot("30_trash")
        compose.onNodeWithText(str(R.string.trash_restore)).performClick()
        compose.waitUntil(10_000) { app.documents.trash.value.isEmpty() && app.documents.documents.value.size == 2 }
        compose.onNodeWithContentDescription(str(R.string.back)).performClick()

        // search finds the words read on B's page
        compose.waitText("Tai lieu A")
        compose.onNode(hasSetTextAction()).performTextReplacement("thue nha")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tai lieu A").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tai lieu B").assertExists()
    }

    /** A PDF shared to the app becomes one page per PDF page. */
    @Test
    fun sharedPdfIsImportedPageByPage() {
        val pdf = TestImages.samplePdf(File(context.cacheDir, "exports/sample.pdf"), pages = 2)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, pdf))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)
        compose.waitDescription(str(R.string.crop_editor), 60_000)
        compose.waitUntil(30_000) { draft(1) != null }
        assertEquals(null, firstDraft()!!.corners)
        assertEquals(com.camscaner.app.scanner.ScanFilter.ORIGINAL, firstDraft()!!.filter)
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitDescription(str(R.string.save))
        waitUntilIdleProgress()
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val doc = app.documents.documents.value.single()
        assertEquals(2, doc.pages.size)
        // a PDF page keeps its A4 proportions
        assertEquals(595.0 / 842.0, doc.pages[0].width.toDouble() / doc.pages[0].height, 0.01)
    }

    /** A text box is typed, placed and saved with the page. */
    @Test
    fun textToolAddsWordsToThePage() {
        val scene = TestImages.documentPhoto(rx = 0.1, ry = 0.05)
        val file = TestImages.save(scene.bitmap, File(context.cacheDir, "exports/text_test.jpg"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Share.uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)
        compose.waitDescription(str(R.string.crop_editor), 30_000)
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitText(str(R.string.filter_original))
        waitUntilIdleProgress()

        compose.onNodeWithText(str(R.string.markup_text)).performScrollTo().performClick()
        compose.waitText(str(R.string.text_dialog_title))
        compose.onNode(hasSetTextAction()).performTextReplacement("DA THANH TOAN")
        compose.onNodeWithText(str(R.string.ok)).performClick()
        compose.waitDescription(str(R.string.markup_hint))
        // dragged a little towards the bottom right, then enlarged with the corner handle
        compose.onNodeWithContentDescription(str(R.string.markup_hint)).performTouchInput {
            swipe(center, Offset(center.x + width * 0.1f, center.y + height * 0.15f), 400)
        }
        compose.waitForIdle()
        shot("31_text_box")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.texts?.size == 1 }
        val text = firstDraft()!!.texts.single()
        assertEquals("DA THANH TOAN", text.text)
        assertTrue("text not moved: ${'$'}{text.cx}, ${'$'}{text.cy}", text.cx > 0.55f && text.cy > 0.6f)
        // turning the page turns the text with it
        compose.onNodeWithText(str(R.string.crop_rotate_left)).performClick()
        compose.waitUntil(5_000) { firstDraft()?.texts?.single()?.angle == 270 }
        waitUntilIdleProgress()
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 60_000)
        val page = app.documents.documents.value.single().pages.single()
        assertEquals(1, page.texts.size)
        assertEquals(270, page.texts[0].angle)
    }

    /** The export screen also writes text and Word files. */
    @Test
    fun exportTextAndWordFiles() {
        seedDocument("Van ban")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText("Van ban")
        compose.onNodeWithText("Van ban").performClick()
        compose.waitText(str(R.string.doc_export_pdf))
        compose.onNodeWithText(str(R.string.doc_export_pdf)).performClick()
        compose.waitText(str(R.string.export_start))
        val done = str(R.string.export_file_done, "").substringBefore("(").trim()
        compose.onNodeWithText(str(R.string.export_txt)).performScrollTo().performClick()
        compose.waitText(done, 90_000)
        val txt = File(context.cacheDir, "exports").listFiles()!!.filter { it.extension == "txt" }
        assertTrue("no txt in ${'$'}{txt.map { it.name }}", txt.isNotEmpty())
        assertTrue(txt.first().readText().contains("12345"))
        shot("32_export_txt")
        compose.onNodeWithText(str(R.string.export_docx)).performScrollTo().performClick()
        compose.waitUntil(60_000) { File(context.cacheDir, "exports").listFiles()!!.any { it.extension == "docx" && it.length() > 0 } }
    }

    /** ID card mode collects the two sides of the card before the crop screen. */
    @Test
    fun cameraIdCardModeTakesBothSides() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitDescription(str(R.string.home_scan))
        compose.onNodeWithContentDescription(str(R.string.home_scan)).performClick()
        compose.waitText(str(R.string.camera_mode_id_card))
        compose.onNodeWithText(str(R.string.camera_mode_id_card)).performClick()
        waitShutterReady()
        Thread.sleep(800)
        shot("33_camera_id_card")
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitUntil(30_000) { firstDraft() != null }
        waitShutterReady()
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitDescription(str(R.string.crop_editor), 30_000)
        compose.waitUntil(5_000) { draft(1) != null }
        assertEquals(com.camscaner.app.scanner.PaperPreset.ID_CARD, draft(0)!!.paper)
        assertEquals(com.camscaner.app.scanner.PaperPreset.ID_CARD, draft(1)!!.paper)
    }

    /** Book mode makes two pages of every shot. */
    @Test
    fun cameraBookModeSplitsEveryShot() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitDescription(str(R.string.home_scan))
        compose.onNodeWithContentDescription(str(R.string.home_scan)).performClick()
        compose.waitText(str(R.string.camera_mode_book))
        compose.onNodeWithText(str(R.string.camera_mode_book)).performClick()
        waitShutterReady()
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitUntil(30_000) { draft(1) != null }
        assertEquals(draft(0)!!.originalFile, draft(1)!!.originalFile)
        shot("34_camera_book")
        compose.onNodeWithContentDescription(str(R.string.done)).performClick()
        compose.waitDescription(str(R.string.crop_editor), 30_000)
    }
}
