package com.camscaner.app.scanner

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * A pen, highlighter or signature stroke drawn on the finished page. [points] are x, y pairs
 * normalized to the page; [width] is relative to sqrt(page width × height), so it survives
 * quarter turns. Strokes placed together (a signature) share a [group] above 0.
 */
data class MarkStroke(
    val points: List<Float>,
    val color: Int,
    val width: Float,
    val highlighter: Boolean = false,
    val group: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        // a ten-thousandth of the page is a third of a pixel on the largest page; more digits only bloat the file
        put("p", JSONArray().apply { points.forEach { put(Math.round(it * 10_000.0) / 10_000.0) } })
        put("c", color)
        put("w", width.toDouble())
        if (highlighter) put("h", true)
        if (group != 0) put("g", group)
    }

    /** The stroke on the page turned clockwise by [degrees] (a multiple of 90). */
    fun rotated(degrees: Int): MarkStroke {
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) return this
        val out = ArrayList<Float>(points.size)
        for (i in 0 until points.size / 2) {
            val x = points[2 * i]
            val y = points[2 * i + 1]
            when (turn) {
                90 -> { out += 1f - y; out += x }
                180 -> { out += 1f - x; out += 1f - y }
                else -> { out += y; out += 1f - x }
            }
        }
        return copy(points = out)
    }

    companion object {
        fun fromJson(o: JSONObject): MarkStroke? {
            val p = o.optJSONArray("p") ?: return null
            return MarkStroke(
                points = List(p.length()) { p.getDouble(it).toFloat() },
                color = o.optInt("c"),
                width = o.optDouble("w", 0.004).toFloat(),
                highlighter = o.optBoolean("h", false),
                group = o.optInt("g", 0),
            )
        }

        /** [marks] without the last thing drawn: its last stroke, or the whole group (signature) it belongs to. */
        fun undo(marks: List<MarkStroke>): List<MarkStroke> {
            val last = marks.lastOrNull() ?: return marks
            return if (last.group == 0) marks.dropLast(1) else marks.dropLastWhile { it.group == last.group }
        }

        /** A group number not used by any of [marks]. */
        fun nextGroup(marks: List<MarkStroke>): Int = (marks.maxOfOrNull { it.group } ?: 0) + 1

        fun listToJson(marks: List<MarkStroke>) = JSONArray().apply { marks.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<MarkStroke> =
            if (a == null) emptyList() else List(a.length()) { a.optJSONObject(it)?.let(::fromJson) }.filterNotNull()
    }
}

/**
 * A text box typed onto the finished page. ([cx], [cy]) is its centre normalized to the page,
 * [size] the font size relative to sqrt(page width × height), [angle] a clockwise multiple of 90°
 * so the text turns with the page.
 */
data class TextMark(
    val text: String,
    val cx: Float,
    val cy: Float,
    val size: Float = DEFAULT_SIZE,
    val color: Int = 0xFF111111.toInt(),
    val angle: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", text)
        put("x", Math.round(cx * 10_000.0) / 10_000.0)
        put("y", Math.round(cy * 10_000.0) / 10_000.0)
        put("s", size.toDouble())
        put("c", color)
        if (angle != 0) put("a", angle)
    }

    /** The text on the page turned clockwise by [degrees] (a multiple of 90). */
    fun rotated(degrees: Int): TextMark {
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) return this
        val (x, y) = when (turn) {
            90 -> 1f - cy to cx
            180 -> 1f - cx to 1f - cy
            else -> cy to 1f - cx
        }
        return copy(cx = x, cy = y, angle = (angle + turn) % 360)
    }

    /** Lines of the text as drawn. */
    val lines: List<String> get() = text.split('\n')

    /**
     * Normalized width and height of the text's box on a page of [pageW] × [pageH] pixels (in the
     * page's own axes, before [angle]), measured with [measure] (text, font size px) → width px.
     */
    fun box(pageW: Float, pageH: Float, measure: (String, Float) -> Float): Pair<Float, Float> {
        val px = size * sqrt(pageW * pageH)
        val widest = lines.maxOf { measure(it, px) }
        val height = px * LINE_HEIGHT * lines.size
        return if (angle % 180 == 0) widest / pageW to height / pageH else height / pageW to widest / pageH
    }

    companion object {
        const val DEFAULT_SIZE = 0.03f
        const val LINE_HEIGHT = 1.25f

        fun fromJson(o: JSONObject): TextMark? {
            val text = o.optString("t")
            if (text.isEmpty()) return null
            return TextMark(
                text = text,
                cx = o.optDouble("x", 0.5).toFloat(),
                cy = o.optDouble("y", 0.5).toFloat(),
                size = o.optDouble("s", DEFAULT_SIZE.toDouble()).toFloat(),
                color = o.optInt("c", 0xFF111111.toInt()),
                angle = o.optInt("a", 0),
            )
        }

        fun listToJson(texts: List<TextMark>) = JSONArray().apply { texts.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<TextMark> =
            if (a == null) emptyList() else List(a.length()) { a.optJSONObject(it)?.let(::fromJson) }.filterNotNull()
    }
}

