package com.camscaner.app.ui.filter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.camscaner.app.R
import com.camscaner.app.scanner.MarkStroke
import com.camscaner.app.scanner.MarkupRenderer
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.crop.ToolButton
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the markup editor is opened for. */
enum class MarkupMode { PEN, SIGN }

private enum class Ink { PEN, HIGHLIGHTER }

private val PenColors = listOf(0xFFE53935.toInt(), 0xFF1E5BD8.toInt(), 0xFF111111.toInt())
private val HighlighterColors = listOf(0xFFFFEB3B.toInt(), 0xFF76FF03.toInt(), 0xFFFF80AB.toInt())
private val SignatureColors = listOf(0xFF1A3DB8.toInt(), 0xFF111111.toInt(), 0xFFD32F2F.toInt())

/** Stroke widths relative to sqrt(page width × height), as stored in [MarkStroke.width]. */
private const val PEN_WIDTH = 0.004f
private const val HIGHLIGHTER_WIDTH = 0.022f

/** Same see-through ink as [MarkupRenderer] uses on the saved page. */
private const val HIGHLIGHTER_ALPHA = 110 / 255f

/** Smallest signature, as a fraction of the page width. */
private const val MIN_SIGNATURE = 0.08f

/**
 * A signature drawn on the pad: [strokes] are x, y pairs normalized to its bounding box,
 * [aspect] is that box's width / height and [width] the ink width relative to the box height.
 */
private class Signature(val strokes: List<List<Float>>, val aspect: Float, val width: Float, val color: Int) {

    /** The strokes placed on a page of [pageW] × [pageH] pixels at [place], as one group. */
    fun toMarks(place: Placement, pageW: Int, pageH: Int, group: Int): List<MarkStroke> {
        val h = place.height(this, pageW.toFloat() / pageH)
        // the ink keeps its weight relative to the signature; marks store it relative to the page
        val strokeWidth = width * h * pageH / sqrt(pageW.toFloat() * pageH)
        return strokes.map { s ->
            val points = ArrayList<Float>(s.size)
            for (i in 0 until s.size / 2) {
                points += place.x + s[2 * i] * place.w
                points += place.y + s[2 * i + 1] * h
            }
            MarkStroke(points, color, strokeWidth, group = group)
        }
    }

    companion object {
        /** Crops [strokes] (pad pixels) to their ink, keeping a margin of one ink width. */
        fun from(strokes: List<List<Offset>>, inkWidth: Float, color: Int): Signature {
            val all = strokes.flatten()
            val minX = all.minOf { it.x } - inkWidth
            val minY = all.minOf { it.y } - inkWidth
            val w = (all.maxOf { it.x } + inkWidth - minX).coerceAtLeast(1f)
            val h = (all.maxOf { it.y } + inkWidth - minY).coerceAtLeast(1f)
            val normalized = strokes.map { s ->
                val points = ArrayList<Float>(s.size * 2)
                for (p in s) {
                    points += (p.x - minX) / w
                    points += (p.y - minY) / h
                }
                points
            }
            return Signature(normalized, w / h, inkWidth / h, color)
        }
    }
}

/** Where a signature sits on the page: normalized left, top and width; the height follows its shape. */
private data class Placement(val x: Float, val y: Float, val w: Float) {

    fun height(signature: Signature, pageAspect: Float) = w * pageAspect / signature.aspect

    fun rect(signature: Signature, pageAspect: Float) = Rect(x, y, x + w, y + height(signature, pageAspect))

    fun moved(dx: Float, dy: Float, signature: Signature, pageAspect: Float): Placement {
        val h = height(signature, pageAspect)
        return copy(
            x = (x + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f)),
            y = (y + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f)),
        )
    }

    /** Resized by dragging its bottom-right corner [dx] to the right; it stays on the page. */
    fun resized(dx: Float, signature: Signature, pageAspect: Float): Placement {
        val maxW = min(1f - x, (1f - y) * signature.aspect / pageAspect)
        return copy(w = (w + dx).coerceIn(min(MIN_SIGNATURE, maxW), maxW))
    }

    companion object {
        /** Centred on the page, wide enough to read but no taller than a fifth of it. */
        fun initial(signature: Signature, pageAspect: Float): Placement {
            val w = min(0.45f, 0.2f * signature.aspect / pageAspect)
            val h = w * pageAspect / signature.aspect
            return Placement((1f - w) / 2f, (1f - h) / 2f, w)
        }
    }
}

