package com.camscaner.app.scanner

import androidx.annotation.StringRes
import com.camscaner.app.R

enum class ScanFilter(@StringRes val label: Int, val key: String) {
    ORIGINAL(R.string.filter_original, "original"),
    LIGHTEN(R.string.filter_lighten, "lighten"),
    /** Document look: white paper without shadows, dark crisp text, colours kept. */
    ENHANCE(R.string.filter_enhance, "enhance"),
    MAGIC(R.string.filter_magic, "magic"),
    NO_SHADOW(R.string.filter_no_shadow, "no_shadow"),
    GRAY(R.string.filter_gray, "gray"),
    BW(R.string.filter_bw, "bw"),
    BW_SOFT(R.string.filter_bw_soft, "bw_soft");

    companion object {
        fun fromKey(key: String?): ScanFilter = entries.firstOrNull { it.key == key } ?: MAGIC
    }
}

/**
 * User fine-tuning applied after the filter. Brightness and contrast are in [-1, 1], sharpness in
 * [0, 1]; [strength] blends the filter with the unfiltered page (1 = full filter).
 */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val sharpness: Float = 0f,
    val strength: Float = 1f,
) {
    /** True when brightness, contrast and sharpness leave the filter output unchanged. */
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f && sharpness == 0f
}
