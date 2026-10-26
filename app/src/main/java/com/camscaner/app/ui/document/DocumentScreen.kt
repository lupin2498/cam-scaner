package com.camscaner.app.ui.document

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.camscaner.app.ocr.OcrEngine
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.DocumentDetailsDialog
import com.camscaner.app.ui.components.FileImage
import com.camscaner.app.ui.components.FolderPickerDialog
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.RenameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

class DocumentViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    val docId: String = checkNotNull(handle["docId"])
    private val scannerApp = app as ScannerApp
    private val repository = scannerApp.documents

    val document: StateFlow<ScanDocument?> =
        repository.observe(docId).stateIn(viewModelScope, SharingStarted.Eagerly, repository.get(docId))
    val documents = repository.documents
    val folders = repository.folders
    val loaded: StateFlow<Boolean> = repository.loaded

    /** Ids of the selected pages; empty when not selecting. */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    /** True while the page selection mode is on (even with nothing ticked yet). */
    var selecting by mutableStateOf(false)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun thumb(page: ScanPage) = repository.thumbFile(docId, page.id)
    fun image(page: ScanPage) = repository.pageFile(docId, page.id)
    fun size() = repository.sizeOf(docId)

    fun rename(name: String) = viewModelScope.launch { repository.rename(docId, name) }
    fun reorder(ids: List<String>) = viewModelScope.launch { repository.reorder(docId, ids) }
    fun moveToFolder(folder: String?) = viewModelScope.launch {
        repository.moveToFolder(listOf(docId), folder)
        message = scannerApp.getString(R.string.home_moved_to_folder)
    }
    fun createFolderAndMove(name: String) = viewModelScope.launch {
        repository.moveToFolder(listOf(docId), repository.createFolder(name))
        message = scannerApp.getString(R.string.home_moved_to_folder)
    }

    /** The screen closes itself once the document disappears from the repository. */
    fun delete() = viewModelScope.launch { repository.delete(docId) }

    fun duplicate() = viewModelScope.launch {
        busy = scannerApp.getString(R.string.processing)
        try {
            val name = document.value?.name ?: return@launch
            repository.duplicate(docId, scannerApp.getString(R.string.home_duplicate_suffix, name))
            message = scannerApp.getString(R.string.home_duplicate)
        } finally {
            busy = null
        }
    }

    fun startSelecting() {
        selecting = true
    }

    fun stopSelecting() {
        selecting = false
        selection = emptySet()
    }

    fun toggle(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAll() {
        selection = document.value?.pages?.map { it.id }?.toSet().orEmpty()
    }

    /** The selected pages in document order. */
    private fun selectedIds(): List<String> = document.value?.pages?.map { it.id }?.filter { it in selection }.orEmpty()

    fun deleteSelected() = viewModelScope.launch {
        val ids = selectedIds()
        stopSelecting()
        repository.deletePages(docId, ids)
    }

    fun rotateSelected() = viewModelScope.launch {
        val ids = selectedIds()
        busy = scannerApp.getString(R.string.page_rotating)
        try {
            ids.forEach { id ->
                repository.updatePage(docId, id) { p ->
                    p.copy(rotation = (p.rotation + 90) % 360, marks = p.marks.map { it.rotated(90) }, texts = p.texts.map { it.rotated(90) })
                }
            }
        } finally {
            busy = null
        }
    }

    /** Moves or copies the selected pages into [targetId], or into a new document when null. */
    fun movePages(targetId: String?, copy: Boolean, newName: String) = viewModelScope.launch {
        val ids = selectedIds()
        stopSelecting()
        if (ids.isEmpty()) return@launch
        busy = scannerApp.getString(R.string.processing)
        try {
            repository.movePages(docId, ids, targetId, copy, newName)
            message = scannerApp.getString(if (copy) R.string.doc_pages_copied else R.string.doc_pages_moved, ids.size)
        } finally {
            busy = null
        }
    }

    fun shareSelectedImages(share: (List<java.io.File>) -> Unit) {
        val pages = document.value?.pages.orEmpty().filter { it.id in selection }
        share(pages.map { image(it) }.filter { it.exists() })
    }

    /** Recognises the text of every page (those without any first) so the document can be searched. */
    fun recognizeAll() = viewModelScope.launch {
        val doc = document.value ?: return@launch
        val pages = doc.pages.sortedBy { if (it.ocrText == null) 0 else 1 }
        try {
            pages.forEachIndexed { i, page ->
                busy = scannerApp.getString(R.string.doc_ocr_running, i + 1, pages.size)
                val text = withContext(Dispatchers.Default) {
                    val bitmap = ImageIO.decodeSampled(image(page), OCR_SIZE) ?: return@withContext ""
                    try {
                        OcrEngine.recognize(bitmap).text
                    } finally {
                        bitmap.recycle()
                    }
                }
                repository.setOcrText(docId, page.id, text)
            }
            message = scannerApp.getString(R.string.doc_ocr_done)
        } catch (e: Exception) {
            message = scannerApp.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = null
        }
    }

    private companion object {
        const val OCR_SIZE = 2400
    }
}

/** What the target picker is open for. */
private enum class PageTransfer { MOVE, COPY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    onBack: () -> Unit,
    onAddCamera: (docId: String) -> Unit,
    onAddImages: (docId: String, uris: List<Uri>) -> Unit,
    onOpenPage: (docId: String, index: Int) -> Unit,
    onExport: (docId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: DocumentViewModel = viewModel()
    val document by vm.document.collectAsState()
    val documents by vm.documents.collectAsState()
    val folders by vm.folders.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var movingDoc by remember { mutableStateOf(false) }
    var confirmDeletePages by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<PageTransfer?>(null) }
    var namingNew by remember { mutableStateOf<PageTransfer?>(null) }

