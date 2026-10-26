package com.camscaner.app.ui.home

import android.app.Application
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.FileImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class TrashViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as ScannerApp).documents
    val trash = repository.trash

    fun thumb(doc: ScanDocument) = doc.pages.firstOrNull()?.let { repository.thumbFile(doc.id, it.id) }
    fun restore(doc: ScanDocument) = viewModelScope.launch { repository.restore(doc.id) }
    fun deleteForever(doc: ScanDocument) = viewModelScope.launch { repository.deleteForever(doc.id) }
    fun emptyTrash() = viewModelScope.launch { repository.emptyTrash() }
}

/** Deleted documents: restore them or remove them for good. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val vm: TrashViewModel = viewModel()
    val trash by vm.trash.collectAsState()
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }
    var emptying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (trash.isNotEmpty()) {
                        IconButton(onClick = { emptying = true }) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.trash_empty_all)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.trash_hint, DocumentRepository.TRASH_DAYS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (trash.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(trash, key = { it.id }) { doc ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                val thumb = vm.thumb(doc)
                                Box(Modifier.size(48.dp, 64.dp)) {
                                    if (thumb != null) {
                                        FileImage(thumb, version = doc.pages.first().updatedAt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(R.string.pages_count, doc.pages.size) + " · " + stringResource(
                                            R.string.trash_deleted_at,
                                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.deletedAt ?: 0L)),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { vm.restore(doc) }) { Text(stringResource(R.string.trash_restore)) }
                                        TextButton(onClick = { deleting = doc }) {
                                            Text(stringResource(R.string.trash_delete_forever), color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { doc ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever),
            message = stringResource(R.string.trash_delete_forever_message, doc.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.deleteForever(doc) },
            onDismiss = { deleting = null },
        )
    }
    if (emptying) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_all),
            message = stringResource(R.string.trash_empty_all_message),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { vm.emptyTrash() },
            onDismiss = { emptying = false },
        )
    }
}
