package com.camscaner.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.camscaner.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Share {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun sharePdf(context: Context, file: File) = shareFiles(context, listOf(file), "application/pdf")

    fun shareFiles(context: Context, files: List<File>, mime: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.clipData = ClipData.newUri(context.contentResolver, files[0].name, uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun openPdf(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.export_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToUri(context: Context, file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** "Scan 2026-10-21 17.45" style default name. */
    fun defaultDocumentName(context: Context): String =
        context.getString(R.string.default_doc_name, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date()))

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "scan" }.take(80)

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
