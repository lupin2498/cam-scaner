package com.camscaner.app.data

import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.scanner.Corners
import com.camscaner.app.scanner.MarkStroke
import com.camscaner.app.scanner.PaperPreset
import com.camscaner.app.scanner.ScanFilter
import org.json.JSONArray
import org.json.JSONObject

data class ScanPage(
    val id: String,
    /** Normalized corners on the original photo; null keeps the whole photo. */
    val corners: Corners?,
    /** Clockwise rotation applied after perspective correction: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.MAGIC,
    val adjustments: Adjustments = Adjustments(),
    /** Clockwise straightening in degrees after perspective correction; null measures it automatically. */
    val skew: Float? = 0f,
    /** Annotations and signatures drawn on the finished page. */
    val marks: List<MarkStroke> = emptyList(),
    /** Paper format the page was cut to; the corrected page gets exactly its proportions. Null measures them. */
    val paper: PaperPreset? = null,
    /** Size of the rendered page image in pixels. */
    val width: Int = 0,
    val height: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        corners?.let { c -> put("corners", JSONArray().apply { c.toFloatArray().forEach { put(it.toDouble()) } }) }
        paper?.let { put("paper", it.name) }
        put("rotation", rotation)
        put("filter", filter.key)
        put("brightness", adjustments.brightness.toDouble())
        put("contrast", adjustments.contrast.toDouble())
        put("sharpness", adjustments.sharpness.toDouble())
        if (adjustments.strength != 1f) put("strength", adjustments.strength.toDouble())
        if (marks.isNotEmpty()) put("marks", MarkStroke.listToJson(marks))
        if (skew == null) put("skewAuto", true) else put("skew", skew.toDouble())
        put("width", width)
        put("height", height)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ScanPage {
            val corners = o.optJSONArray("corners")?.let { a ->
                if (a.length() == 8) Corners.fromFloatArray(FloatArray(8) { a.getDouble(it).toFloat() }) else null
            }
            return ScanPage(
                id = o.getString("id"),
                corners = corners,
                rotation = o.optInt("rotation", 0),
                filter = ScanFilter.fromKey(o.optString("filter")),
                adjustments = Adjustments(
                    brightness = o.optDouble("brightness", 0.0).toFloat(),
                    contrast = o.optDouble("contrast", 0.0).toFloat(),
                    sharpness = o.optDouble("sharpness", 0.0).toFloat(),
                    strength = o.optDouble("strength", 1.0).toFloat(),
                ),
                marks = MarkStroke.listFromJson(o.optJSONArray("marks")),
                paper = PaperPreset.fromName(o.optString("paper", null)),
                // pages saved before straightening existed keep their look
                skew = if (o.optBoolean("skewAuto", false)) null else o.optDouble("skew", 0.0).toFloat(),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }
}

data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ScanDocument {
            val arr = o.optJSONArray("pages") ?: JSONArray()
            return ScanDocument(
                id = o.getString("id"),
                name = o.optString("name"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                pages = List(arr.length()) { ScanPage.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

/** A page being scanned or edited, before it is saved into a document. */
data class DraftPage(
    val id: String,
    /** Upright original photo (no EXIF rotation). */
    val originalFile: java.io.File,
    val width: Int,
    val height: Int,
    /** Corners found by the detector, kept so the user can go back to them. */
    val detected: Corners?,
    val corners: Corners?,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.ENHANCE,
    val adjustments: Adjustments = Adjustments(),
    /** Clockwise straightening in degrees; null measures it automatically from the text lines. */
    val skew: Float? = null,
    /** Annotations and signatures drawn on the finished page. */
    val marks: List<MarkStroke> = emptyList(),
    /** Paper format the outline was fitted to; the page gets exactly its proportions. Null measures them. */
    val paper: PaperPreset? = null,
    /** Set when re-editing a page that already belongs to a document. */
    val existingPageId: String? = null,
)
