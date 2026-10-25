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
        put("p", JSONArray().apply { points.forEach { put(it.toDouble()) } })
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

/** Draws annotation and signature strokes onto finished pages. */
object MarkupRenderer {

    /** Highlighter ink is laid over the page like a marker: it darkens but never hides the text. */
    private const val HIGHLIGHTER_ALPHA = 110

    /**
     * Draws [marks] on [page] and returns the result: [page] itself when it is mutable, otherwise
     * a mutable copy (and [page] is recycled).
     */
    fun draw(page: Bitmap, marks: List<MarkStroke>): Bitmap {
        if (marks.isEmpty()) return page
        val target = if (page.isMutable) page else page.copy(Bitmap.Config.ARGB_8888, true).also { page.recycle() }
        val canvas = Canvas(target)
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        val unit = sqrt(w * h)
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
