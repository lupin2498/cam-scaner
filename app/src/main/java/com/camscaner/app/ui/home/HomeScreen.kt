package com.camscaner.app.ui.home

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.Folder
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.data.SortOrder
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.DocumentDetailsDialog
import com.camscaner.app.ui.components.FileImage
import com.camscaner.app.ui.components.FolderPickerDialog
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.RenameDialog
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val scannerApp = app as ScannerApp
    private val repository = scannerApp.documents
    val documents = repository.documents
    val folders = repository.folders
    val trash = repository.trash
    val settings = scannerApp.settings

    var query by mutableStateOf("")
    /** Folder shown; null shows every document. */
    var folderId by mutableStateOf<String?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    val selecting: Boolean get() = selection.isNotEmpty()
    var busy by mutableStateOf(false)
        private set
    /** One-shot message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    fun thumb(doc: ScanDocument): File? = doc.pages.firstOrNull()?.let { repository.thumbFile(doc.id, it.id) }
    fun size(doc: ScanDocument): Long = repository.sizeOf(doc.id)

    fun toggle(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun select(ids: Collection<String>) {
        selection = selection + ids
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun rename(doc: ScanDocument, name: String) = viewModelScope.launch { repository.rename(doc.id, name) }

    fun delete(doc: ScanDocument) = viewModelScope.launch {
        repository.delete(doc.id)
        message = scannerApp.getString(R.string.home_moved_to_trash, 1)
    }

    fun deleteSelected() = viewModelScope.launch {
        val ids = selection.toList()
        clearSelection()
        ids.forEach { repository.delete(it) }
        message = scannerApp.getString(R.string.home_moved_to_trash, ids.size)
    }

    /** Merges the selected documents into the first of them in [order]. */
    fun mergeSelected(order: List<ScanDocument>) = viewModelScope.launch {
        val chosen = order.filter { it.id in selection }.map { it.id }
        clearSelection()
        if (chosen.size < 2) return@launch
        busy = true
        try {
            repository.merge(chosen.first(), chosen.drop(1))
            message = scannerApp.getString(R.string.home_merged)
        } finally {
            busy = false
        }
    }

    fun moveSelected(folder: String?) = viewModelScope.launch {
        val ids = selection.toList()
        clearSelection()
        repository.moveToFolder(ids, folder)
        message = scannerApp.getString(R.string.home_moved_to_folder)
    }

    fun move(doc: ScanDocument, folder: String?) = viewModelScope.launch {
        repository.moveToFolder(listOf(doc.id), folder)
        message = scannerApp.getString(R.string.home_moved_to_folder)
    }

    fun duplicate(doc: ScanDocument) = viewModelScope.launch {
        busy = true
        try {
            repository.duplicate(doc.id, scannerApp.getString(R.string.home_duplicate_suffix, doc.name))
        } finally {
            busy = false
        }
    }

    /** Creates a folder and shows it. */
    fun createFolder(name: String) = viewModelScope.launch {
        folderId = repository.createFolder(name)
    }

    /** Creates a folder and files [ids] in it. */
    fun createFolderAndMove(name: String, ids: List<String>) = viewModelScope.launch {
        val id = repository.createFolder(name)
        repository.moveToFolder(ids, id)
        message = scannerApp.getString(R.string.home_moved_to_folder)
    }

    fun renameFolder(folder: Folder, name: String) = viewModelScope.launch { repository.renameFolder(folder.id, name) }

    fun deleteFolder(folder: Folder) = viewModelScope.launch {
        if (folderId == folder.id) folderId = null
        repository.deleteFolder(folder.id)
    }

    /** Exports with the last used PDF options and opens the share sheet. */
    fun quickShare(doc: ScanDocument, share: (File) -> Unit, onError: (String) -> Unit) {
        if (busy || doc.pages.isEmpty()) return
        busy = true
        viewModelScope.launch {
            try {
                val options = scannerApp.settings.pdfOptions(doc.name)
                val dir = File(scannerApp.cacheDir, "exports").apply { mkdirs() }
                val file = scannerApp.pdfExporter.export(doc, options, File(dir, Share.safeFileName(doc.name) + ".pdf"))
                share(file)
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onImport: (List<Uri>) -> Unit,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
    onTrash: () -> Unit,
    openImport: Boolean = false,
    onImportOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel()
    val documents by vm.documents.collectAsState()
    val folders by vm.folders.collectAsState()
    val trash by vm.trash.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<ScanDocument?>(null) }
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }
    var moving by remember { mutableStateOf<List<ScanDocument>?>(null) }
    var details by remember { mutableStateOf<ScanDocument?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var deletingFolder by remember { mutableStateOf<Folder?>(null) }
    var confirmMerge by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            snackbar.showSnackbar(it)
        }
    }
    BackHandler(enabled = vm.selecting) { vm.clearSelection() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    LaunchedEffect(openImport) {
        if (openImport) {
            onImportOpened()
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    val currentFolder = folders.firstOrNull { it.id == vm.folderId }
    val sort = vm.settings.sortOrder
    val filtered = remember(documents, vm.query, vm.folderId, sort) {
        val q = vm.query.trim()
        val inFolder = if (vm.folderId == null) documents else documents.filter { it.folderId == vm.folderId }
        sort.sort(if (q.isEmpty()) inFolder else inFolder.filter { it.matches(q) })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_selected, vm.selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, stringResource(R.string.home_clear_selection)) }
                    },
                    actions = {
                        IconButton(onClick = { vm.select(filtered.map { it.id }) }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.home_select_all)) }
                        IconButton(onClick = { moving = filtered.filter { it.id in vm.selection } }) {
                            Icon(Icons.Filled.DriveFileMove, stringResource(R.string.home_move_to_folder))
                        }
                        IconButton(onClick = { confirmMerge = true }, enabled = vm.selection.size >= 2) {
                            Icon(Icons.Filled.MergeType, stringResource(R.string.home_merge))
                        }
                        IconButton(onClick = { confirmDeleteSelected = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.home_sort)) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(order.label), fontWeight = if (order == sort) FontWeight.Bold else null) },
                                        onClick = { vm.settings.sortOrder = order; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.home_more)) }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (trash.isEmpty()) stringResource(R.string.home_trash) else stringResource(R.string.home_trash) + " (${trash.size})") },
                                    onClick = { moreMenu = false; onTrash() },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_settings)) }, onClick = { moreMenu = false; onSettings() })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!vm.selecting) {
                Column(horizontalAlignment = Alignment.End) {
                    Box {
                        SmallFloatingActionButton(
                            onClick = { importMenu = true },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) { Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.home_import)) }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.home_import_images)) }, onClick = {
                                importMenu = false
                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.home_import_pdf)) }, onClick = {
                                importMenu = false
                                files.launch(arrayOf("application/pdf"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.home_import_files)) }, onClick = {
                                importMenu = false
                                files.launch(arrayOf("image/*", "application/pdf"))
                            })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val scanLabel = stringResource(R.string.home_scan)
                    ExtendedFloatingActionButton(
                        onClick = onScan,
                        // Material hides the FAB text from accessibility; label the button itself
                        modifier = Modifier.semantics { contentDescription = scanLabel },
                        icon = { Icon(Icons.Filled.CameraAlt, null) },
                        text = { Text(stringResource(R.string.home_scan)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (documents.isNotEmpty() || folders.isNotEmpty()) {
                FolderBar(
                    folders = folders,
                    current = vm.folderId,
                    onSelect = { vm.folderId = it },
                    onNew = { newFolder = true },
                    onRename = { renamingFolder = it },
                    onDelete = { deletingFolder = it },
                )
            }
            if (documents.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (documents.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            thumb = vm.thumb(doc),
                            folderName = if (vm.folderId == null) folders.firstOrNull { it.id == doc.folderId }?.name else null,
                            selecting = vm.selecting,
                            selected = doc.id in vm.selection,
                            onClick = { if (vm.selecting) vm.toggle(doc.id) else onOpenDocument(doc.id) },
                            onLongClick = { vm.toggle(doc.id) },
                            onRename = { renaming = doc },
                            onShare = {
                                vm.quickShare(doc, share = { Share.sharePdf(context, it) }, onError = { msg ->
                                    Toast.makeText(context, context.getString(R.string.error_generic, msg), Toast.LENGTH_LONG).show()
                                })
                            },
                            onMove = { moving = listOf(doc) },
                            onDuplicate = { vm.duplicate(doc) },
                            onDetails = { details = doc },
                            onDelete = { deleting = doc },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { doc -> RenameDialog(doc.name, onRename = { vm.rename(doc, it) }, onDismiss = { renaming = null }) }
    deleting?.let { doc ->
        ConfirmDialog(
            title = stringResource(R.string.home_delete_title),
            message = stringResource(R.string.home_delete_message, doc.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.delete(doc) },
            onDismiss = { deleting = null },
        )
    }
    moving?.let { docs ->
        FolderPickerDialog(
            folders = folders,
            current = docs.singleOrNull()?.folderId,
            onPick = { folder ->
                if (vm.selecting) vm.moveSelected(folder) else docs.forEach { vm.move(it, folder) }
                moving = null
            },
            onNewFolder = { name ->
                val ids = docs.map { it.id }
                vm.clearSelection()
                vm.createFolderAndMove(name, ids)
                moving = null
            },
            onDismiss = { moving = null },
        )
    }
    details?.let { doc ->
        DocumentDetailsDialog(doc, size = vm.size(doc), folderName = folders.firstOrNull { it.id == doc.folderId }?.name, onDismiss = { details = null })
    }
    if (newFolder) {
        RenameDialog(
            initial = "",
            title = stringResource(R.string.home_new_folder),
            hint = stringResource(R.string.home_folder_name),
            onRename = { name -> vm.createFolder(name) },
            onDismiss = { newFolder = false },
        )
    }
    renamingFolder?.let { folder ->
        RenameDialog(
            initial = folder.name,
            title = stringResource(R.string.home_rename_folder),
            hint = stringResource(R.string.home_folder_name),
            onRename = { vm.renameFolder(folder, it) },
            onDismiss = { renamingFolder = null },
        )
    }
    deletingFolder?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.home_delete_folder),
            message = stringResource(R.string.home_delete_folder_message, folder.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.deleteFolder(folder) },
            onDismiss = { deletingFolder = null },
        )
    }
    if (confirmMerge) {
        ConfirmDialog(
            title = stringResource(R.string.home_merge),
            message = stringResource(R.string.home_merge_message, vm.selection.size),
            confirmLabel = stringResource(R.string.home_merge),
            onConfirm = { vm.mergeSelected(filtered) },
            onDismiss = { confirmMerge = false },
        )
    }
    if (confirmDeleteSelected) {
        ConfirmDialog(
            title = stringResource(R.string.home_delete_title),
            message = stringResource(R.string.home_moved_to_trash, vm.selection.size),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.deleteSelected() },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
    if (vm.busy) ProgressDialog(stringResource(R.string.processing))
}

/** Chips for "all" and every folder, a chip to add one, and a menu for the folder shown. */
@Composable
private fun FolderBar(
    folders: List<Folder>,
    current: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = current == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.home_folders_all)) })
        folders.forEach { f ->
            FilterChip(selected = current == f.id, onClick = { onSelect(f.id) }, label = { Text(f.name) })
        }
        FilterChip(
            selected = false,
            onClick = onNew,
            label = { Text(stringResource(R.string.home_new_folder)) },
            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
        )
        val shown = folders.firstOrNull { it.id == current }
        if (shown != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.home_folder_menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_rename_folder)) }, onClick = { menu = false; onRename(shown) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_delete_folder), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete(shown) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    doc: ScanDocument,
    thumb: File?,
    folderName: String?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            Box(
                Modifier.size(56.dp, 74.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE9EEEC)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    FileImage(
                        file = thumb,
                        version = doc.pages.first().updatedAt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pages_count, doc.pages.size) + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.updatedAt)) +
                        (folderName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.home_doc_menu)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_quick_pdf)) }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_move_to_folder)) }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_details)) }, onClick = { menu = false; onDetails() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
