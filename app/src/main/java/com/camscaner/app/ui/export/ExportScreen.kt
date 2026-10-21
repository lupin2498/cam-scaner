package com.camscaner.app.ui.export

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.ScanDocument
import com.camscaner.app.pdf.PageMargin
import com.camscaner.app.pdf.PageOrientation
import com.camscaner.app.pdf.PaperSize
import com.camscaner.app.pdf.PdfLayout
import com.camscaner.app.pdf.PdfOptions
import com.camscaner.app.pdf.PdfQuality
import com.camscaner.app.pdf.Sheet
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.FileImage
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.SectionTitle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ExportViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val scannerApp = app as ScannerApp
    val docId: String = checkNotNull(handle["docId"])
    private val repository = scannerApp.documents

    val document: StateFlow<ScanDocument?> =
        repository.observe(docId).stateIn(viewModelScope, SharingStarted.Eagerly, repository.get(docId))

    var options by mutableStateOf(scannerApp.settings.pdfOptions(repository.get(docId)?.name ?: "scan"))
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var result by mutableStateOf<File?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    fun thumb(pageId: String) = repository.thumbFile(docId, pageId)

    /** Any change makes the previously exported file stale. */
    fun updateOptions(transform: (PdfOptions) -> PdfOptions) {
        options = transform(options)
        result = null
    }

    fun export() {
        val doc = document.value ?: return
        if (progress != null) return
        scannerApp.settings.savePdfOptions(options)
        result = null
        progress = 0f
        viewModelScope.launch {
            try {
                val dir = File(scannerApp.cacheDir, "exports").apply { mkdirs() }
                val out = File(dir, Share.safeFileName(options.fileName) + ".pdf")
                result = scannerApp.pdfExporter.export(doc, options, out) { p -> progress = p }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                progress = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ExportViewModel = viewModel()
    val document by vm.document.collectAsState()
    val doc = document ?: return
    val o = vm.options
    fun update(t: (PdfOptions) -> PdfOptions) = vm.updateOptions(t)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = vm.result
        if (uri != null && file != null) {
            runCatching { Share.copyToUri(context, file, uri) }
                .onSuccess { Toast.makeText(context, R.string.export_saved, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.error_generic, it.message), Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { vm.export() },
                    enabled = doc.pages.isNotEmpty() && o.fileName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                ) {
                    Icon(Icons.Filled.PictureAsPdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_start))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            vm.result?.let { file ->
                ResultCard(
                    file = file,
                    onOpen = { Share.openPdf(context, file) },
                    onShare = { Share.sharePdf(context, file) },
                    onSave = { saveLauncher.launch(file.name) },
                )
            }

            OutlinedTextField(
                value = o.fileName,
                onValueChange = { v -> update { it.copy(fileName = v) } },
                label = { Text(stringResource(R.string.export_file_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            LayoutPreview(doc, o, vm)

            SectionTitle(stringResource(R.string.export_paper_size))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperSize.entries.forEach { p ->
                    FilterChip(selected = o.paperSize == p, onClick = { update { it.copy(paperSize = p) } }, label = { Text(stringResource(p.label)) })
                }
            }
            if (o.paperSize == PaperSize.AUTO) {
                Text(stringResource(R.string.paper_auto_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle(stringResource(R.string.export_orientation))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageOrientation.entries.forEach { v ->
                    FilterChip(selected = o.orientation == v, onClick = { update { it.copy(orientation = v) } }, label = { Text(stringResource(v.label)) })
                }
            }

            SectionTitle(stringResource(R.string.export_margin))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMargin.entries.forEach { v ->
                    FilterChip(selected = o.margin == v, onClick = { update { it.copy(margin = v) } }, label = { Text(stringResource(v.label)) })
                }
            }

            SectionTitle(stringResource(R.string.export_quality))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfQuality.entries.forEach { v ->
                    FilterChip(selected = o.quality == v, onClick = { update { it.copy(quality = v) } }, label = { Text(stringResource(v.label)) })
                }
            }

            SectionTitle(stringResource(R.string.export_options))
            SwitchRow(stringResource(R.string.export_ocr_layer), stringResource(R.string.export_ocr_layer_desc), o.ocrLayer) { v ->
                update { it.copy(ocrLayer = v) }
            }
            SwitchRow(stringResource(R.string.export_id_card), stringResource(R.string.export_id_card_desc), o.mergeIdCards) { v ->
                update { it.copy(mergeIdCards = v) }
            }
            SwitchRow(stringResource(R.string.export_page_numbers), null, o.pageNumbers) { v -> update { it.copy(pageNumbers = v) } }

            var passwordOn by remember { mutableStateOf(o.password.isNotEmpty()) }
            SwitchRow(stringResource(R.string.export_password), null, passwordOn) { v ->
                passwordOn = v
                if (!v) update { it.copy(password = "") }
            }
            if (passwordOn) {
                OutlinedTextField(
                    value = o.password,
                    onValueChange = { v -> update { it.copy(password = v) } },
                    label = { Text(stringResource(R.string.export_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            var watermarkOn by remember { mutableStateOf(o.watermark.isNotEmpty()) }
            SwitchRow(stringResource(R.string.export_watermark), null, watermarkOn) { v ->
                watermarkOn = v
                if (!v) update { it.copy(watermark = "") }
            }
            if (watermarkOn) {
                OutlinedTextField(
                    value = o.watermark,
                    onValueChange = { v -> update { it.copy(watermark = v) } },
                    label = { Text(stringResource(R.string.export_watermark_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    vm.progress?.let { p ->
        ProgressDialog(stringResource(R.string.export_progress, (p * 100).toInt()), p)
    }
    vm.error?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.error_generic, message)) },
        )
    }
}

@Composable
private fun ResultCard(file: File, onOpen: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.export_done, Share.formatSize(file.length())),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.open))
                }
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.share))
                }
                OutlinedButton(onClick = onSave) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.export_save_device))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Miniature of the first PDF pages so the effect of paper size / margins is visible. */
@Composable
private fun LayoutPreview(doc: ScanDocument, o: PdfOptions, vm: ExportViewModel) {
    val sheets = remember(doc.pages, o.paperSize, o.orientation, o.margin, o.mergeIdCards) {
        PdfLayout.plan(
            doc.pages.map { it.width.coerceAtLeast(1) to it.height.coerceAtLeast(1) },
            o.paperSize, o.orientation, o.margin, o.mergeIdCards,
        )
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            stringResource(R.string.pages_count, sheets.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            sheets.take(6).forEach { sheet -> SheetPreview(sheet, doc, vm, 150.dp) }
        }
    }
}

@Composable
private fun SheetPreview(sheet: Sheet, doc: ScanDocument, vm: ExportViewModel, maxSide: Dp) {
    val k = maxSide.value / maxOf(sheet.width, sheet.height)
    Box(
        Modifier
            .size((sheet.width * k).dp, (sheet.height * k).dp)
            .shadow(3.dp, RoundedCornerShape(2.dp))
            .background(Color.White)
            .border(0.5.dp, Color(0x22000000)),
    ) {
        sheet.items.forEach { item ->
            val page = doc.pages.getOrNull(item.index) ?: return@forEach
            FileImage(
                file = vm.thumb(page.id),
                version = page.updatedAt,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset(x = (item.x * k).dp, y = ((sheet.height - item.y - item.height) * k).dp)
                    .size((item.width * k).dp, (item.height * k).dp),
            )
        }
    }
}
