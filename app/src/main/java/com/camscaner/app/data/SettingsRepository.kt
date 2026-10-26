package com.camscaner.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.camscaner.app.pdf.PageMargin
import com.camscaner.app.pdf.PageOrientation
import com.camscaner.app.pdf.PaperSize
import com.camscaner.app.pdf.PdfOptions
import com.camscaner.app.pdf.PdfQuality
import com.camscaner.app.scanner.PaperPreset
import com.camscaner.app.scanner.ScanFilter

/** User preferences backed by SharedPreferences and exposed as Compose state. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var _defaultFilter by mutableStateOf(ScanFilter.fromKey(prefs.getString(KEY_FILTER, ScanFilter.ENHANCE.key)))
    var defaultFilter: ScanFilter
        get() = _defaultFilter
        set(value) {
            _defaultFilter = value
            prefs.edit { putString(KEY_FILTER, value.key) }
        }

    private var _autoCapture by mutableStateOf(prefs.getBoolean(KEY_AUTO_CAPTURE, true))
    var autoCapture: Boolean
        get() = _autoCapture
        set(value) {
            _autoCapture = value
            prefs.edit { putBoolean(KEY_AUTO_CAPTURE, value) }
        }

    private var _showGrid by mutableStateOf(prefs.getBoolean(KEY_GRID, false))
    var showGrid: Boolean
        get() = _showGrid
        set(value) {
            _showGrid = value
            prefs.edit { putBoolean(KEY_GRID, value) }
        }

    private var _autoCrop by mutableStateOf(prefs.getBoolean(KEY_AUTO_CROP, true))
    var autoCrop: Boolean
        get() = _autoCrop
        set(value) {
            _autoCrop = value
            prefs.edit { putBoolean(KEY_AUTO_CROP, value) }
        }

    private var _batchMode by mutableStateOf(prefs.getBoolean(KEY_BATCH, false))
    var batchMode: Boolean
        get() = _batchMode
        set(value) {
            _batchMode = value
            prefs.edit { putBoolean(KEY_BATCH, value) }
        }

    private var _autoAlign by mutableStateOf(prefs.getBoolean(KEY_AUTO_ALIGN, true))
    /** Turn new pages upright automatically, judged by their text. */
    var autoAlign: Boolean
        get() = _autoAlign
        set(value) {
            _autoAlign = value
            prefs.edit { putBoolean(KEY_AUTO_ALIGN, value) }
        }

    private var _cameraPaper by mutableStateOf(PaperPreset.fromName(prefs.getString(KEY_CAMERA_PAPER, null)))
    /** Paper format new photos are cut to (a guide frame is shown in the camera); null detects the sheet freely. */
    var cameraPaper: PaperPreset?
        get() = _cameraPaper
        set(value) {
            _cameraPaper = value
            prefs.edit { putString(KEY_CAMERA_PAPER, value?.name) }
        }

    private var _autoStraighten by mutableStateOf(prefs.getBoolean(KEY_AUTO_STRAIGHTEN, true))
    /** Level the text lines of new pages automatically. */
    var autoStraighten: Boolean
        get() = _autoStraighten
        set(value) {
            _autoStraighten = value
            prefs.edit { putBoolean(KEY_AUTO_STRAIGHTEN, value) }
        }

    /** Last used PDF options (the password is never stored). */
    fun pdfOptions(fileName: String): PdfOptions = PdfOptions(
        fileName = fileName,
        paperSize = enumOr(prefs.getString(KEY_PDF_PAPER, null), PaperSize.AUTO),
        orientation = enumOr(prefs.getString(KEY_PDF_ORIENTATION, null), PageOrientation.AUTO),
        margin = enumOr(prefs.getString(KEY_PDF_MARGIN, null), PageMargin.NONE),
        quality = enumOr(prefs.getString(KEY_PDF_QUALITY, null), PdfQuality.HIGH),
        ocrLayer = prefs.getBoolean(KEY_PDF_OCR, false),
        mergeIdCards = prefs.getBoolean(KEY_PDF_ID, false),
        pageNumbers = prefs.getBoolean(KEY_PDF_NUMBERS, false),
        watermark = prefs.getString(KEY_PDF_WATERMARK, "").orEmpty(),
        pagesPerSheet = prefs.getInt(KEY_PDF_PER_SHEET, 1).let { if (it == 2 || it == 4) it else 1 },
    )

    fun savePdfOptions(o: PdfOptions) = prefs.edit {
        putString(KEY_PDF_PAPER, o.paperSize.name)
        putString(KEY_PDF_ORIENTATION, o.orientation.name)
        putString(KEY_PDF_MARGIN, o.margin.name)
        putString(KEY_PDF_QUALITY, o.quality.name)
        putBoolean(KEY_PDF_OCR, o.ocrLayer)
        putBoolean(KEY_PDF_ID, o.mergeIdCards)
        putBoolean(KEY_PDF_NUMBERS, o.pageNumbers)
        putString(KEY_PDF_WATERMARK, o.watermark)
        putInt(KEY_PDF_PER_SHEET, o.pagesPerSheet)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_FILTER = "default_filter"
        const val KEY_AUTO_CAPTURE = "auto_capture"
        const val KEY_GRID = "grid"
        const val KEY_AUTO_CROP = "auto_crop"
        const val KEY_BATCH = "batch_mode"
        const val KEY_PDF_PAPER = "pdf_paper"
        const val KEY_PDF_ORIENTATION = "pdf_orientation"
        const val KEY_PDF_MARGIN = "pdf_margin"
        const val KEY_PDF_QUALITY = "pdf_quality"
        const val KEY_PDF_OCR = "pdf_ocr"
        const val KEY_PDF_ID = "pdf_id_cards"
        const val KEY_AUTO_ALIGN = "auto_align"
        const val KEY_AUTO_STRAIGHTEN = "auto_straighten"
        const val KEY_PDF_NUMBERS = "pdf_page_numbers"
        const val KEY_PDF_WATERMARK = "pdf_watermark"
        const val KEY_CAMERA_PAPER = "camera_paper"
        const val KEY_PDF_PER_SHEET = "pdf_per_sheet"
    }
}
