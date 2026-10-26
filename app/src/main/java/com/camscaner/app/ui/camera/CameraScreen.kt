package com.camscaner.app.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.camera.core.TorchState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.camscaner.app.R
import com.camscaner.app.scanner.PaperFit
import com.camscaner.app.scanner.PaperPreset
import com.camscaner.app.scannerApp
import com.camscaner.app.ui.ScanSessionViewModel
import com.camscaner.app.ui.components.ConfirmDialog
import com.camscaner.app.ui.components.LightSystemBarIcons
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(session: ScanSessionViewModel, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    LightSystemBarIcons()
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val activity = context as? Activity
    fun rationale() = activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    // Android only says "show a rationale" after an explicit denial; once that flag drops back to
    // false on a denial, the system dialog will not appear any more. A dismissed dialog keeps it.
    val prefs = remember { context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE) }
    var blocked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPermission = ok
        val rationaleNow = rationale()
        if (!ok && rationaleNow) prefs.edit().putBoolean(KEY_DENIED_ONCE, true).apply()
        blocked = !ok && !rationaleNow && prefs.getBoolean(KEY_DENIED_ONCE, false)
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // the permission may have been granted in the system settings meanwhile
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        onPauseOrDispose { }
    }
    fun requestPermission() {
        if (blocked) {
            // denied permanently: the system no longer shows the dialog
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // pages taken in this session are deleted on close, so ask first
    val unsaved = session.pages.count { it.existingPageId == null }
    var confirmClose by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (unsaved > 0) confirmClose = true else onClose()
    }
    BackHandler(onBack = close)
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.camera_discard_title),
            message = pluralStringResource(R.plurals.camera_discard_message, unsaved, unsaved),
            confirmLabel = stringResource(R.string.camera_discard),
            onConfirm = onClose,
            onDismiss = { confirmClose = false },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraContent(session, close, onDone, paused = confirmClose)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.camera_permission_message), color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::requestPermission) {
                    Text(stringResource(R.string.camera_permission_grant))
                }
            }
            IconButton(onClick = close, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

private enum class FlashState { OFF, AUTO, ON, TORCH }

@Composable
private fun CameraContent(session: ScanSessionViewModel, onClose: () -> Unit, onDone: () -> Unit, paused: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = context.scannerApp.settings
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    // every analysed frame must reach the tracker; results carry a frame number so equal
    // detections still restart the effect below
    var detection by remember { mutableStateOf<FrameDetection?>(null) }
    var flash by remember { mutableStateOf(FlashState.OFF) }
    var capturing by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
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
                    // ~12 MP is plenty for A4 at 300 dpi and keeps capture and processing fast
                    .setResolutionStrategy(ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .build()
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                cameraFailed = true
                return@addListener
            }
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
                // tablets and Chromebooks may only have a front camera
                val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
                cameraRef = p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                Log.i(TAG, "Camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                cameraFailed = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
        }
    }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
    // the torch goes off when the app is backgrounded; keep the flash icon in sync with it
    DisposableEffect(cameraRef) {
        val torch = cameraRef?.cameraInfo?.torchState
        val observer = Observer<Int> { state ->
            if (state == TorchState.OFF && flash == FlashState.TORCH) flash = FlashState.OFF
        }
        torch?.observe(lifecycleOwner, observer)
        onDispose { torch?.removeObserver(observer) }
    }

    fun capture() {
        // takePicture fails with "Not bound to a valid Camera" until binding has finished
        if (capturing || cameraRef == null) return
        capturing = true
        // the next auto-capture needs a new page, whether this shot was manual or automatic
        tracker.disarm()
        scope.launch {
            flashOverlay.snapTo(0.7f)
            flashOverlay.animateTo(0f, tween(300))
        }
        val file = File(context.cacheDir, "scan/raw_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        Log.i(TAG, "Capturing to ${file.name}")
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
                    Log.i(TAG, "Photo saved (${file.length()} bytes)")
                    // a single shot opens the crop screen through the navigation once processed
                    val job = session.addCapture(file, single = !settings.batchMode)
                    scope.launch {
                        job.join()
                        capturing = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    capturing = false
                    Toast.makeText(context, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    LaunchedEffect(detection) {
        val progress = tracker.update(detection?.corners)
        holdProgress = progress
        // not behind the photo picker, the discard dialog or an import: the user is busy with those
        val visible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val blocked = paused || pickerOpen || session.importing || !visible
        if (settings.autoCapture && progress >= 1f && !capturing && !blocked) {
            capture()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        pickerOpen = false
        // the navigation opens the crop screen once the import is done
        if (uris.isNotEmpty()) session.importImages(uris)
    }

    // paper format new photos are cut to: a guide frame on the preview, and the region of that
    // format inside the detected sheet is outlined instead of the sheet itself
    val paper = settings.cameraPaper

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (cameraFailed) {
            Text(
                stringResource(R.string.camera_unavailable),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

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
            if (paper != null) {
                val g = PaperFit.guide(paper, dw, dh)
                val gp = g.points.map { Offset(left + it.x * dw, top + it.y * dh) }
                val guidePath = Path().apply {
                    moveTo(gp[0].x, gp[0].y)
                    for (i in 1 until 4) lineTo(gp[i].x, gp[i].y)
                    close()
                }
                drawPath(
                    guidePath, Color.White.copy(alpha = 0.85f),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx()))),
                )
            }
            val frame = d ?: return@Canvas
            val found = frame.corners ?: return@Canvas
            val corners = if (paper != null) PaperFit.fit(found, paper, frame.frameWidth, frame.frameHeight) else found
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
            detection?.corners == null && paper != null -> stringResource(R.string.camera_hint_paper, stringResource(paper.label))
            detection?.corners == null -> stringResource(R.string.camera_hint_searching)
            holdProgress > 0f && settings.autoCapture -> stringResource(R.string.camera_hint_hold)
            else -> stringResource(R.string.camera_hint_found)
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
        ) {
            Text(
                hint,
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
                PaperMenuChip(paper) { settings.cameraPaper = it }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        pickerOpen = true
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
                    enabled = cameraRef != null,
                ) { capture() }
                val last = session.pages.lastOrNull()
                if (settings.batchMode && last != null) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            .clickable(enabled = !capturing) { onDone() },
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

/** Chip naming the paper format new photos are cut to; tapping it offers the formats. */
@Composable
private fun PaperMenuChip(paper: PaperPreset?, onSelect: (PaperPreset?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ModeChip(paper?.let { stringResource(it.label) } ?: stringResource(R.string.camera_paper), paper != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.paper_free)) }, onClick = { onSelect(null); open = false })
            PaperPreset.entries.forEach { p ->
                DropdownMenuItem(text = { Text(stringResource(p.label)) }, onClick = { onSelect(p); open = false })
            }
        }
    }
}

@Composable
private fun ShutterButton(label: String, progress: Float, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val active = enabled && !busy
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(84.dp).semantics {
            contentDescription = label
            if (!active) disabled()
        },
    ) {
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
                .clip(CircleShape).background(if (active) Color.White else Color.Gray)
                .clickable(enabled = active, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}

private const val TAG = "CameraScreen"

private const val PERMISSION_PREFS = "camera_permission"
private const val KEY_DENIED_ONCE = "denied_once"
