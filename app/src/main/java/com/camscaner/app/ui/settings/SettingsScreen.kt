package com.camscaner.app.ui.settings

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camscaner.app.BuildConfig
import com.camscaner.app.R
import com.camscaner.app.ScannerApp
import com.camscaner.app.data.AppTheme
import com.camscaner.app.scanner.ScanFilter
import com.camscaner.app.scannerApp
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.components.SectionTitle
import com.camscaner.app.ui.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val scannerApp = app as ScannerApp
    private val repository = scannerApp.documents

    var working by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun backupTo(uri: Uri) = viewModelScope.launch {
        working = scannerApp.getString(R.string.settings_backup_progress, 0)
        try {
            withContext(Dispatchers.IO) {
                scannerApp.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    repository.writeBackup(out) { p -> working = scannerApp.getString(R.string.settings_backup_progress, (p * 100).toInt()) }
                } ?: error("Cannot open $uri")
            }
            message = scannerApp.getString(R.string.settings_backup_done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = scannerApp.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        } finally {
            working = null
        }
    }

    fun restoreFrom(uri: Uri) = viewModelScope.launch {
        working = scannerApp.getString(R.string.settings_restore_progress)
        try {
            val count = withContext(Dispatchers.IO) {
                scannerApp.contentResolver.openInputStream(uri)?.use { repository.readBackup(it) } ?: error("Cannot open $uri")
            }
            message = if (count < 0) scannerApp.getString(R.string.settings_restore_invalid)
            else scannerApp.getString(R.string.settings_restore_done, count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = scannerApp.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        } finally {
            working = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.scannerApp.settings
    val signatures = context.scannerApp.signatures
    val vm: SettingsViewModel = viewModel()
    val lockUnavailable = stringResource(R.string.settings_app_lock_unavailable)

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_default_filter))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.entries.forEach { f ->
                    FilterChip(
                        selected = settings.defaultFilter == f,
                        onClick = { settings.defaultFilter = f },
                        label = { Text(stringResource(f.label)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow(stringResource(R.string.settings_auto_crop), stringResource(R.string.settings_auto_crop_desc), settings.autoCrop) {
                settings.autoCrop = it
            }
            ToggleRow(stringResource(R.string.settings_auto_align), stringResource(R.string.settings_auto_align_desc), settings.autoAlign) {
                settings.autoAlign = it
            }
            ToggleRow(stringResource(R.string.settings_auto_straighten), stringResource(R.string.settings_auto_straighten_desc), settings.autoStraighten) {
                settings.autoStraighten = it
            }
            ToggleRow(stringResource(R.string.settings_auto_capture), null, settings.autoCapture) { settings.autoCapture = it }
            ToggleRow(stringResource(R.string.settings_grid), null, settings.showGrid) { settings.showGrid = it }

            SectionTitle(stringResource(R.string.settings_theme))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t ->
                    FilterChip(selected = settings.theme == t, onClick = { settings.theme = t }, label = { Text(stringResource(t.label)) })
                }
            }

            SectionTitle(stringResource(R.string.settings_name_prefix))
            var prefix by remember { mutableStateOf(settings.namePrefix) }
            OutlinedTextField(
                value = prefix,
                onValueChange = {
                    prefix = it
                    settings.namePrefix = it
                },
                placeholder = { Text(stringResource(R.string.settings_name_prefix_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.settings_security))
            ToggleRow(stringResource(R.string.settings_app_lock), stringResource(R.string.settings_app_lock_desc), settings.appLock) { on ->
                if (on && !AppLock.available(context)) {
                    Toast.makeText(context, lockUnavailable, Toast.LENGTH_LONG).show()
                } else {
                    settings.appLock = on
                }
            }

            SectionTitle(stringResource(R.string.settings_backup))
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    backupLauncher.launch("CamScaner-$stamp.zip")
                }) { Text(stringResource(R.string.settings_backup_create)) }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(stringResource(R.string.settings_backup_restore))
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_signatures, signatures.signatures.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { signatures.signatures.toList().forEach { signatures.remove(it) } },
                    enabled = signatures.signatures.isNotEmpty(),
                ) { Text(stringResource(R.string.settings_signatures_clear)) }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    vm.working?.let { ProgressDialog(it) }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
