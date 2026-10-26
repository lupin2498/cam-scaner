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
import java.io.IOException
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

    fun openPdf(context: Context, file: File) = openFile(context, file)

    /** Opens [file] in another app, by its type. */
    fun openFile(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.export_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(context: Context, file: File) = shareFiles(context, listOf(file), mimeOf(file))

    /** Media type of an exported file, from its extension. */
    fun mimeOf(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Copies [file] over [target]; an existing, longer file there is truncated first. */
    fun copyToUri(context: Context, file: File, target: Uri) {
        val resolver = context.contentResolver
        // plain "w" does not truncate on many providers since Android 10, leaving the old tail behind
        val out = try {
            resolver.openOutputStream(target, "wt")
        } catch (e: Exception) {
            resolver.openOutputStream(target, "w")
        } ?: throw IOException("Cannot open $target")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** "CamScaner 25-10-2026 18.38" style default name, with the prefix chosen in the settings. */
    fun defaultDocumentName(context: Context): String {
        val date = SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        val prefix = (context.applicationContext as? com.camscaner.app.ScannerApp)?.settings?.namePrefix.orEmpty()
        return if (prefix.isEmpty()) context.getString(R.string.default_doc_name, date) else "$prefix $date"
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "scan" }.take(80)

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