/** Draws annotation and signature strokes and text boxes onto finished pages. */
object MarkupRenderer {

    /** Highlighter ink is laid over the page like a marker: it darkens but never hides the text. */
    private const val HIGHLIGHTER_ALPHA = 110

    /** Paint used to draw and measure text marks at font size [px]. */
    fun textPaint(px: Float, color: Int = 0): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = px
        this.color = color
        typeface = android.graphics.Typeface.SANS_SERIF
    }

    /**
     * Draws [marks] and [texts] on [page] and returns the result: [page] itself when it is
     * mutable, otherwise a mutable copy (and [page] is recycled).
     */
    fun draw(page: Bitmap, marks: List<MarkStroke>, texts: List<TextMark> = emptyList()): Bitmap {
        if (marks.isEmpty() && texts.isEmpty()) return page
        val target = if (page.isMutable) page else page.copy(Bitmap.Config.ARGB_8888, true).also { page.recycle() }
        val canvas = Canvas(target)
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        val unit = sqrt(w * h)
        for (text in texts) drawText(canvas, text, w, h, unit)
        for (mark in marks) {
            if (mark.points.size < 2) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (mark.width * unit).coerceAtLeast(1f)
                color = mark.color
                if (mark.highlighter) {
                    alpha = HIGHLIGHTER_ALPHA
                    strokeCap = Paint.Cap.SQUARE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.MULTIPLY
                    else xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                }
            }
            canvas.drawPath(path(mark.points, w, h), paint)
        }
        return target
    }

    /** Draws one text box centred at its position, turned by its angle. */
    private fun drawText(canvas: Canvas, text: TextMark, w: Float, h: Float, unit: Float) {
        val px = text.size * unit
        val paint = textPaint(px, text.color)
        val lines = text.lines
        val lineHeight = px * TextMark.LINE_HEIGHT
        val total = lineHeight * lines.size
        canvas.save()
        canvas.translate(text.cx * w, text.cy * h)
        canvas.rotate(text.angle.toFloat())
        // baseline of the first line so the block is centred on the anchor
        var y = -total / 2f + px * 0.95f
        for (line in lines) {
            canvas.drawText(line, -paint.measureText(line) / 2f, y, paint)
            y += lineHeight
        }
        canvas.restore()
    }

    /** A smooth path through normalized points, scaled to [w] × [h]. */
    fun path(points: List<Float>, w: Float, h: Float): Path {
        val path = Path()
        val n = points.size / 2
        if (n == 0) return path
        var px = points[0] * w
        var py = points[1] * h
        path.moveTo(px, py)
        if (n == 1) {
            // a single tap leaves a dot
            path.lineTo(px + 0.1f, py)
            return path
        }
        for (i in 1 until n) {
            val x = points[2 * i] * w
            val y = points[2 * i + 1] * h
            // quadratic segments through the midpoints smooth the finger's polyline
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            px = x
            py = y
        }
        path.lineTo(px, py)
        return path
    }
}
