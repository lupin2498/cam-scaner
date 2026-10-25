package com.camscaner.app.ui.filter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStarted
import com.camscaner.app.R
import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.scanner.ScanFilter
import com.camscaner.app.ui.ScanSessionViewModel
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.LightSystemBarIcons
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.RenameDialog
import com.camscaner.app.ui.theme.Brand
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Panel shown above the filter strip. */
private enum class Panel { NONE, STRENGTH, STRAIGHTEN, ADJUST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(session: ScanSessionViewModel, onBack: () -> Unit, onSaved: (docId: String) -> Unit) {
    val context = LocalContext.current
    LightSystemBarIcons()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val vm: FilterViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val pages = session.pages
    var panel by remember { mutableStateOf(Panel.NONE) }
    var markup by remember { mutableStateOf<MarkupMode?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var aligning by remember { mutableStateOf(false) }

    LaunchedEffect(pages.size) {
        // pages are also cleared by a successful save, which navigates on its own
        if (pages.isEmpty() && !session.saving) onBack()
    }
    LaunchedEffect(session.savedDocId) {
        // navigation must happen on the main thread and while the activity is visible
        if (session.savedDocId != null) {
            lifecycleOwner.lifecycle.withStarted { session.consumeSaved()?.let(onSaved) }
        }
    }
    LaunchedEffect(session.saveError) {
        val e = session.consumeSaveError() ?: return@LaunchedEffect
        Log.e(TAG, "Saving pages failed", e)
        Toast.makeText(
            context,
            context.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
    // always intercept Back: while saving it must not pop the screen under the progress overlay
    BackHandler { if (!session.saving && !aligning && !vm.extracting) onBack() }

    if (pages.isEmpty()) {
        // pages are cleared right after saving, while navigation to the document happens
        Box(Modifier.fillMaxSize().background(ScreenColor), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val index = session.currentIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]

    LaunchedEffect(page) {
        vm.render(page)
        vm.renderThumbnails(page)
    }

    markup?.let { mode ->
        val pageId = page.id
        MarkupEditor(
            // while a new preview is computed the page may still be shown turned the old way
            image = if (vm.busy) null else vm.cleanPreview,
            initialMarks = page.marks,
            mode = mode,
            onDone = { marks ->
                session.update(session.pages.indexOfFirst { it.id == pageId }) { it.copy(marks = marks) }
                markup = null
            },
            onCancel = { markup = null },
        )
        return
    }

    val appliedAll = stringResource(R.string.filter_applied_all)
    val alignedMessage = stringResource(R.string.filter_aligned)
    val noTextMessage = stringResource(R.string.filter_align_no_text)

    fun autoAlign() {
        if (aligning) return
        aligning = true
        scope.launch {
            val found = try {
                session.autoAlign(index)
            } finally {
                aligning = false
            }
            snackbar.showSnackbar(if (found) alignedMessage else noTextMessage)
        }
    }

    fun toggle(p: Panel) {
        panel = if (panel == p) Panel.NONE else p
    }

    fun selectFilter(f: ScanFilter) {
        if (f == page.filter) {
            // tapping the chosen filter again opens its strength
            if (f != ScanFilter.ORIGINAL) toggle(Panel.STRENGTH)
        } else {
            session.update(index) { it.copy(filter = f, adjustments = it.adjustments.copy(strength = 1f)) }
            if (panel == Panel.STRENGTH) panel = Panel.NONE
        }
    }

    Scaffold(
        containerColor = ScreenColor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { renaming = true }.padding(4.dp),
                    ) {
                        Text(
                            session.documentName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.filter_rename), modifier = Modifier.size(18.dp))
                    }
                },
                navigationIcon = { BackButton { if (!session.saving) onBack() } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val shown = if (comparing) vm.original ?: vm.preview else vm.preview
                if (shown != null) {
                    Image(
                        shown,
                        contentDescription = if (comparing) stringResource(R.string.filter_compare) else null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (vm.busy || shown == null) CircularProgressIndicator()
                if (pages.size > 1) {
                    IconButton(
                        onClick = { session.currentIndex = (index - 1).coerceAtLeast(0) },
                        enabled = index > 0,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.page_previous), tint = Color.White) }
                    IconButton(
                        onClick = { session.currentIndex = (index + 1).coerceAtMost(pages.lastIndex) },
                        enabled = index < pages.lastIndex,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.page_next), tint = Color.White) }
                    Text(
                        "${index + 1}/${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                CompareButton(onPressedChange = { comparing = it }, modifier = Modifier.align(Alignment.TopEnd))
            }

            AnimatedVisibility(panel == Panel.STRENGTH && page.filter != ScanFilter.ORIGINAL) {
                Row(
                    Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.filter_strength),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(72.dp),
                    )
                    Slider(
                        value = page.adjustments.strength,
                        onValueChange = { v ->
                            session.update(index) { it.copy(adjustments = it.adjustments.copy(strength = (v * 100).roundToInt() / 100f)) }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(page.adjustments.strength * 100).roundToInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp),
                    )
                }
            }

