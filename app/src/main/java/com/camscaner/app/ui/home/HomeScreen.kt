package com.camscaner.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.FileImage
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

    var query by mutableStateOf("")
    var sharing by mutableStateOf(false)
        private set

    fun thumb(doc: ScanDocument): File? = doc.pages.firstOrNull()?.let { repository.thumbFile(doc.id, it.id) }

    fun rename(doc: ScanDocument, name: String) = viewModelScope.launch { repository.rename(doc.id, name) }
    fun delete(doc: ScanDocument) = viewModelScope.launch { repository.delete(doc.id) }

    /** Exports with the last used PDF options and opens the share sheet. */
    fun quickShare(doc: ScanDocument, share: (File) -> Unit, onError: (String) -> Unit) {
        if (sharing || doc.pages.isEmpty()) return
        sharing = true
        viewModelScope.launch {
            try {
                val options = scannerApp.settings.pdfOptions(doc.name)
                val dir = File(scannerApp.cacheDir, "exports").apply { mkdirs() }
                val file = scannerApp.pdfExporter.export(doc, options, File(dir, Share.safeFileName(doc.name) + ".pdf"))
                share(file)
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                sharing = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onImport: (List<Uri>) -> Unit,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel()
    val documents by vm.documents.collectAsState()
    var renaming by remember { mutableStateOf<ScanDocument?>(null) }
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }

    val filtered = remember(documents, vm.query) {
        val q = vm.query.trim()
        if (q.isEmpty()) documents else documents.filter { it.name.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.home_settings)) }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) { Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.home_import)) }
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
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                            onClick = { onOpenDocument(doc.id) },
                            onRename = { renaming = doc },
                            onShare = {
                                vm.quickShare(doc, share = { Share.sharePdf(context, it) }, onError = { msg ->
                                    android.widget.Toast.makeText(context, context.getString(R.string.error_generic, msg), android.widget.Toast.LENGTH_LONG).show()
                                })
                            },
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
    if (vm.sharing) ProgressDialog(stringResource(R.string.processing))
}

@Composable
private fun DocumentRow(
    doc: ScanDocument,
    thumb: File?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_quick_pdf)) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
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
