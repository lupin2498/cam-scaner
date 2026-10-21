package com.camscaner.app.ui.document

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.data.ScanPage
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.FileImage
import com.camscaner.app.ui.components.RenameDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

class DocumentViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    val docId: String = checkNotNull(handle["docId"])
    private val repository = (app as ScannerApp).documents

    val document: StateFlow<ScanDocument?> =
        repository.observe(docId).stateIn(viewModelScope, SharingStarted.Eagerly, repository.get(docId))

    fun thumb(page: ScanPage) = repository.thumbFile(docId, page.id)
    fun image(page: ScanPage) = repository.pageFile(docId, page.id)

    fun rename(name: String) = viewModelScope.launch { repository.rename(docId, name) }
    fun reorder(ids: List<String>) = viewModelScope.launch { repository.reorder(docId, ids) }
    val loaded: StateFlow<Boolean> = repository.loaded

    /** The screen closes itself once the document disappears from the repository. */
    fun delete() = viewModelScope.launch { repository.delete(docId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    onBack: () -> Unit,
    onAddCamera: (docId: String) -> Unit,
    onAddImages: (docId: String, uris: List<android.net.Uri>) -> Unit,
    onOpenPage: (docId: String, index: Int) -> Unit,
    onExport: (docId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: DocumentViewModel = viewModel()
    val document by vm.document.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val loaded by vm.loaded.collectAsState()
    val doc = document
    LaunchedEffect(doc == null, loaded) {
        if (doc == null && loaded) onBack()
    }
    if (doc == null) return

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onAddImages(doc.id, uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.doc_share_images)) },
                                onClick = {
                                    menu = false
                                    Share.shareFiles(context, doc.pages.map { vm.image(it) }.filter { it.exists() }, "image/jpeg")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.doc_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomAction(Icons.Filled.AddAPhoto, stringResource(R.string.doc_add_camera)) { onAddCamera(doc.id) }
                    BottomAction(Icons.Filled.AddPhotoAlternate, stringResource(R.string.doc_add_gallery)) {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    BottomAction(Icons.Filled.PictureAsPdf, stringResource(R.string.doc_export_pdf), highlighted = true) {
                        onExport(doc.id)
                    }
                }
            }
        },
    ) { padding ->
        if (doc.pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.doc_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            PageGrid(doc, vm, Modifier.padding(padding)) { index -> onOpenPage(doc.id, index) }
        }
    }

    if (renaming) RenameDialog(doc.name, onRename = { vm.rename(it) }, onDismiss = { renaming = false })
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.home_delete_title),
            message = stringResource(R.string.home_delete_message, doc.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.delete() },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun PageGrid(doc: ScanDocument, vm: DocumentViewModel, modifier: Modifier, onOpen: (Int) -> Unit) {
    var items by remember(doc.pages) { mutableStateOf(doc.pages) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(modifier) {
        Text(
            stringResource(R.string.doc_reorder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, p -> p.id }) { index, page ->
                ReorderableItem(reorderState, key = page.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 10.dp else 1.dp, label = "drag")
                    Card(
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .longPressDraggableHandle(onDragStopped = { vm.reorder(items.map { it.id }) })
                            .clickable { onOpen(index) },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White)) {
                            FileImage(
                                file = vm.thumb(page),
                                version = page.updatedAt,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 8.dp),
                                modifier = Modifier.align(Alignment.BottomEnd),
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