/**
 * Full-screen editor drawing on the finished page: pen and highlighter strokes ([MarkupMode.PEN])
 * or a signature drawn on a pad, then moved and resized on the page ([MarkupMode.SIGN]).
 * [image] is the page without marks (null while it is being prepared); [onDone] receives all
 * marks of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkupEditor(
    image: ImageBitmap?,
    initialMarks: List<MarkStroke>,
    mode: MarkupMode,
    onDone: (List<MarkStroke>) -> Unit,
    onCancel: () -> Unit,
) {
    var marks by remember { mutableStateOf(initialMarks) }
    var ink by remember { mutableStateOf(Ink.PEN) }
    var penColor by remember { mutableIntStateOf(PenColors[0]) }
    var highlighterColor by remember { mutableIntStateOf(HighlighterColors[0]) }
    var signature by remember { mutableStateOf<Signature?>(null) }
    var placement by remember { mutableStateOf<Placement?>(null) }
    var padOpen by remember { mutableStateOf(mode == MarkupMode.SIGN) }

    BackHandler {
        // closing the pad over a placed signature keeps it; otherwise leave without changes
        if (padOpen && signature != null) padOpen = false else onCancel()
    }
    LaunchedEffect(signature, image) {
        // a signature finished while the page was still being prepared is placed once it is shown
        val sig = signature
        if (sig != null && placement == null && image != null) {
            placement = Placement.initial(sig, image.width.toFloat() / image.height)
        }
    }

    fun finish() {
        val sig = signature
        val place = placement
        val page = image
        onDone(
            if (mode == MarkupMode.SIGN && sig != null && place != null && page != null) {
                marks + sig.toMarks(place, page.width, page.height, MarkStroke.nextGroup(marks))
            } else {
                marks
            }
        )
    }

    Scaffold(
        containerColor = ScreenColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (mode == MarkupMode.PEN) R.string.filter_markup else R.string.filter_sign)) },
                navigationIcon = { BackButton(onCancel) },
                actions = {
                    IconButton(onClick = ::finish, enabled = image != null) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.done))
                    }
                },
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
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (image == null) {
                    CircularProgressIndicator()
                } else {
                    PageCanvas(
                        image = image,
                        marks = marks,
                        drawing = mode == MarkupMode.PEN,
                        ink = ink,
                        color = if (ink == Ink.PEN) penColor else highlighterColor,
                        onStroke = { marks = marks + it },
                        signature = signature,
                        placement = placement,
                        onPlacement = { placement = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (mode == MarkupMode.PEN) {
                PenControls(
                    ink = ink,
                    color = if (ink == Ink.PEN) penColor else highlighterColor,
                    canUndo = marks.isNotEmpty(),
                    onInk = { ink = it },
                    onColor = { c -> if (ink == Ink.PEN) penColor = c else highlighterColor = c },
                    onUndo = { marks = MarkStroke.undo(marks) },
                    onClear = { marks = emptyList() },
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().background(BarColor).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.sign_hint),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    ToolButton(Icons.Filled.Refresh, stringResource(R.string.sign_redraw)) { padOpen = true }
                }
            }
        }
    }

    if (padOpen) {
        SignaturePad(
            initialColor = signature?.color ?: SignatureColors[0],
            onPlace = { sig ->
                signature = sig
                val page = image
                placement = if (page != null) Placement.initial(sig, page.width.toFloat() / page.height) else null
                padOpen = false
            },
            onCancel = { if (signature == null) onCancel() else padOpen = false },
        )
    }
}

/** The page with its marks; draws strokes or moves the signature being placed. */
@Composable
private fun PageCanvas(
    image: ImageBitmap,
    marks: List<MarkStroke>,
    drawing: Boolean,
    ink: Ink,
    color: Int,
    onStroke: (MarkStroke) -> Unit,
    signature: Signature?,
    placement: Placement?,
    onPlacement: (Placement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val current = remember { mutableStateListOf<Float>() }
    val currentInk by rememberUpdatedState(ink)
    val currentColor by rememberUpdatedState(color)
    val strokeDone by rememberUpdatedState(onStroke)
    val currentPlacement by rememberUpdatedState(placement)
    val placementChange by rememberUpdatedState(onPlacement)
    val label = stringResource(R.string.markup_hint)
    val pageAspect = image.width.toFloat() / image.height

    BoxWithConstraints(modifier) {
        val margin = with(density) { 16.dp.toPx() }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min((boxW - 2 * margin) / image.width, (boxH - 2 * margin) / image.height).coerceAtLeast(0.01f)
        val dw = image.width * scale
        val dh = image.height * scale
        val left = (boxW - dw) / 2f
        val top = (boxH - dh) / 2f
        val unit = sqrt(dw * dh)
        val handle = with(density) { 12.dp.toPx() }
        val grab = with(density) { 36.dp.toPx() }

        fun normalized(p: Offset) = Offset(((p.x - left) / dw).coerceIn(0f, 1f), ((p.y - top) / dh).coerceIn(0f, 1f))
        fun onPage(p: Offset) = p.x in left..(left + dw) && p.y in top..(top + dh)
        fun screenRect(r: Rect) = Rect(left + r.left * dw, top + r.top * dh, left + r.right * dw, top + r.bottom * dh)

        val gestures = when {
            drawing -> Modifier.pointerInput(left, top, dw, dh) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!onPage(down.position)) return@awaitEachGesture
                    down.consume()
                    current.clear()
                    normalized(down.position).let { current += it.x; current += it.y }
                    drag(down.id) { change ->
                        normalized(change.position).let { current += it.x; current += it.y }
                        change.consume()
                    }
                    val highlighter = currentInk == Ink.HIGHLIGHTER
                    strokeDone(MarkStroke(current.toList(), currentColor, if (highlighter) HIGHLIGHTER_WIDTH else PEN_WIDTH, highlighter))
                    current.clear()
                }
            }
            signature != null -> Modifier.pointerInput(left, top, dw, dh, signature) {
                var resizing = false
                var moving = false
                detectDragGestures(
                    onDragStart = { pos ->
                        val box = currentPlacement?.let { screenRect(it.rect(signature, pageAspect)) }
                        resizing = box != null && (pos - box.bottomRight).getDistance() < grab
                        moving = !resizing && box != null && box.inflate(handle).contains(pos)
                    },
                    onDrag = { change, amount ->
                        val place = currentPlacement ?: return@detectDragGestures
                        if (!resizing && !moving) return@detectDragGestures
                        change.consume()
                        placementChange(
                            if (resizing) place.resized(amount.x / dw, signature, pageAspect)
                            else place.moved(amount.x / dw, amount.y / dh, signature, pageAspect)
                        )
                    },
                    onDragEnd = { resizing = false; moving = false },
                    onDragCancel = { resizing = false; moving = false },
                )
            }
            else -> Modifier
        }

        Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }.then(gestures)) {
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            translate(left, top) {
                clipRect(0f, 0f, dw, dh) {
                    for (m in marks) drawInk(m.points, m.color, m.width * unit, m.highlighter, dw, dh)
                    if (current.isNotEmpty()) {
                        val highlighter = ink == Ink.HIGHLIGHTER
                        drawInk(current.toList(), color, (if (highlighter) HIGHLIGHTER_WIDTH else PEN_WIDTH) * unit, highlighter, dw, dh)
                    }
                }
                if (signature != null && placement != null) {
                    val r = placement.rect(signature, pageAspect)
                    val bx = r.left * dw
                    val by = r.top * dh
                    val bw = r.width * dw
                    val bh = r.height * dh
                    translate(bx, by) {
                        for (s in signature.strokes) drawInk(s, signature.color, signature.width * bh, false, bw, bh)
                    }
                    drawRect(
                        Color.White,
                        topLeft = Offset(bx, by),
                        size = Size(bw, bh),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
                    )
                    drawRect(accent, topLeft = Offset(bx, by), size = Size(bw, bh), style = Stroke(1.dp.toPx()))
                    val corner = Offset(bx + bw, by + bh)
                    drawCircle(Color.White, handle, corner)
                    drawCircle(accent, handle, corner, style = Stroke(3.dp.toPx()))
                }
            }
        }
    }
}

