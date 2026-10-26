package com.camscaner.app.ui.crop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.camscaner.app.R
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.ImageIO
import com.camscaner.app.scanner.PaperPreset
import com.camscaner.app.scanner.rotated
import com.camscaner.app.ui.ScanSessionViewModel
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.LightSystemBarIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(session: ScanSessionViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    LightSystemBarIcons()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pages = session.pages

    LaunchedEffect(pages.size) {
        if (pages.isEmpty()) onBack()
    }
    if (pages.isEmpty()) return
    val index = session.currentIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]

    BackHandler(onBack = onBack)

    // the photo is shown turned the way the page will come out; corners are kept on the photo
    val image by produceState<ImageBitmap?>(null, page.originalFile, page.rotation) {
        // never show the previous page's photo under this page's outline
        value = null
        value = withContext(Dispatchers.IO) {
            ImageIO.decodeSampled(page.originalFile, 1600)?.let { ImageIO.rotate(it, page.rotation) }?.asImageBitmap()
        }
    }
    val invalidMessage = stringResource(R.string.crop_invalid)
    val splitMessage = stringResource(R.string.crop_split_done)
    fun next() {
        val bad = pages.indexOfFirst { it.corners != null && !it.corners.isValid() }
        if (bad >= 0) {
            session.currentIndex = bad
            scope.launch { snackbar.showSnackbar(invalidMessage) }
        } else {
            onNext()
        }
    }

    Scaffold(
        containerColor = Color(0xFF101312),
        // the tool row is part of the content, so the snackbar is lifted above it
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 84.dp)) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pages.size > 1) stringResource(R.string.crop_page_of, index + 1, pages.size)
                        else stringResource(R.string.crop_title)
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (pages.size > 1) {
                        IconButton(onClick = { session.remove(index) }) {
                            Icon(Icons.Filled.DeleteOutline, stringResource(R.string.crop_delete_page))
                        }
                    }
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
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = image
                if (bmp == null) {
                    CircularProgressIndicator()
                } else {
                    CropEditor(
                        image = bmp,
                        corners = (page.corners ?: Corners.FULL).rotated(page.rotation),
                        // edited on the turned photo, stored on the photo as taken
                        onCornersChange = { c -> session.update(index) { it.copy(corners = c.rotated(360 - it.rotation)) } },
                        modifier = Modifier.fillMaxSize(),
                        label = stringResource(R.string.crop_editor),
                    )
                }
            }

            // paper formats: the outline is fitted to the chosen one inside the detected sheet and
            // the page comes out with exactly its proportions
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.crop_paper),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 4.dp),
                )
                PaperChip(stringResource(R.string.paper_free), page.paper == null) { session.fitPaper(index, null) }
                PaperPreset.entries.forEach { p ->
                    PaperChip(stringResource(p.label), page.paper == p) { session.fitPaper(index, p) }
                }
            }

            if (pages.size > 1) {
                // page arrows live beside the strip: over the photo they would cover the edge handles
                Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { session.currentIndex = (index - 1).coerceAtLeast(0) },
                        enabled = index > 0,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White) }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                            AsyncImage(
                                model = p.originalFile,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(6.dp))
                                    .border(2.dp, if (i == index) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { session.currentIndex = i },
                            )
                        }
                    }
                    IconButton(
                        onClick = { session.currentIndex = (index + 1).coerceAtMost(pages.lastIndex) },
                        enabled = index < pages.lastIndex,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White) }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Color(0xFF181C1B)).navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.Filled.Rotate90DegreesCcw, stringResource(R.string.crop_rotate_left)) {
                    session.rotate(index, 270)
                }
                ToolButton(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.crop_rotate_right)) {
                    session.rotate(index, 90)
                }
                // "all" keeps the whole photo; on a whole photo the button finds the sheet again
                val whole = page.corners == null || page.corners == Corners.FULL
                if (whole) {
                    ToolButton(Icons.Filled.AutoAwesome, stringResource(R.string.crop_auto)) {
                        scope.launch { session.autoDetect(index) }
                    }
                } else {
                    ToolButton(Icons.Filled.Fullscreen, stringResource(R.string.crop_full)) {
                        session.update(index) { it.copy(corners = null) }
                    }
                }
                // a photographed book spread becomes its two pages
                ToolButton(Icons.Filled.VerticalSplit, stringResource(R.string.crop_split)) {
                    session.split(index)
                    scope.launch { snackbar.showSnackbar(splitMessage) }
                }
                ToolButton(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.next), onClick = ::next)
            }
        }
    }
}

/** A paper format of the crop screen's strip. */
@Composable
private fun PaperChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFF2A302E),
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White,
        ),
        border = null,
    )
}

@Composable
internal fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}
