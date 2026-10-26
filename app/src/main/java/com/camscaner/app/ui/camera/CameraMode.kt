package com.camscaner.app.ui.camera

import androidx.annotation.StringRes
import com.camscaner.app.R

/** What the camera is pointed at; decides detection, cropping and how many shots make a page. */
enum class CameraMode(@StringRes val label: Int) {
    /** Sheets of paper: the sheet is detected and cut out, one or several pages. */
    DOCUMENT(R.string.camera_mode_document),
    /** Front and back of an ID card: two shots cut to the card format, printed together. */
    ID_CARD(R.string.camera_mode_id_card),
    /** An open book: every shot of a spread becomes two pages. */
    BOOK(R.string.camera_mode_book),
    /** QR codes and barcodes are read from the preview. */
    QR(R.string.camera_mode_qr),
    /** Plain photos: nothing is cut or filtered. */
    PHOTO(R.string.camera_mode_photo);

    /** The sheet outline is looked for and shown on the preview. */
    val detects: Boolean get() = this == DOCUMENT || this == ID_CARD || this == BOOK

    /** Several shots are collected before the pages are edited. */
    val collects: Boolean get() = this == ID_CARD || this == BOOK
}
