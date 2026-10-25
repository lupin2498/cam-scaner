package com.camscaner.app.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.Pt
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows [image] with the document outline. Corners (round handles) and edges (pill handles along
 * each side) can be dragged; a magnifier shows the area under the finger while moving a corner.
 * [label] describes the editor for accessibility.
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    corners: Corners,
    onCornersChange: (Corners) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val currentCorners by rememberUpdatedState(corners)
    val onChange by rememberUpdatedState(onCornersChange)
    var active by remember { mutableIntStateOf(-1) } // 0..3 corners, 4..7 edges

    BoxWithConstraints(modifier) {
        val pad = with(density) { 28.dp.toPx() }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min((boxW - 2 * pad) / image.width, (boxH - 2 * pad) / image.height).coerceAtLeast(0.01f)
        val dw = image.width * scale
        val dh = image.height * scale
        val left = (boxW - dw) / 2f
        val top = (boxH - dh) / 2f
        val touchRadius = with(density) { 40.dp.toPx() }

        fun toScreen(p: Pt) = Offset(left + p.x * dw, top + p.y * dh)

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { if (label != null) contentDescription = label }
                .pointerInput(image, left, top, dw, dh) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val pts = currentCorners.points.map(::toScreen)
                            var best = -1
                            var bestDistance = touchRadius
                            pts.forEachIndexed { i, p ->
                                val d = (p - pos).getDistance()
                                if (d < bestDistance) {
                                    best = i
                                    bestDistance = d
                                }
                            }
                            if (best < 0) {
                                for (i in 0 until 4) {
                                    val mid = (pts[i] + pts[(i + 1) % 4]) / 2f
                                    val d = (mid - pos).getDistance()
                                    if (d < bestDistance) {
                                        best = 4 + i
                                        bestDistance = d
                                    }
                                }
                            }
                            active = best
                        },
                        onDrag = { change, drag ->
                            val index = active
                            if (index < 0) return@detectDragGestures
                            change.consume()
                            val delta = Pt(drag.x / dw, drag.y / dh)
                            val c = currentCorners
                            val updated = if (index < 4) {
                                c.with(index, (c.points[index] + delta).clamp())
                            } else {
                                val i = index - 4
                                val j = (i + 1) % 4
                                val moved = c.with(i, (c.points[i] + delta).clamp())
                                moved.with(j, (moved.points[j] + delta).clamp())
                            }
                            onChange(updated)
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    )
                }
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            val pts = corners.points.map(::toScreen)
            val quad = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                close()
            }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + dw, top + dh))
                addPath(quad)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.3f))
            val lineColor = if (corners.isValid()) primary else Color(0xFFE53935)
            drawPath(quad, lineColor, style = Stroke(width = 2.5.dp.toPx()))

            // edge handles: white pills lying along each side
            val pillLength = 26.dp.toPx()
            val pillThickness = 10.dp.toPx()
            for (i in 0 until 4) {
                val a = pts[i]
                val b = pts[(i + 1) % 4]
                val mid = (a + b) / 2f
                val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                rotate(angle, pivot = mid) {
                    val topLeft = Offset(mid.x - pillLength / 2f, mid.y - pillThickness / 2f)
                    val pill = androidx.compose.ui.geometry.Size(pillLength, pillThickness)
                    val radius = CornerRadius(pillThickness / 2f)
                    drawRoundRect(if (i + 4 == active) lineColor.copy(alpha = 0.4f) else Color.White, topLeft, pill, radius)
                    drawRoundRect(lineColor, topLeft, pill, radius, style = Stroke(2.dp.toPx()))
                }
            }
            // corner handles: white discs with a coloured ring
            val cornerRadius = 11.dp.toPx()
            pts.forEachIndexed { i, p ->
                drawCircle(if (i == active) lineColor.copy(alpha = 0.4f) else Color.White, cornerRadius, p)
                drawCircle(lineColor, cornerRadius, p, style = Stroke(3.dp.toPx()))
            }

            if (active in 0..3) {
                val p = pts[active]
                val loupe = 56.dp.toPx()
                val margin = 16.dp.toPx()
                val center = Offset(
                    if (p.x < size.width / 2f) size.width - loupe - margin else loupe + margin,
                    loupe + margin,
                )
                val zoom = 2.5f
                // source square (image pixels) around the corner
                val srcSize = (2 * loupe / (scale * zoom)).roundToInt().coerceIn(8, min(image.width, image.height))
                val cx = corners.points[active].x * image.width
                val cy = corners.points[active].y * image.height
                val sx = (cx - srcSize / 2f).roundToInt().coerceIn(0, image.width - srcSize)
                val sy = (cy - srcSize / 2f).roundToInt().coerceIn(0, image.height - srcSize)
                val circle = Path().apply { addOval(Rect(center, loupe)) }
                clipPath(circle) {
                    drawRect(Color.Black, topLeft = center - Offset(loupe, loupe), size = androidx.compose.ui.geometry.Size(2 * loupe, 2 * loupe))
                    drawImage(
                        image,
                        srcOffset = IntOffset(sx, sy),
                        srcSize = IntSize(srcSize, srcSize),
                        dstOffset = IntOffset((center.x - loupe).roundToInt(), (center.y - loupe).roundToInt()),
                        dstSize = IntSize((2 * loupe).roundToInt(), (2 * loupe).roundToInt()),
                    )
                }
                // crosshair at the exact corner position inside the loupe
                val k = 2 * loupe / srcSize
                val target = Offset(center.x - loupe + (cx - sx) * k, center.y - loupe + (cy - sy) * k)
                val arm = 10.dp.toPx()
                drawLine(lineColor, target - Offset(arm, 0f), target + Offset(arm, 0f), 2.dp.toPx())
                drawLine(lineColor, target - Offset(0f, arm), target + Offset(0f, arm), 2.dp.toPx())
                drawCircle(Color.White, loupe, center, style = Stroke(3.dp.toPx()))
            }
        }
    }
}
