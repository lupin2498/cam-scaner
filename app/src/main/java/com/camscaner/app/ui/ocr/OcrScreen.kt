package com.camscaner.app.ui.ocr

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.ocr.OcrEngine
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OcrViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val docId: String = checkNotNull(handle["docId"])
    private val pageId: String = checkNotNull(handle["pageId"])
    private val repository = (app as ScannerApp).documents

    var running by mutableStateOf(true)
        private set
    var text by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageIO.decodeSampled(repository.pageFile(docId, pageId), 2400) }
                if (bitmap != null) {
                    text = OcrEngine.recognize(bitmap).text
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: OcrViewModel = viewModel()
    val copied = stringResource(R.string.ocr_copied)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.ocr_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.running -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ocr_running))
                }
                vm.error != null -> Text(
                    stringResource(R.string.error_generic, vm.error.orEmpty()),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                vm.text.isBlank() -> Text(
                    stringResource(R.string.ocr_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = vm.text,
                        onValueChange = { vm.text = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("OCR", vm.text))
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.copy))
                        }
                        OutlinedButton(onClick = { Share.shareText(context, vm.text) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.share))
                        }
                    }
                }
            }
        }
    }
}
