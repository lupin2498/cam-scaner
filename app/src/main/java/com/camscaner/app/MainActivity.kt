package com.camscaner.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.camscaner.app.ui.AppNavigation
import com.camscaner.app.ui.lock.AppLock
import com.camscaner.app.ui.lock.LockScreen
import com.camscaner.app.ui.theme.CamScanerTheme

class MainActivity : FragmentActivity() {

    /** Images and PDF files shared to the app from other apps, waiting to be imported. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    /** Launcher shortcut to carry out once the screen is up: "scan" or "import". */
    private val startAction = mutableStateOf<String?>(null)

    /** True while the device lock has to be passed before the documents are shown. */
    private val locked = mutableStateOf(false)
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        locked.value = scannerApp.settings.appLock
        setContent {
            CamScanerTheme(scannerApp.settings.theme) {
                Box {
                    AppNavigation(
                        sharedUris = sharedUris.value,
                        onSharedConsumed = { sharedUris.value = emptyList() },
                        startAction = startAction.value,
                        onStartActionConsumed = { startAction.value = null },
                    )
                    if (locked.value) LockScreen(onUnlock = ::unlock)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (locked.value) unlock()
    }

    override fun onStop() {
        super.onStop()
        // the lock comes back whenever the app leaves the screen
        if (scannerApp.settings.appLock) locked.value = true
    }

    private fun unlock() {
        if (prompting) return
        prompting = true
        AppLock.authenticate(this) { ok ->
            prompting = false
            if (ok) locked.value = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_SCAN -> startAction.value = "scan"
            ACTION_IMPORT -> startAction.value = "import"
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            // "open with": a PDF or image handed over by a file manager
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris.value = uris
    }

    companion object {
        const val ACTION_SCAN = "com.camscaner.app.action.SCAN"
        const val ACTION_IMPORT = "com.camscaner.app.action.IMPORT"
    }
}