            AnimatedVisibility(panel == Panel.STRAIGHTEN) {
                // the measured angle is only known once this page's preview has been rendered
                val measured = if (vm.appliedSkewPage == page.id) vm.appliedSkew else null
                StraightenPanel(
                    auto = page.skew == null,
                    angle = page.skew ?: measured,
                    onAutoChange = { auto ->
                        // switching auto off keeps the measured angle as the starting point
                        session.update(index) { it.copy(skew = if (auto) null else measured ?: 0f) }
                    },
                    onAngleChange = { v -> session.update(index) { it.copy(skew = (v * 10).roundToInt() / 10f) } },
                    onReset = { session.update(index) { it.copy(skew = 0f) } },
                    onAutoAlign = ::autoAlign,
                )
            }

            AnimatedVisibility(panel == Panel.ADJUST) {
                Column(Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AdjustSlider(stringResource(R.string.filter_brightness), page.adjustments.brightness, -1f..1f) { v ->
                        session.update(index) { it.copy(adjustments = it.adjustments.copy(brightness = v)) }
                    }
                    AdjustSlider(stringResource(R.string.filter_contrast), page.adjustments.contrast, -1f..1f) { v ->
                        session.update(index) { it.copy(adjustments = it.adjustments.copy(contrast = v)) }
                    }
                    AdjustSlider(stringResource(R.string.filter_sharpness), page.adjustments.sharpness, 0f..1f) { v ->
                        session.update(index) { it.copy(adjustments = it.adjustments.copy(sharpness = v)) }
                    }
                    TextButton(
                        onClick = { session.update(index) { it.copy(adjustments = Adjustments(strength = it.adjustments.strength)) } },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.filter_reset)) }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(BarColor),
            ) {
                items(ScanFilter.entries) { f ->
                    FilterThumb(
                        label = stringResource(f.label),
                        thumb = vm.thumbnails[f],
                        isSelected = f == page.filter,
                        adjustable = f != ScanFilter.ORIGINAL,
                        onClick = { selectFilter(f) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().background(BarColor).navigationBarsPadding().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Top) {
                    ToolbarItem(Icons.Filled.Rotate90DegreesCcw, stringResource(R.string.crop_rotate_left)) {
                        session.rotate(index, 270)
                    }
                    ToolbarItem(Icons.Filled.Create, stringResource(R.string.filter_markup)) {
                        panel = Panel.NONE
                        markup = MarkupMode.PEN
                    }
                    ToolbarItem(Icons.Filled.TextFields, stringResource(R.string.filter_extract_text)) {
                        vm.extractText(page)
                    }
                    ToolbarItem(Icons.Filled.Gesture, stringResource(R.string.filter_sign)) {
                        panel = Panel.NONE
                        markup = MarkupMode.SIGN
                    }
                    ToolbarItem(Icons.Filled.AutoFixHigh, stringResource(R.string.filter_auto_align), onClick = ::autoAlign)
                    ToolbarItem(Icons.Filled.Straighten, stringResource(R.string.filter_straighten), isSelected = panel == Panel.STRAIGHTEN) {
                        toggle(Panel.STRAIGHTEN)
                    }
                    ToolbarItem(Icons.Filled.Tune, stringResource(R.string.filter_adjust), isSelected = panel == Panel.ADJUST) {
                        toggle(Panel.ADJUST)
                    }
                    if (pages.size > 1) {
                        ToolbarItem(Icons.Filled.DoneAll, stringResource(R.string.filter_apply_all)) {
                            val f = page.filter
                            val a = page.adjustments
                            for (i in pages.indices) session.update(i) { it.copy(filter = f, adjustments = a) }
                            scope.launch { snackbar.showSnackbar(appliedAll) }
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { session.save() },
                    shape = CircleShape,
                    containerColor = Brand,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save), modifier = Modifier.size(30.dp))
                }
            }
        }
    }

