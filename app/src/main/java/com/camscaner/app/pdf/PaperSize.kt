package com.camscaner.app.pdf

import androidx.annotation.StringRes
import com.camscaner.app.R
import kotlin.math.abs
import kotlin.math.sqrt

/** Page sizes in PDF points (1/72 inch), portrait. */
enum class PaperSize(@StringRes val label: Int, val widthPt: Float, val heightPt: Float) {
    AUTO(R.string.paper_auto, 0f, 0f),
    FIT_IMAGE(R.string.paper_fit_image, 0f, 0f),
    A4(R.string.paper_a4, 595.28f, 841.89f),
    LETTER(R.string.paper_letter, 612f, 792f),
    LEGAL(R.string.paper_legal, 612f, 1008f),
    A3(R.string.paper_a3, 841.89f, 1190.55f),
    A5(R.string.paper_a5, 419.53f, 595.28f),
    B5(R.string.paper_b5, 498.9f, 708.66f);

    val isFixed: Boolean get() = widthPt > 0f

    companion object {
        /** Relative tolerance used to recognise a standard sheet from its aspect ratio. */
        const val SNAP_TOLERANCE = 0.035

        const val ID_CARD_WIDTH_PT = 242.65f // 85.6 mm (ISO/IEC 7810 ID-1)
        const val ID_CARD_HEIGHT_PT = 153.01f // 53.98 mm

        /** Returns the standard sheet whose ratio is closest to [ratio] (w/h, any orientation), if any. */
        fun match(ratio: Double): PaperKind? {
            if (ratio <= 0 || ratio.isNaN()) return null
            val shortOverLong = if (ratio <= 1) ratio else 1 / ratio
            return PaperKind.entries
                .map { it to abs(shortOverLong / it.ratio - 1) }
                .filter { it.second < SNAP_TOLERANCE }
                .minByOrNull { it.second }
                ?.first
        }

        /** Snaps a measured w/h ratio to the exact ratio of the matching standard sheet. */
        fun snapRatio(ratio: Double): Double {
            val kind = match(ratio) ?: return ratio
            return if (ratio <= 1) kind.ratio else 1 / kind.ratio
        }
    }
}

/** Standard sheets that can be recognised from their short/long side ratio. */
enum class PaperKind(val ratio: Double) {
    A_SERIES(1.0 / sqrt(2.0)),
    LETTER_SIZE(612.0 / 792.0),
    LEGAL_SIZE(612.0 / 1008.0),
    ID_CARD(153.01 / 242.65);

    /** Page size used for this kind in automatic mode; ID cards get their own layout. */
    val paper: PaperSize?
        get() = when (this) {
            A_SERIES -> PaperSize.A4
            LETTER_SIZE -> PaperSize.LETTER
            LEGAL_SIZE -> PaperSize.LEGAL
            ID_CARD -> null
        }
}

enum class PageOrientation(@StringRes val label: Int) {
    AUTO(R.string.orientation_auto),
    PORTRAIT(R.string.orientation_portrait),
    LANDSCAPE(R.string.orientation_landscape),
}

enum class PageMargin(@StringRes val label: Int, val points: Float) {
    NONE(R.string.margin_none, 0f),
    SMALL(R.string.margin_small, 18f),
    MEDIUM(R.string.margin_medium, 36f),
    LARGE(R.string.margin_large, 54f),
}

enum class PdfQuality(@StringRes val label: Int, val maxDim: Int, val jpegQuality: Int) {
    LOW(R.string.quality_low, 1400, 60),
    MEDIUM(R.string.quality_medium, 2000, 72),
    HIGH(R.string.quality_high, 2600, 84),
    ORIGINAL(R.string.quality_original, 3200, 93),
}

data class PdfOptions(
    val fileName: String,
    val paperSize: PaperSize = PaperSize.AUTO,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val margin: PageMargin = PageMargin.NONE,
    val quality: PdfQuality = PdfQuality.HIGH,
    val ocrLayer: Boolean = false,
    val mergeIdCards: Boolean = true,
    val pageNumbers: Boolean = false,
    val password: String = "",
    val watermark: String = "",
)
