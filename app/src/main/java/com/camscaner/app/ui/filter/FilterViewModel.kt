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
import com.camscaner.app.scanner.ImageEnhancer
import com.camscaner.app.scanner.ImageIO
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

    var preview by mutableStateOf<ImageBitmap?>(null)
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

    private val warpCache = LinkedHashMap<String, Bitmap>()
    private val autoSkews = HashMap<String, Float>()
    private val cacheLock = Mutex()
    private var previewJob: Job? = null
    private var thumbsJob: Job? = null

    fun render(page: DraftPage) {
        val key = "${page.id}|${page.corners}|${page.rotation}|${page.filter}|${page.adjustments}|${page.skew}"
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
                val (bitmap, angle) = withContext(Dispatchers.Default) {
                    val warped = warped(page, PREVIEW_SIZE)
                    val angle = angleFor(page)
                    // the cached warp is shared: only a bitmap made here may be recycled
                    val straight = PageAligner.rotate(warped, angle)
                    val enhanced = if (page.filter == ScanFilter.ORIGINAL && page.adjustments.isNeutral) {
                        if (straight !== warped) straight else warped.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        ImageEnhancer.enhance(straight, page.filter, page.adjustments)
                            .also { if (straight !== warped) straight.recycle() }
                    }
                    ImageIO.rotate(enhanced, page.rotation) to angle
                }
                preview = bitmap.asImageBitmap()
                previewKey = key
                appliedSkew = angle
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

    fun renderThumbnails(page: DraftPage) {
        val key = "${page.id}|${page.corners}|${page.rotation}|${page.skew}"
        thumbsJob?.cancel()
        if (key == thumbnailsKey) return
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
    }
}

private const val TAG = "FilterViewModel"