    val loaded by vm.loaded.collectAsState()
    val doc = document
    LaunchedEffect(doc == null, loaded) {
        if (doc == null && loaded) onBack()
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            snackbar.showSnackbar(it)
        }
    }
    BackHandler(enabled = vm.selecting) { vm.stopSelecting() }
    if (doc == null) return

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onAddImages(doc.id, uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.doc_selected, vm.selection.size)) },
                    navigationIcon = { IconButton(onClick = { vm.stopSelecting() }) { Icon(Icons.Filled.Close, stringResource(R.string.home_clear_selection)) } },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.home_select_all)) }
                        val any = vm.selection.isNotEmpty()
                        IconButton(onClick = { vm.rotateSelected() }, enabled = any) { Icon(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.doc_rotate_selected)) }
                        IconButton(onClick = { vm.shareSelectedImages { Share.shareFiles(context, it, "image/jpeg") } }, enabled = any) {
                            Icon(Icons.Filled.Share, stringResource(R.string.doc_share_images))
                        }
                        IconButton(onClick = { transfer = PageTransfer.MOVE }, enabled = any) { Icon(Icons.Filled.DriveFileMove, stringResource(R.string.doc_move_pages)) }
                        IconButton(onClick = { transfer = PageTransfer.COPY }, enabled = any) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.doc_copy_pages)) }
                        IconButton(onClick = { confirmDeletePages = true }, enabled = any) { Icon(Icons.Filled.Delete, stringResource(R.string.doc_delete_pages)) }
                    },
                )
            } else {
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
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.home_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.doc_select)) }, onClick = { menu = false; vm.startSelecting() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.doc_ocr_all)) }, onClick = { menu = false; vm.recognizeAll() })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_share_images)) },
                                    onClick = {
                                        menu = false
                                        Share.shareFiles(context, doc.pages.map { vm.image(it) }.filter { it.exists() }, "image/jpeg")
                                    },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_move_to_folder)) }, onClick = { menu = false; movingDoc = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = { menu = false; vm.duplicate() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_details)) }, onClick = { menu = false; details = true })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleting = true },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!vm.selecting) {
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
            }
        },
    ) { padding ->
        if (doc.pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.doc_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            PageGrid(
                doc = doc,
                vm = vm,
                modifier = Modifier.padding(padding),
                onOpen = { index -> onOpenPage(doc.id, index) },
            )
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
    if (details) {
        DocumentDetailsDialog(doc, size = vm.size(), folderName = folders.firstOrNull { it.id == doc.folderId }?.name, onDismiss = { details = false })
    }
    if (movingDoc) {
        FolderPickerDialog(
            folders = folders,
            current = doc.folderId,
            onPick = { vm.moveToFolder(it); movingDoc = false },
            onNewFolder = { vm.createFolderAndMove(it); movingDoc = false },
            onDismiss = { movingDoc = false },
        )
    }
    if (confirmDeletePages) {
        ConfirmDialog(
            title = stringResource(R.string.doc_delete_pages),
            message = stringResource(R.string.doc_delete_pages_message, vm.selection.size),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.deleteSelected() },
            onDismiss = { confirmDeletePages = false },
        )
    }
    transfer?.let { kind ->
        TargetPickerDialog(
            title = stringResource(if (kind == PageTransfer.MOVE) R.string.doc_move_pages else R.string.doc_copy_pages),
            documents = documents.filter { it.id != doc.id },
            onPick = { target ->
                vm.movePages(target.id, copy = kind == PageTransfer.COPY, newName = "")
                transfer = null
            },
            onNew = { namingNew = kind; transfer = null },
            onDismiss = { transfer = null },
        )
    }
    namingNew?.let { kind ->
        RenameDialog(
            initial = doc.name,
            title = stringResource(R.string.doc_new_from_pages),
            onRename = { name -> vm.movePages(null, copy = kind == PageTransfer.COPY, newName = name) },
            onDismiss = { namingNew = null },
        )
    }
    vm.busy?.let { ProgressDialog(it) }
}

/** Lists the other documents as targets for moved or copied pages, or offers a new one. */
@Composable
private fun TargetPickerDialog(
    title: String,
    documents: List<ScanDocument>,
    onPick: (ScanDocument) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.doc_new_document),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 12.dp, horizontal = 4.dp),
                )
                documents.forEach { d ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.pages_count, d.pages.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PageGrid(doc: ScanDocument, vm: DocumentViewModel, modifier: Modifier, onOpen: (Int) -> Unit) {
    var items by remember(doc.pages) { mutableStateOf(doc.pages) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val selecting = vm.selecting
    Column(modifier) {
        Text(
            stringResource(if (selecting) R.string.doc_select_hint else R.string.doc_reorder_hint),
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
                ReorderableItem(reorderState, key = page.id, enabled = !selecting) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 10.dp else 1.dp, label = "drag")
                    val selected = page.id in vm.selection
                    val label = stringResource(R.string.doc_page_checkbox, index + 1)
                    Card(
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier
                            .then(if (selecting) Modifier else Modifier.longPressDraggableHandle(onDragStopped = { vm.reorder(items.map { it.id }) }))
                            .clickable { if (selecting) vm.toggle(page.id) else onOpen(index) },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White)) {
                            FileImage(
                                file = vm.thumb(page),
                                version = page.updatedAt,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                            )
                            if (selecting) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { vm.toggle(page.id) },
                                    modifier = Modifier.align(Alignment.TopStart).semantics { contentDescription = label },
                                )
                            }
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
