package com.camscaner.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.camscaner.app.R
import com.camscaner.app.scannerApp
import com.camscaner.app.ui.ScanSessionViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(session: ScanSessionViewModel, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraContent(session, onClose, onDone)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.camera_permission_message), color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.camera_permission_grant))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

private enum class FlashState { OFF, AUTO, ON, TORCH }

@Composable
private fun CameraContent(session: ScanSessionViewModel, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = context.scannerApp.settings
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    var detection by remember { mutableStateOf<FrameDetection?>(null) }
    var flash by remember { mutableStateOf(FlashState.OFF) }
    var capturing by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    val flashOverlay = remember { Animatable(0f) }
    val tracker = remember { StabilityTracker() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val ratio43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(ratio43).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor, DocumentAnalyzer { detection = it })
            try {
                p.unbindAll()
                cameraRef = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            } catch (e: Exception) {
                Log.e("CameraScreen", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
        }
    }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        scope.launch {
            flashOverlay.snapTo(0.7f)
            flashOverlay.animateTo(0f, tween(300))
        }
        val file = File(context.cacheDir, "scan/raw_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        imageCapture.flashMode = when (flash) {
            FlashState.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashState.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val batch = settings.batchMode
                        if (!batch) session.clear()
                        session.addPhoto(file)
                        capturing = false
                        if (!batch && session.pages.isNotEmpty()) onDone()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(context, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    LaunchedEffect(detection) {
        val progress = tracker.update(detection?.corners)
        holdProgress = progress
        if (settings.autoCapture && progress >= 1f && !capturing) {
            tracker.disarm()
            capture()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                session.importUris(uris)
                if (session.pages.isNotEmpty()) onDone()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Canvas(Modifier.fillMaxSize()) {
            val d = detection
            val frameW = d?.frameWidth?.toFloat() ?: 3f
            val frameH = d?.frameHeight?.toFloat() ?: 4f
            val scale = min(size.width / frameW, size.height / frameH)
            val dw = frameW * scale
            val dh = frameH * scale
            val left = (size.width - dw) / 2f
            val top = (size.height - dh) / 2f
            if (settings.showGrid) {
                for (k in 1..2) {
                    val x = left + dw * k / 3f
                    val y = top + dh * k / 3f
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(x, top), Offset(x, top + dh), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(left, y), Offset(left + dw, y), 1.dp.toPx())
                }
            }
            val corners = d?.corners ?: return@Canvas
            val pts = corners.points.map { Offset(left + it.x * dw, top + it.y * dh) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                close()
            }
            val color = if (holdProgress > 0f) Color(0xFF3DDC97) else primary
            drawPath(path, color.copy(alpha = 0.22f))
            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            pts.forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
        }

        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashOverlay.value)))

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                flash = FlashState.entries[(flash.ordinal + 1) % FlashState.entries.size]
                cameraRef?.cameraControl?.enableTorch(flash == FlashState.TORCH)
            }) {
                val icon = when (flash) {
                    FlashState.OFF -> Icons.Filled.FlashOff
                    FlashState.AUTO -> Icons.Filled.FlashAuto
                    FlashState.ON -> Icons.Filled.FlashOn
                    FlashState.TORCH -> Icons.Filled.FlashlightOn
                }
                Icon(icon, stringResource(R.string.camera_flash), tint = Color.White)
            }
            IconButton(onClick = { settings.autoCapture = !settings.autoCapture }) {
                Icon(
                    Icons.Filled.AutoMode, stringResource(R.string.camera_auto_capture),
                    tint = if (settings.autoCapture) primary else Color.White,
                )
            }
            IconButton(onClick = { settings.showGrid = !settings.showGrid }) {
                Icon(Icons.Filled.GridOn, stringResource(R.string.camera_grid), tint = if (settings.showGrid) primary else Color.White)
            }
        }

        // Hint
        val hint = when {
            detection?.corners == null -> R.string.camera_hint_searching
            holdProgress > 0f && settings.autoCapture -> R.string.camera_hint_hold
            else -> R.string.camera_hint_found
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
        ) {
            Text(
                stringResource(hint),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding().padding(bottom = 16.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.camera_mode_single), !settings.batchMode) { settings.batchMode = false }
                ModeChip(stringResource(R.string.camera_mode_batch), settings.batchMode) { settings.batchMode = true }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.home_import), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                ShutterButton(
                    label = stringResource(R.string.camera_capture),
                    progress = if (settings.autoCapture) holdProgress else 0f,
                    busy = capturing,
                ) { capture() }
                val last = session.pages.lastOrNull()
                if (settings.batchMode && last != null) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            .clickable { onDone() },
                    ) {
                        AsyncImage(model = last.originalFile, contentDescription = stringResource(R.string.done), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(CircleShape).background(primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${session.pages.size}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Spacer(Modifier.width(56.dp))
                }
            }
        }

        if (session.importing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ShutterButton(label: String, progress: Float, busy: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp).semantics { contentDescription = label }) {
        if (progress > 0f) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF3DDC97),
                strokeWidth = 4.dp,
            )
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp)
                .clip(CircleShape).background(if (busy) Color.Gray else Color.White)
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}
