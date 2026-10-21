package com.camscaner.app.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camscaner.app.BuildConfig
import com.camscaner.app.R
import com.camscaner.app.scanner.ScanFilter
import com.camscaner.app.scannerApp
import com.camscaner.app.ui.components.BackButton
import com.camscaner.app.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalContext.current.scannerApp.settings
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
            ToggleRow(stringResource(R.string.settings_auto_capture), null, settings.autoCapture) { settings.autoCapture = it }
            ToggleRow(stringResource(R.string.settings_grid), null, settings.showGrid) { settings.showGrid = it }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
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
