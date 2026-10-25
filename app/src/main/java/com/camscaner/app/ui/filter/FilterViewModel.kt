package com.camscaner.app.ui.filter

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camscaner.app.data.DraftPage
import com.camscaner.app.ocr.OcrEngine
import com.camscaner.app.scanner.ImageEnhancer
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.MarkupRenderer
import com.camscaner.app.scanner.PageAligner
import com.camscaner.app.scanner.PageProcessor
import com.camscaner.app.scanner.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live preview of filters on a reduced-size, perspective-corrected copy of the page. */
class FilterViewModel : ViewModel() {

    /** The page as it will be saved: filtered, turned, with its marks. */
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    /** [preview] without the marks: what the markup editor draws on. */
    var cleanPreview by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The page without filter and adjustments (with its marks), shown while comparing. */
    var original by mutableStateOf<ImageBitmap?>(null)
        private set
    var previewKey by mutableStateOf("")
        private set
    var thumbnails by mutableStateOf<Map<ScanFilter, ImageBitmap>>(emptyMap())
        private set
    var thumbnailsKey by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    /** Straightening shown in the preview, in clockwise degrees (measured when the page is on auto). */
    var appliedSkew by mutableStateOf(0f)
        private set
    /** Page [appliedSkew] belongs to; it is stale while another page's preview is computed. */
    var appliedSkewPage by mutableStateOf<String?>(null)
        private set

    /** True while the text of a page is being recognised. */
    var extracting by mutableStateOf(false)
        private set
    /** Text recognised on the page; null when no result is shown. */
    var extractedText by mutableStateOf<String?>(null)
        private set

    private val warpCache = LinkedHashMap<String, Bitmap>()
    private val autoSkews = HashMap<String, Float>()
    private val cacheLock = Mutex()
    private var previewJob: Job? = null
    private var thumbsJob: Job? = null
    /** Key [thumbsJob] is rendering. */
    private var thumbsJobKey = ""

    // Intermediate images of the last preview, reused while only later steps change (a filter
    // slider does not straighten again, a new stroke does not filter again). Only touched under
    // [renderLock]; bitmaps shown on screen are never recycled.
    private val renderLock = Mutex()
    private var straightKey = ""
    private var straight: Bitmap? = null
    private var cleanKey = ""
    private var clean: Bitmap? = null
    private var originalKey = ""
    private var originalBitmap: Bitmap? = null

    fun render(page: DraftPage) {
        val baseKey = "${page.id}|${page.originalFile.absolutePath}|${page.corners}|${page.rotation}|${page.skew}"
        val filterKey = "$baseKey|${page.filter}|${page.adjustments}"
        // marks can hold thousands of points; their hash identifies them without a huge string
        val key = "$filterKey|${page.marks.size}|${page.marks.hashCode()}"
        // a render for an intermediate state may still be running; it must not overwrite the
        // preview once the page is back to the state already shown
        previewJob?.cancel()
        if (key == previewKey && preview != null) {
            busy = false
            return
        }
        previewJob = viewModelScope.launch {
            busy = true
            delay(40) // coalesce slider updates
            try {
                val result = withContext(Dispatchers.Default) {
                    renderLock.withLock { renderLocked(page, baseKey, filterKey) }
                }
                preview = result.preview.asImageBitmap()
                cleanPreview = result.clean.asImageBitmap()
                original = result.original.asImageBitmap()
                previewKey = key
                appliedSkew = result.angle
                appliedSkewPage = page.id
                busy = false
            } catch (e: CancellationException) {
                // superseded by a newer render, which owns `busy` now
                throw e
            } catch (e: Throwable) {
                // e.g. the session photo was purged from the cache: keep the screen usable
                Log.e(TAG, "Preview failed", e)
                busy = false
            }
        }
    }

    private class Rendered(val preview: Bitmap, val clean: Bitmap, val original: Bitmap, val angle: Float)

    private suspend fun renderLocked(page: DraftPage, baseKey: String, filterKey: String): Rendered {
        val angle = angleFor(page)
        val straightened = "${page.originalFile.absolutePath}|${page.corners}|$angle"
        val level = straight?.takeIf { straightKey == straightened } ?: run {
            // the cached warp is shared: rotate() returns it unchanged when there is nothing to do
            val warped = warped(page, PREVIEW_SIZE)
            val turned = PageAligner.rotate(warped, angle)
            val owned = if (turned === warped) warped.copy(Bitmap.Config.ARGB_8888, false) else turned
            straight?.recycle()
            straight = owned
            straightKey = straightened
            owned
        }
        val filtered = clean?.takeIf { cleanKey == filterKey } ?: run {
            val enhanced = if (page.filter == ScanFilter.ORIGINAL && page.adjustments.isNeutral) {
                level.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                ImageEnhancer.enhance(level, page.filter, page.adjustments)
            }
            ImageIO.rotate(enhanced, page.rotation).also {
                clean = it
                cleanKey = filterKey
            }
        }
        val originalWithMarks = "$baseKey|${page.marks.size}|${page.marks.hashCode()}"
        val unfiltered = originalBitmap?.takeIf { originalKey == originalWithMarks } ?: run {
            val turned = ImageIO.rotate(level.copy(Bitmap.Config.ARGB_8888, page.marks.isNotEmpty()), page.rotation)
            withMarks(turned, page).also {
                originalBitmap = it
                originalKey = originalWithMarks
            }
        }
        val marked = if (page.marks.isEmpty()) filtered else withMarks(filtered.copy(Bitmap.Config.ARGB_8888, true), page)
        return Rendered(marked, filtered, unfiltered, angle)
    }

