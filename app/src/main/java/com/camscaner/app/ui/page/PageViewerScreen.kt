package com.camscaner.app.ui.page

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.data.ScanPage
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.FileImage
import com.camscaner.app.ui.crop.ToolButton
import com.camscaner.app.ui.components.LightSystemBarIcons
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PageViewerViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    val docId: String = checkNotNull(handle["docId"])
    val startIndex: Int = handle.get<Int>("index") ?: 0
    private val repository = (app as ScannerApp).documents

    val document: StateFlow<ScanDocument?> =
        repository.observe(docId).stateIn(viewModelScope, SharingStarted.Eagerly, repository.get(docId))

    val loaded: StateFlow<Boolean> = repository.loaded

    var rotating by mutableStateOf(false)
        private set

    fun image(page: ScanPage) = repository.pageFile(docId, page.id)

    fun rotate(page: ScanPage) {
        if (rotating) return
        rotating = true
        viewModelScope.launch {
            try {
                repository.updatePage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) }
            } finally {
                rotating = false
            }
        }
    }

    fun delete(page: ScanPage) = viewModelScope.launch { repository.deletePage(docId, page.id) }
}

@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewerScreen(
    onBack: () -> Unit,
    onRecrop: (docId: String, pageId: String) -> Unit,
    onFilter: (docId: String, pageId: String) -> Unit,
    onOcr: (docId: String, pageId: String) -> Unit,
) {
    LightSystemBarIcons()
    val vm: PageViewerViewModel = viewModel()
    val document by vm.document.collectAsState()
    val doc = document
    val pages = doc?.pages.orEmpty()
    val loaded by vm.loaded.collectAsState()
    // after process death the documents are still loading on the first frames
    LaunchedEffect(pages.isEmpty(), loaded) {
        if (pages.isEmpty() && loaded) onBack()
    }
    if (doc == null || pages.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = vm.startIndex.coerceIn(0, pages.lastIndex)) { pages.size }
    val zoomStates = remember { HashMap<String, ZoomState>() }
    var confirmDelete by remember { mutableStateOf<ScanPage?>(null) }
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    val currentZoomed = (zoomStates[current.id]?.scale ?: 1f) > 1.01f

    Scaffold(
        containerColor = Color(0xFF101312),
        topBar = {
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${pages.size}") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101312),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !currentZoomed,
                key = { pages.getOrNull(it)?.id ?: it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { i ->
                val page = pages.getOrNull(i) ?: return@HorizontalPager
                val zoom = zoomStates.getOrPut(page.id) { ZoomState() }
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    FileImage(
                        file = vm.image(page),
                        version = page.updatedAt,
                        modifier = Modifier
                            .fillMaxSize()
                            .zoomable(zoom),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF181C1B)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.Filled.Crop, stringResource(R.string.page_recrop)) { onRecrop(doc.id, current.id) }
                ToolButton(Icons.Filled.AutoFixHigh, stringResource(R.string.page_filter)) { onFilter(doc.id, current.id) }
                ToolButton(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.page_rotate)) { vm.rotate(current) }
                ToolButton(Icons.Filled.DocumentScanner, stringResource(R.string.page_ocr)) { onOcr(doc.id, current.id) }
                ToolButton(Icons.Filled.DeleteOutline, stringResource(R.string.delete)) { confirmDelete = current }
            }
        }
        // blocks the toolbar: editing or deleting the page mid-rotation would race with the re-render
        if (vm.rotating) ProgressDialog(message = stringResource(R.string.page_rotating))
    }

    confirmDelete?.let { page ->
        ConfirmDialog(
            title = stringResource(R.string.page_delete_title),
            message = null,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.delete(page) },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Pinch to zoom, pan when zoomed, double tap to toggle. One-finger swipes go to the pager at 1x. */
private fun Modifier.zoomable(state: ZoomState): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val multiTouch = event.changes.size > 1
                if (multiTouch || state.scale > 1.01f) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val newScale = (state.scale * zoom).coerceIn(1f, 5f)
                    state.scale = newScale
                    if (newScale <= 1.01f) {
                        state.offset = Offset.Zero
                    } else {
                        val maxX = size.width * (newScale - 1f) / 2f
                        val maxY = size.height * (newScale - 1f) / 2f
                        val o = state.offset + pan
                        state.offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state) {
        detectTapGestures(onDoubleTap = {
            if (state.scale > 1.01f) {
                state.scale = 1f
                state.offset = Offset.Zero
            } else {
                state.scale = 2.5f
            }
        })
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
