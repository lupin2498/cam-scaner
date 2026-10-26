package com.camscaner.app.ui.camera

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camscaner.app.R
import com.camscaner.app.ui.Share
import com.google.mlkit.vision.barcode.common.Barcode

/** The content of a scanned QR code or barcode, with the actions that make sense for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarcodeDialog(code: Barcode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val raw = code.rawValue.orEmpty()
    val typeLabel = when (code.valueType) {
        Barcode.TYPE_URL -> R.string.qr_type_url
        Barcode.TYPE_WIFI -> R.string.qr_type_wifi
        Barcode.TYPE_CONTACT_INFO -> R.string.qr_type_contact
        Barcode.TYPE_PHONE -> R.string.qr_type_phone
        Barcode.TYPE_EMAIL -> R.string.qr_type_email
        Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> R.string.qr_type_product
        else -> R.string.qr_type_text
    }
    val detail = when (code.valueType) {
        Barcode.TYPE_WIFI -> code.wifi?.let { stringResource(R.string.qr_wifi_detail, it.ssid.orEmpty(), it.password.orEmpty()) } ?: raw
        else -> raw
    }
    val url = code.url?.url ?: raw.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    val phone = code.phone?.number
    val copied = stringResource(R.string.qr_copied)

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.export_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_result_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(typeLabel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(detail, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                when {
                    url != null -> TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(stringResource(R.string.qr_open)) }
                    phone != null -> TextButton(onClick = { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }) { Text(stringResource(R.string.qr_open)) }
                    else -> TextButton(onClick = {
                        open(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, raw))
                    }) { Text(stringResource(R.string.qr_search)) }
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QR", raw))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = { Share.shareText(context, raw) }) { Text(stringResource(R.string.share)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
