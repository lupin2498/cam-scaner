package com.camscaner.app.ui

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStarted
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.camscaner.app.R
import com.camscaner.app.ui.camera.CameraScreen
import com.camscaner.app.ui.components.ProgressDialog
import com.camscaner.app.ui.crop.CropScreen
import com.camscaner.app.ui.document.DocumentScreen
import com.camscaner.app.ui.export.ExportScreen
import com.camscaner.app.ui.filter.FilterScreen
import com.camscaner.app.ui.home.HomeScreen
import com.camscaner.app.ui.home.TrashScreen
import com.camscaner.app.ui.ocr.OcrScreen
import com.camscaner.app.ui.page.PageViewerScreen
import com.camscaner.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val CROP = "crop"
    const val FILTER = "filter"
    const val DOCUMENT = "document/{docId}"
    const val PAGE = "page/{docId}/{index}"
    const val EXPORT = "export/{docId}"
    const val OCR = "ocr/{docId}/{pageId}"
    const val SETTINGS = "settings"
    const val TRASH = "trash"

    fun document(id: String) = "document/$id"
    fun page(id: String, index: Int) = "page/$id/$index"
    fun export(id: String) = "export/$id"
    fun ocr(id: String, pageId: String) = "ocr/$id/$pageId"
}

@Composable
fun AppNavigation(
    sharedUris: List<Uri>,
    onSharedConsumed: () -> Unit,
    startAction: String? = null,
    onStartActionConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val session: ScanSessionViewModel = viewModel()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // "import" from a launcher shortcut opens the picker on the home screen
    var importRequested by remember { mutableStateOf(false) }

    LaunchedEffect(startAction) {
        when (startAction) {
            "scan" -> {
                onStartActionConsumed()
                session.start(null)
                nav.navigate(Routes.CAMERA) { launchSingleTop = true }
            }
            "import" -> {
                onStartActionConsumed()
                importRequested = true
            }
        }
    }

    fun importAndCrop(docId: String?, returnRoute: String?, uris: List<Uri>) {
        session.start(docId, returnRoute)
        session.importFiles(uris)
    }

    LaunchedEffect(session.cropReady) {
        // the import can outlive the screen (or the activity); only navigate while it is visible
        if (session.cropReady) {
            lifecycle.withStarted {
                if (session.consumeCropReady()) nav.navigate(Routes.CROP) { launchSingleTop = true }
            }
        }
    }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            val uris = sharedUris
            onSharedConsumed()
            importAndCrop(null, null, uris)
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = {
                    session.start(null)
                    nav.navigate(Routes.CAMERA)
                },
                onImport = { uris -> importAndCrop(null, null, uris) },
                onOpenDocument = { nav.navigate(Routes.document(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onTrash = { nav.navigate(Routes.TRASH) },
                openImport = importRequested,
                onImportOpened = { importRequested = false },
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                session = session,
                onClose = {
                    session.abandon()
                    nav.popBackStack()
                },
                onDone = { nav.navigate(Routes.CROP) { launchSingleTop = true } },
            )
        }
        composable(Routes.CROP) {
            CropScreen(
                session = session,
                onBack = { nav.popBackStack() },
                onNext = { nav.navigate(Routes.FILTER) { launchSingleTop = true } },
            )
        }
        composable(Routes.FILTER) {
            FilterScreen(
                session = session,
                onBack = { nav.popBackStack() },
                onSaved = { docId -> finishScan(nav, session, docId) },
            )
        }
        composable(Routes.DOCUMENT, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            DocumentScreen(
                onBack = { nav.popBackStack() },
                onAddCamera = { docId ->
                    session.start(docId, Routes.DOCUMENT)
                    nav.navigate(Routes.CAMERA)
                },
                onAddImages = { docId, uris -> importAndCrop(docId, Routes.DOCUMENT, uris) },
                onOpenPage = { docId, index -> nav.navigate(Routes.page(docId, index)) },
                onExport = { docId -> nav.navigate(Routes.export(docId)) },
            )
        }
        composable(
            Routes.PAGE,
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) {
            PageViewerScreen(
                onBack = { nav.popBackStack() },
                onRecrop = { docId, pageId ->
                    session.editExisting(docId, pageId, Routes.PAGE)
                    nav.navigate(Routes.CROP)
                },
                onFilter = { docId, pageId ->
                    session.editExisting(docId, pageId, Routes.PAGE)
                    nav.navigate(Routes.FILTER)
                },
                onOcr = { docId, pageId -> nav.navigate(Routes.ocr(docId, pageId)) },
            )
        }
        composable(Routes.EXPORT, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            ExportScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.OCR,
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("pageId") { type = NavType.StringType },
            ),
        ) {
            OcrScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    if (session.importing) ProgressDialog(stringResource(R.string.processing))
}

/** After saving: go back to where the scan started, or open the new document. */
private fun finishScan(nav: NavHostController, session: ScanSessionViewModel, docId: String) {
    val back = session.returnRoute
    Log.d("AppNavigation", "Scan saved into $docId, returning to ${back ?: "new document"}")
    if (back == null || !nav.popBackStack(back, inclusive = false)) {
        nav.navigate(Routes.document(docId)) {
            popUpTo(Routes.HOME)
        }
    }
}
