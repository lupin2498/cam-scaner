package com.camscaner.app.ui.filter

import android.graphics.Bitmap
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
import com.camscaner.app.scanner.PageProcessor
import com.camscaner.app.scanner.ScanFilter
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

    private val warpCache = LinkedHashMap<String, Bitmap>()
    private val cacheLock = Mutex()
    private var previewJob: Job? = null
    private var thumbsJob: Job? = null

    fun render(page: DraftPage) {
        val key = "${page.id}|${page.corners}|${page.rotation}|${page.filter}|${page.adjustments}"
        if (key == previewKey && preview != null) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            busy = true
            delay(40) // coalesce slider updates
            val bitmap = withContext(Dispatchers.Default) {
                val warped = warped(page, PREVIEW_SIZE)
                val enhanced = if (page.filter == ScanFilter.ORIGINAL && page.adjustments.isNeutral) {
                    warped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    ImageEnhancer.enhance(warped, page.filter, page.adjustments)
                }
                ImageIO.rotate(enhanced, page.rotation)
            }
            preview = bitmap.asImageBitmap()
            previewKey = key
            busy = false
        }
    }

    fun renderThumbnails(page: DraftPage) {
        val key = "${page.id}|${page.corners}|${page.rotation}"
        if (key == thumbnailsKey) return
        thumbsJob?.cancel()
        thumbsJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val small = ImageIO.scaleDown(warped(page, PREVIEW_SIZE).copy(Bitmap.Config.ARGB_8888, false), THUMB_SIZE)
                val map = ScanFilter.entries.associateWith { f ->
                    val out = if (f == ScanFilter.ORIGINAL) small.copy(Bitmap.Config.ARGB_8888, false)
                    else ImageEnhancer.enhance(small, f)
                    ImageIO.rotate(out, page.rotation).asImageBitmap()
                }
                small.recycle()
                map
            }
            thumbnails = result
            thumbnailsKey = key
        }
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
        const val PREVIEW_SIZE = 1400
        const val THUMB_SIZE = 220
    }
}