/** Draws a stroke of normalized [points] scaled to [w] × [h], like [MarkupRenderer] does on the page. */
private fun DrawScope.drawInk(points: List<Float>, color: Int, width: Float, highlighter: Boolean, w: Float, h: Float) {
    if (points.size < 2) return
    drawPath(
        MarkupRenderer.path(points, w, h).asComposePath(),
        color = Color(color).let { if (highlighter) it.copy(alpha = HIGHLIGHTER_ALPHA) else it },
        style = Stroke(
            width = width.coerceAtLeast(1f),
            cap = if (highlighter) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
        blendMode = if (highlighter) BlendMode.Multiply else DrawScope.DefaultBlendMode,
    )
}

/** Draws a stroke of pad pixels. */
private fun DrawScope.drawInk(points: List<Offset>, color: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, width / 2f, points[0])
        return
    }
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val p = points[i]
        // quadratic segments through the midpoints smooth the finger's polyline
        path.quadraticBezierTo(prev.x, prev.y, (prev.x + p.x) / 2f, (prev.y + p.y) / 2f)
    }
    path.lineTo(points.last().x, points.last().y)
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun PenControls(
    ink: Ink,
    color: Int,
    canUndo: Boolean,
    onInk: (Ink) -> Unit,
    onColor: (Int) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val colorLabel = stringResource(R.string.markup_color)
    Column(Modifier.fillMaxWidth().background(BarColor).navigationBarsPadding().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val palette = if (ink == Ink.PEN) PenColors else HighlighterColors
            palette.forEachIndexed { i, c ->
                ColorDot(c, isSelected = c == color, description = "$colorLabel ${i + 1}", ring = accent, onClick = { onColor(c) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Filled.Create, stringResource(R.string.markup_pen), tint = if (ink == Ink.PEN) accent else Color.White) {
                onInk(Ink.PEN)
            }
            ToolButton(Icons.Filled.BorderColor, stringResource(R.string.markup_highlighter), tint = if (ink == Ink.HIGHLIGHTER) accent else Color.White) {
                onInk(Ink.HIGHLIGHTER)
            }
            ToolButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.markup_undo), enabled = canUndo, onClick = onUndo)
            ToolButton(Icons.Filled.DeleteOutline, stringResource(R.string.markup_clear), enabled = canUndo, onClick = onClear)
        }
    }
}

