package com.camscaner.app.data

import com.camscaner.app.scanner.Adjustments
import com.camscaner.app.scanner.Corners
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
    /** Size of the rendered page image in pixels. */
    val width: Int = 0,
    val height: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        corners?.let { c -> put("corners", JSONArray().apply { c.toFloatArray().forEach { put(it.toDouble()) } }) }
        put("rotation", rotation)
        put("filter", filter.key)
        put("brightness", adjustments.brightness.toDouble())
        put("contrast", adjustments.contrast.toDouble())
        put("sharpness", adjustments.sharpness.toDouble())
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
                ),
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
    val filter: ScanFilter = ScanFilter.MAGIC,
    val adjustments: Adjustments = Adjustments(),
    /** Set when re-editing a page that already belongs to a document. */
    val existingPageId: String? = null,
)
