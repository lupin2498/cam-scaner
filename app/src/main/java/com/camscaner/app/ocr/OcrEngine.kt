package com.camscaner.app.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** A recognised line of text; [box] is normalized to [0, 1] relative to the image. */
data class OcrLine(val text: String, val box: RectF)

data class OcrResult(val text: String, val lines: List<OcrLine>)

/** On-device text recognition (ML Kit, Latin script incl. Vietnamese). */
object OcrEngine {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, RectF(r.left / w, r.top / h, r.right / w, r.bottom / h))
            }
        }
        val fullText = text.textBlocks.joinToString("\n\n") { block -> block.lines.joinToString("\n") { it.text } }
        return OcrResult(fullText, lines)
    }
}