@Composable
private fun ColorDot(color: Int, isSelected: Boolean, description: String, ring: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) ring else Color.Gray, CircleShape)
            .padding(6.dp)
            .background(Color(color), CircleShape)
    )
}

/** Overlay where the signature is drawn before it is placed on the page. */
@Composable
private fun SignaturePad(initialColor: Int, onPlace: (Signature) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableIntStateOf(initialColor) }
    val inkWidth = with(LocalDensity.current) { 3.dp.toPx() }
    val title = stringResource(R.string.sign_pad_title)
    val colorLabel = stringResource(R.string.markup_color)
    val accent = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // the editor below must not react meanwhile
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF1B1F1E))
                Text(stringResource(R.string.sign_pad_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6664))
                Spacer(Modifier.height(10.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF4F6F5))
                        .border(1.dp, Color(0xFFD5DBD9), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = title }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                current.clear()
                                current += down.position
                                drag(down.id) { change ->
                                    current += change.position
                                    change.consume()
                                }
                                strokes.add(current.toList())
                                current.clear()
                            }
                        }
                ) {
                    val y = size.height * 0.78f
                    drawLine(Color(0xFFB8C0BD), Offset(size.width * 0.08f, y), Offset(size.width * 0.92f, y), 1.dp.toPx())
                    for (s in strokes) drawInk(s, Color(color), inkWidth)
                    drawInk(current.toList(), Color(color), inkWidth)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SignatureColors.forEachIndexed { i, c ->
                        ColorDot(c, isSelected = c == color, description = "$colorLabel ${i + 1}", ring = accent, onClick = { color = c })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.sign_redraw)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onPlace(Signature.from(strokes.toList(), inkWidth, color)) },
                        enabled = strokes.isNotEmpty(),
                    ) { Text(stringResource(R.string.sign_place)) }
                }
            }
        }
    }
}