    /** Draws the page's marks on [bitmap]; a mutable bitmap is drawn on in place. */
    private fun withMarks(bitmap: Bitmap, page: DraftPage): Bitmap = MarkupRenderer.draw(bitmap, page.marks)

    fun renderThumbnails(page: DraftPage) {
        val key = "${page.id}|${page.corners}|${page.rotation}|${page.skew}"
        // filters, strength and marks do not change the thumbnails: a running render is kept
        if (key == thumbsJobKey && thumbsJob?.isActive == true) return
        thumbsJob?.cancel()
        if (key == thumbnailsKey) return
        thumbsJobKey = key
        thumbsJob = viewModelScope.launch {
            // the straighten slider changes the key on every step; wait until it rests
            delay(150)
            val result = try {
                withContext(Dispatchers.Default) {
                    val scaled = ImageIO.scaleDown(warped(page, PREVIEW_SIZE).copy(Bitmap.Config.ARGB_8888, false), THUMB_SIZE)
                    val small = PageAligner.rotate(scaled, angleFor(page))
                    if (small !== scaled) scaled.recycle()
                    val map = ScanFilter.entries.associateWith { f ->
                        val out = if (f == ScanFilter.ORIGINAL) small.copy(Bitmap.Config.ARGB_8888, false)
                        else ImageEnhancer.enhance(small, f)
                        ImageIO.rotate(out, page.rotation).asImageBitmap()
                    }
                    small.recycle()
                    map
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Thumbnails failed", e)
                return@launch
            }
            thumbnails = result
            thumbnailsKey = key
        }
    }

    /**
     * Recognises the text of [page] as it will be saved (without its marks, which could hide
     * words) and shows it in [extractedText]; an empty text when nothing could be read.
     */
    fun extractText(page: DraftPage) {
        if (extracting) return
        extracting = true
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.Default) {
                    val bitmap = PageProcessor.render(
                        page.originalFile, page.corners, page.rotation, page.filter, page.adjustments,
                        OCR_SIZE, skew = angleFor(page),
                    )
                    try {
                        OcrEngine.recognize(bitmap).text
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: CancellationException) {
                extracting = false
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Text extraction failed", e)
                ""
            }
            extracting = false
            extractedText = text
        }
    }

    fun dismissText() {
        extractedText = null
    }

    /**
     * Clockwise straightening of [page]: its manual angle, or the automatic one measured on the
     * preview warp, which is the same input saved pages are measured on.
     */
    private suspend fun angleFor(page: DraftPage): Float {
        page.skew?.let { return it }
        val key = "${page.originalFile.absolutePath}|${page.corners}"
        cacheLock.withLock {
            autoSkews[key]?.let { return it }
        }
        val angle = PageAligner.autoCorrection(warped(page, PREVIEW_SIZE))
        cacheLock.withLock { autoSkews[key] = angle }
        return angle
    }

    private suspend fun warped(page: DraftPage, size: Int): Bitmap {
        val key = "${page.originalFile.absolutePath}|${page.corners}|$size"
        cacheLock.withLock {
            warpCache[key]?.let { return it }
        }
        val original = ImageIO.decodeSampled(page.originalFile, (size * 1.25f).toInt())
            ?: error("Cannot decode page")
        val warped = PageProcessor.warp(original, page.corners, size)
        original.recycle()
        cacheLock.withLock {
            warpCache[key] = warped
            // keep a handful of pages in memory
            while (warpCache.size > 6) {
                val eldest = warpCache.keys.first()
                warpCache.remove(eldest)
            }
        }
        return warped
    }

    private companion object {
        /** Equal to the size saved pages measure their automatic angle on. */
        const val PREVIEW_SIZE = PageProcessor.MEASURE_WARP
        const val THUMB_SIZE = 220
        /** Resolution text is recognised at: small print needs more than the preview has. */
        const val OCR_SIZE = 2400
    }
}

private const val TAG = "FilterViewModel"
