package com.camscaner.app.ui.ocr

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.camscaner.app.ui.components.ProgressDialog
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Languages offered for translating recognised text (ML Kit on-device models). */
enum class Language(@StringRes val label: Int, val code: String) {
    VI(R.string.lang_vi, TranslateLanguage.VIETNAMESE),
    EN(R.string.lang_en, TranslateLanguage.ENGLISH),
    ZH(R.string.lang_zh, TranslateLanguage.CHINESE),
    JA(R.string.lang_ja, TranslateLanguage.JAPANESE),
    KO(R.string.lang_ko, TranslateLanguage.KOREAN),
    FR(R.string.lang_fr, TranslateLanguage.FRENCH),
    DE(R.string.lang_de, TranslateLanguage.GERMAN),
    ES(R.string.lang_es, TranslateLanguage.SPANISH),
    RU(R.string.lang_ru, TranslateLanguage.RUSSIAN),
    TH(R.string.lang_th, TranslateLanguage.THAI),
}

class OcrViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val docId: String = checkNotNull(handle["docId"])
    private val pageId: String = checkNotNull(handle["pageId"])
    private val scannerApp = app as ScannerApp
    private val repository = scannerApp.documents

    var running by mutableStateOf(true)
        private set
    var text by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var translating by mutableStateOf(false)
        private set
    var translation by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            try {
                // text read earlier is shown at once; otherwise the page is read now and the text kept
                val stored = repository.get(docId)?.pages?.firstOrNull { it.id == pageId }?.ocrText
                if (stored != null) {
                    text = stored
                } else {
                    val bitmap = withContext(Dispatchers.IO) { ImageIO.decodeSampled(repository.pageFile(docId, pageId), 2400) }
                    if (bitmap != null) {
                        text = OcrEngine.recognize(bitmap).text
                        bitmap.recycle()
                        repository.setOcrText(docId, pageId, text)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }

    /** Translates the (possibly edited) text; the language model is downloaded on first use. */
    fun translate(from: Language, to: Language) {
        if (translating || text.isBlank()) return
        translating = true
        viewModelScope.launch {
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from.code).setTargetLanguage(to.code).build()
            )
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                translation = translator.translate(text).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = scannerApp.getString(R.string.ocr_translate_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                translator.close()
                translating = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OcrScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: OcrViewModel = viewModel()
    val copied = stringResource(R.string.ocr_copied)
    var languages by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OCR", text))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ocr_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (!vm.running && vm.text.isNotBlank()) {
                        IconButton(onClick = { languages = true }) { Icon(Icons.Filled.Translate, stringResource(R.string.ocr_translate)) }
                    }
                },
            )
        },
    ) { padding ->
        // edge-to-edge: the editable text and its buttons stay above the keyboard
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
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
                    vm.translation?.let { translated ->
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.ocr_translation), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            Text(translated, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { copy(translated) }) { Text(stringResource(R.string.copy)) }
                            TextButton(onClick = { Share.shareText(context, translated) }) { Text(stringResource(R.string.share)) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { copy(vm.text) }, modifier = Modifier.weight(1f)) {
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

    if (languages) {
        LanguageDialog(
            onTranslate = { from, to ->
                languages = false
                vm.translate(from, to)
            },
            onDismiss = { languages = false },
        )
    }
    if (vm.translating) ProgressDialog(stringResource(R.string.ocr_translating))
}

/** Picks the languages to translate from and to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageDialog(onTranslate: (Language, Language) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(Language.VI) }
    var to by remember { mutableStateOf(Language.EN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_translate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ocr_translate_from), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Language.entries.forEach { l ->
                        FilterChip(selected = from == l, onClick = { from = l }, label = { Text(stringResource(l.label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ocr_translate_to), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Language.entries.forEach { l ->
                        FilterChip(selected = to == l, onClick = { to = l }, label = { Text(stringResource(l.label)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTranslate(from, to) }, enabled = from != to) { Text(stringResource(R.string.ocr_translate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