    if (renaming) RenameDialog(session.documentName, onRename = session::rename, onDismiss = { renaming = false })

    vm.extractedText?.let { text -> ExtractedTextDialog(text, onDismiss = vm::dismissText) }

    // the page must not change under a running alignment, and saving waits for it
    if (aligning) ProgressDialog(message = stringResource(R.string.filter_aligning))
    if (vm.extracting) ProgressDialog(message = stringResource(R.string.filter_extracting))

    session.saveProgress?.let { (done, total) ->
        ProgressDialog(
            message = stringResource(R.string.filter_saving),
            progress = if (total > 0) done / total.toFloat() else null,
        )
    }
}

/** "Compare": shows the page without filter while it is held down. */
@Composable
private fun CompareButton(onPressedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val onChange by rememberUpdatedState(onPressedChange)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onChange(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        onChange(false)
                    }
                })
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Compare, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.filter_compare), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** A filter preview with its name across the bottom, CamScanner style. */
@Composable
private fun FilterThumb(label: String, thumb: ImageBitmap?, isSelected: Boolean, adjustable: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            // wide enough for the longest name, "Không có bóng", on one line
            .size(82.dp, 100.dp)
            .clip(shape)
            .background(Color(0xFF2A302E))
            .border(if (isSelected) 2.5.dp else 0.dp, if (isSelected) accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .semantics { selected = isSelected },
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (isSelected && adjustable) {
            // tapping the chosen filter again opens its strength
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)
                    .background(accent, CircleShape).padding(3.dp),
            )
        }
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(if (isSelected) accent else Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 2.dp, vertical = 3.dp),
        )
    }
}

/** A tool of the bottom bar: icon above a label of up to two lines. */
@Composable
private fun ToolbarItem(icon: ImageVector, label: String, isSelected: Boolean = false, onClick: () -> Unit) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 13.sp,
        )
    }
}

/** Recognised text of the page, editable before it is copied or shared. */
@Composable
private fun ExtractedTextDialog(initial: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember(initial) { mutableStateOf(initial) }
    val copied = stringResource(R.string.ocr_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_extract_text)) },
        text = {
            if (initial.isBlank()) {
                Text(stringResource(R.string.ocr_empty))
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                )
            }
        },
        confirmButton = {
            if (initial.isNotBlank()) {
                Row {
                    TextButton(onClick = { Share.shareText(context, text) }) { Text(stringResource(R.string.share)) }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("OCR", text))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Straightening controls: automatic measuring, a fine angle slider and automatic alignment. */
@Composable
private fun StraightenPanel(
    auto: Boolean,
    angle: Float?,
    onAutoChange: (Boolean) -> Unit,
    onAngleChange: (Float) -> Unit,
    onReset: () -> Unit,
    onAutoAlign: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.filter_straighten_auto),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = auto, onCheckedChange = onAutoChange, enabled = angle != null)
        }
        Text(
            stringResource(R.string.filter_straighten_angle, angle?.let { String.format(Locale.US, "%+.1f", it) } ?: "…"),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
        // moving the slider takes over from the automatic angle
        Slider(
            value = (angle ?: 0f).coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN),
            onValueChange = onAngleChange,
            valueRange = -MAX_STRAIGHTEN..MAX_STRAIGHTEN,
            enabled = angle != null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.filter_reset)) }
            TextButton(onClick = onAutoAlign) { Text(stringResource(R.string.filter_auto_align)) }
        }
    }
}

private const val MAX_STRAIGHTEN = 20f

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

internal val ScreenColor = Color(0xFF101312)
internal val BarColor = Color(0xFF181C1B)

private const val TAG = "FilterScreen"
