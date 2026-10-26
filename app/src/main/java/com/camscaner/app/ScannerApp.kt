package com.camscaner.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.camscaner.app.data.DocumentRepository
import com.camscaner.app.data.SettingsRepository
import com.camscaner.app.data.SignatureStore
import com.camscaner.app.pdf.PdfExporter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class ScannerApp : Application() {

    lateinit var documents: DocumentRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var signatures: SignatureStore
        private set
    val pdfExporter: PdfExporter by lazy { PdfExporter(documents) }

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV native library failed to load")
        PDFBoxResourceLoader.init(applicationContext)
        documents = DocumentRepository(this)
        settings = SettingsRepository(this)
        signatures = SignatureStore(this)
    }

    private companion object {
        const val TAG = "ScannerApp"
    }
}

val Context.scannerApp: ScannerApp get() = applicationContext as ScannerApp
