package com.camscaner.app.ui.crop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.camscaner.app.ui.ScanSessionViewModel
import com.camscaner.app.ui.components.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(session: ScanSessionViewModel, onBack: () -> Unit, onNext: () -> Unit) {
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

    val image by produceState<ImageBitmap?>(null, page.originalFile) {
        value = withContext(Dispatchers.IO) { ImageIO.decodeSampled(page.originalFile, 1600)?.asImageBitmap() }
    }
    val invalidMessage = stringResource(R.string.crop_invalid)

    Scaffold(
        containerColor = Color(0xFF101312),
        snackbarHost = { SnackbarHost(snackbar) },
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
                    TextButton(onClick = {
                        val bad = pages.indexOfFirst { it.corners != null && !it.corners.isValid() }
                        if (bad >= 0) {
                            session.currentIndex = bad
                            scope.launch { snackbar.showSnackbar(invalidMessage) }
                        } else {
                            onNext()
                        }
                    }) { Text(stringResource(R.string.next)) }
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
                        corners = page.corners ?: Corners.FULL,
                        onCornersChange = { c -> session.update(index) { it.copy(corners = c) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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

            if (pages.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
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
            }

            Row(
                Modifier.fillMaxWidth().background(Color(0xFF181C1B)).navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.Filled.AutoAwesome, stringResource(R.string.crop_auto)) {
                    scope.launch { session.autoDetect(index) }
                }
                ToolButton(Icons.Filled.CropFree, stringResource(R.string.crop_full)) {
                    session.update(index) { it.copy(corners = null) }
                }
                if (pages.size > 1) {
                    ToolButton(Icons.Filled.DeleteOutline, stringResource(R.string.crop_delete_page)) {
                        session.remove(index)
                    }
                }
                Button(onClick = {
                    val bad = pages.indexOfFirst { it.corners != null && !it.corners.isValid() }
                    if (bad >= 0) {
                        session.currentIndex = bad
                        scope.launch { snackbar.showSnackbar(invalidMessage) }
                    } else onNext()
                }) { Text(stringResource(R.string.next)) }
            }
        }
    }
}

@Composable
internal fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
