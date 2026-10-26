package com.camscaner.app.ui.camera

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Reads QR codes and barcodes from preview frames (ML Kit); the same code is reported once. */
class BarcodeAnalyzer(private val onResult: (Barcode) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )
    private var lastValue: String? = null
    private var lastTime = 0L

    /** While false, frames are dropped (a result is being shown). */
    @Volatile
    var enabled = true

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null || !enabled) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() } ?: return@addOnSuccessListener
                val now = SystemClock.elapsedRealtime()
                if (code.rawValue == lastValue && now - lastTime < REPEAT_MS) return@addOnSuccessListener
                lastValue = code.rawValue
                lastTime = now
                onResult(code)
            }
            // the frame must stay open until ML Kit has read it
            .addOnCompleteListener { image.close() }
    }

    fun close() = scanner.close()

    private companion object {
        const val REPEAT_MS = 3000L
    }
}

/** Routes preview frames to the analyzer of the current [mode]. */
class ModeAnalyzer(
    private val mode: () -> CameraMode,
    onDetection: (FrameDetection) -> Unit,
    onBarcode: (Barcode) -> Unit,
) : ImageAnalysis.Analyzer {

    private val document = DocumentAnalyzer(onDetection)
    val barcodes = BarcodeAnalyzer(onBarcode)

    override fun analyze(image: ImageProxy) {
        when (mode()) {
            CameraMode.QR -> barcodes.analyze(image)
            CameraMode.PHOTO -> image.close()
            else -> document.analyze(image)
        }
    }

    fun close() = barcodes.close()
}
