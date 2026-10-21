package com.camscaner.app

import android.Manifest
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
        app.documents.documents.value.forEach { app.documents.delete(it.id) }
        app.settings.batchMode = false
        app.settings.autoCapture = false
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

    /** Waits for a node; on timeout saves a screenshot and the semantics tree for the CI log. */
    private fun ComposeTestRule.waitOrDump(matcher: SemanticsMatcher, what: String, timeout: Long) {
        try {
            waitUntilAtLeastOneExists(matcher, timeout)
        } catch (e: Throwable) {
            TestImages.screenshot("fail_" + what.filter { it.isLetterOrDigit() }.take(24))
            val tree = runCatching { onAllNodes(isRoot()).printToString() }.getOrElse { "tree unavailable: $it" }
            Log.e(TAG, "Timed out waiting for $what\n$tree")
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private companion object {
        const val TAG = "CamScanerTest"
    }

    @Test
    fun homeShowsEmptyStateAndSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText(str(R.string.home_empty_title))
        TestImages.screenshot("01_home_empty")
        compose.onNodeWithContentDescription(str(R.string.home_settings)).performClick()
        compose.waitText(str(R.string.settings_default_filter))
        TestImages.screenshot("02_settings")
        compose.onNodeWithText(str(R.string.filter_bw)).performClick()
        assertEquals(com.camscaner.app.scanner.ScanFilter.BW, app.settings.defaultFilter)
        app.settings.defaultFilter = com.camscaner.app.scanner.ScanFilter.MAGIC
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
        compose.waitText(str(R.string.crop_auto))
        Thread.sleep(800)
        TestImages.screenshot("03_crop")
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()

        compose.waitText(str(R.string.filter_title))
        compose.waitText(str(R.string.filter_bw))
        Thread.sleep(1500)
        TestImages.screenshot("04_filter_magic")
        compose.onNodeWithText(str(R.string.filter_bw)).performClick()
        Thread.sleep(1500)
        TestImages.screenshot("05_filter_bw")
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()

        compose.waitText(str(R.string.doc_export_pdf), 30_000)
        compose.waitText(str(R.string.doc_reorder_hint))
        Thread.sleep(800)
        TestImages.screenshot("06_document")
        val docs = app.documents.documents.value
        assertEquals(1, docs.size)
        assertEquals(1, docs[0].pages.size)
        assertEquals(com.camscaner.app.scanner.ScanFilter.BW, docs[0].pages[0].filter)

        compose.onNodeWithText(str(R.string.doc_export_pdf)).performClick()
        compose.waitText(str(R.string.export_start))
        Thread.sleep(800)
        TestImages.screenshot("07_export_options")
        compose.onNodeWithText(str(R.string.export_start)).performClick()
        compose.waitText(str(R.string.export_done, "").substringBefore("(").trim(), 60_000)
        Thread.sleep(500)
        TestImages.screenshot("08_export_done")
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
        TestImages.screenshot("09_home_list")
        compose.onNodeWithText("Hoa don thang 10").performClick()
        compose.waitText(str(R.string.doc_reorder_hint))
        compose.onNodeWithText("1").performClick()
        compose.waitText(str(R.string.page_ocr))
        Thread.sleep(800)
        TestImages.screenshot("10_page_viewer")

        val before = app.documents.get(docId)!!.pages[0]
        compose.onNodeWithText(str(R.string.page_rotate)).performClick()
        compose.waitUntil(20_000) { app.documents.get(docId)!!.pages[0].rotation == 90 }
        val after = app.documents.get(docId)!!.pages[0]
        assertEquals(before.width, after.height)

        compose.onNodeWithText(str(R.string.page_ocr)).performClick()
        compose.waitText(str(R.string.copy), 60_000)
        TestImages.screenshot("11_ocr")
        compose.onNodeWithText("12345", substring = true).assertExists()
    }

    @Test
    fun cameraCaptureFlow() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitText(str(R.string.home_scan))
        compose.onNodeWithText(str(R.string.home_scan)).performClick()
        compose.waitText(str(R.string.camera_mode_batch))
        Thread.sleep(3000)
        TestImages.screenshot("12_camera")
        compose.waitDescription(str(R.string.camera_capture))
        compose.onNodeWithContentDescription(str(R.string.camera_capture)).performClick()
        compose.waitText(str(R.string.crop_auto), 30_000)
        Thread.sleep(1000)
        TestImages.screenshot("13_camera_crop")
        compose.onAllNodesWithText(str(R.string.next)).onFirst().performClick()
        compose.waitDescription(str(R.string.save))
        Thread.sleep(1500)
        compose.onNodeWithContentDescription(str(R.string.save)).performClick()
        compose.waitText(str(R.string.doc_export_pdf), 30_000)
        TestImages.screenshot("14_camera_saved")
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
}
