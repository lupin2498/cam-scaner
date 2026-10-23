package com.camscaner.app.ui.filter

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStarted
import com.camscaner.app.R
import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.ui.ScanSessionViewModel
import com.camscaner.app.ui.Share
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.crop.ToolButton
import com.camscaner.app.ui.components.LightSystemBarIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

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
    var showAdjust by remember { mutableStateOf(false) }
    var showStraighten by remember { mutableStateOf(false) }
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
    BackHandler { if (!session.saving) onBack() }

    if (pages.isEmpty()) {
        // pages are cleared right after saving, while navigation to the document happens
        Box(Modifier.fillMaxSize().background(Color(0xFF101312)), contentAlignment = Alignment.Center) {
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

    fun save() = session.save(Share.defaultDocumentName(context))

    Scaffold(
        containerColor = Color(0xFF101312),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pages.size > 1) "${stringResource(R.string.filter_title)} ${index + 1}/${pages.size}"
                        else stringResource(R.string.filter_title)
                    )
                },
                navigationIcon = { BackButton { if (!session.saving) onBack() } },
                actions = {
                    IconButton(onClick = ::save) { Icon(Icons.Filled.Check, stringResource(R.string.save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101312),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val bmp = vm.preview
                if (bmp != null) {
                    Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (vm.busy || bmp == null || aligning) CircularProgressIndicator()
                if (pages.size > 1) {
                    IconButton(
                        onClick = { session.currentIndex = (index - 1).coerceAtLeast(0) },
                        enabled = index > 0,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White) }
                    IconButton(
                        onClick = { session.currentIndex = (index + 1).coerceAtMost(pages.lastIndex) },
                        enabled = index < pages.lastIndex,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White) }
                }
            }

            AnimatedVisibility(showStraighten) {
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

            AnimatedVisibility(showAdjust) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF181C1B)).padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                        onClick = { session.update(index) { it.copy(adjustments = Adjustments()) } },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.filter_reset)) }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF181C1B)),
            ) {
                items(com.camscaner.app.scanner.ScanFilter.entries) { f ->
                    val selected = f == page.filter
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(72.dp).clickable { session.update(index) { it.copy(filter = f) } },
                    ) {
                        val thumb = vm.thumbnails[f]
                        Box(
                            Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2A302E))
                                .border(
                                    if (selected) 3.dp else 0.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        Text(
                            stringResource(f.label),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Color(0xFF181C1B)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.page_rotate)) {
                    session.update(index) { it.copy(rotation = (it.rotation + 90) % 360) }
                }
                ToolButton(Icons.Filled.AutoFixHigh, stringResource(R.string.filter_auto_align), onClick = ::autoAlign)
                ToolButton(
                    Icons.Filled.Straighten, stringResource(R.string.filter_straighten),
                    tint = if (showStraighten) MaterialTheme.colorScheme.primary else Color.White,
                ) {
                    showStraighten = !showStraighten
                    showAdjust = false
                }
                ToolButton(
                    Icons.Filled.Tune, stringResource(R.string.filter_adjust),
                    tint = if (showAdjust) MaterialTheme.colorScheme.primary else Color.White,
                ) {
                    showAdjust = !showAdjust
                    showStraighten = false
                }
                if (pages.size > 1) {
                    ToolButton(Icons.Filled.DoneAll, stringResource(R.string.filter_apply_all)) {
                        val f = page.filter
                        val a = page.adjustments
                        for (i in pages.indices) session.update(i) { it.copy(filter = f, adjustments = a) }
                        scope.launch { snackbar.showSnackbar(appliedAll) }
                    }
                }
            }
        }
    }

    session.saveProgress?.let { (done, total) ->
        ProgressDialog(
            message = stringResource(R.string.filter_saving),
            progress = if (total > 0) done / total.toFloat() else null,
        )
    }
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
    Column(Modifier.fillMaxWidth().background(Color(0xFF181C1B)).padding(horizontal = 16.dp, vertical = 4.dp)) {
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

private const val TAG = "FilterScreen"
