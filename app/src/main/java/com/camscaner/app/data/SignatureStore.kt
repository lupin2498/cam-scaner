package com.camscaner.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A signature drawn on the pad: [strokes] are x, y pairs normalized to its bounding box,
 * [aspect] is that box's width / height and [width] the ink width relative to the box height.
 */
data class SavedSignature(val strokes: List<List<Float>>, val aspect: Float, val width: Float, val color: Int) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("s", JSONArray().apply {
            strokes.forEach { s -> put(JSONArray().apply { s.forEach { put(Math.round(it * 10_000.0) / 10_000.0) } }) }
        })
        put("a", aspect.toDouble())
        put("w", width.toDouble())
        put("c", color)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedSignature? {
            val arr = o.optJSONArray("s") ?: return null
            val strokes = List(arr.length()) { i ->
                val s = arr.getJSONArray(i)
                List(s.length()) { s.getDouble(it).toFloat() }
            }.filter { it.size >= 2 }
            if (strokes.isEmpty()) return null
            return SavedSignature(strokes, o.optDouble("a", 2.0).toFloat(), o.optDouble("w", 0.05).toFloat(), o.optInt("c"))
        }
    }
}

/** Signatures kept for reuse, newest first, in files/signatures.json. */
class SignatureStore(context: Context) {

    private val file = File(context.filesDir, "signatures.json")

    var signatures by mutableStateOf(load())
        private set

    /** Keeps [signature] for later (an identical one is moved to the front). */
    fun add(signature: SavedSignature) {
        signatures = (listOf(signature) + signatures.filterNot { it.strokes == signature.strokes }).take(MAX)
        persist()
    }

    fun remove(signature: SavedSignature) {
        signatures = signatures - signature
        persist()
    }

    private fun load(): List<SavedSignature> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) { arr.optJSONObject(it)?.let(SavedSignature::fromJson) }.filterNotNull()
    }.getOrDefault(emptyList())

    private fun persist() {
        runCatching { file.writeText(JSONArray().apply { signatures.forEach { put(it.toJson()) } }.toString()) }
    }

    private companion object {
        const val MAX = 6
    }
}
