package it.unipg.agriapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import it.unipg.agriapp.ui.MainUiState
import it.unipg.agriapp.ui.MainViewModel
import it.unipg.agriapp.ui.theme.AgriAppTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.isSystemInDarkTheme
import it.unipg.agriapp.data.ImageMetadata
import kotlin.math.abs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.core.view.WindowCompat

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgriAppTheme {
                val view = LocalView.current
                val isDark = isSystemInDarkTheme()
                val systemBarsColor = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    window.statusBarColor = systemBarsColor
                    window.navigationBarColor = systemBarsColor
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
                }
                val compactShape = RoundedCornerShape(12.dp)
                val compactBtnPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                val vm: MainViewModel = viewModel()
                var ui by remember { mutableStateOf(vm.state) }
                var discoveryStarted by remember { mutableStateOf(false) }
                var autoBootstrapDone by remember { mutableStateOf(false) }
                var showSystemDialog by remember { mutableStateOf(false) }
                var showShotDialog by remember { mutableStateOf(false) }
                var showAutoStartDialog by remember { mutableStateOf(false) }
                var pendingDelete by remember { mutableStateOf<String?>(null) }
                var showDeleteAllConfirm by remember { mutableStateOf(false) }
                var logEntries by remember { mutableStateOf(listOf<String>()) }
                var galleryRefreshing by remember { mutableStateOf(false) }
                var autoDiscoverInFlight by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (!discoveryStarted) {
                        discoveryStarted = true
                        vm.discoverLan { ui = it }
                    }
                }
                LaunchedEffect(ui.discoveredRpiBaseUrls) {
                    if (!autoBootstrapDone && ui.discoveredRpiBaseUrls.isNotEmpty()) {
                        autoBootstrapDone = true
                        vm.loadSystem { ui = it }
                        vm.loadNetworkMode { ui = it }
                        vm.loadImages { ui = it }
                        vm.loadAutoCaptureStatus { ui = it }
                    }
                }
                LaunchedEffect(ui.log) {
                    val msg = ui.log.trim()
                    if (msg.isNotEmpty()) {
                        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        val line = "[$ts] $msg"
                        if (logEntries.lastOrNull() != line) {
                            logEntries = (logEntries + line).takeLast(600)
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000)
                        vm.refreshSystemAndNetworkSilently { ui = it }
                    }
                }
                LaunchedEffect(ui.autoCaptureRpiRunning, ui.autoCaptureEspRunning) {
                    while (ui.autoCaptureRpiRunning == true || ui.autoCaptureEspRunning == true) {
                        delay(12_000)
                        vm.refreshGallerySilently { ui = it }
                        vm.loadAutoCaptureStatusSilently { ui = it }
                    }
                }
                LaunchedEffect(ui.autoDiscoverToken) {
                    if (ui.autoDiscoverNeeded && !autoDiscoverInFlight) {
                        autoDiscoverInFlight = true
                        delay(7000)
                        vm.consumeAutoDiscoverRequest()
                        ui = vm.state
                        vm.discoverLan {
                            ui = it
                            if (!it.busy) {
                                autoDiscoverInFlight = false
                            }
                        }
                    }
                }

                val isPhoneLayout = LocalConfiguration.current.screenWidthDp < 700

                if (isPhoneLayout) {
                    PhoneMainScaffold(
                        ui = ui,
                        compactShape = compactShape,
                        compactBtnPadding = compactBtnPadding,
                        logEntries = logEntries,
                        onDiscover = { vm.discoverLan { ui = it } },
                        onSetWifi = { vm.setNetworkMode("wifi_only") { ui = it } },
                        onSetAp = { vm.setNetworkMode("ap_only") { ui = it } },
                        onSelectRpiHost = { host ->
                            vm.updateBaseUrl(host)
                            ui = vm.state
                        },
                        onSelectEspHost = { host ->
                            vm.selectEspHost(host)
                            ui = vm.state
                        },
                        onSetCaptureSource = { source ->
                            vm.setAutoCaptureSource(source)
                            ui = vm.state
                        },
                        onSetCaptureInterval = { seconds ->
                            vm.setAutoCaptureInterval(seconds)
                            ui = vm.state
                        },
                        onShot = { source ->
                            when (source) {
                                "esp" -> vm.oneShotEsp { ui = it }
                                "both" -> vm.oneShotRpi {
                                    ui = it
                                    vm.oneShotEsp { ui = it }
                                }
                                else -> vm.oneShotRpi { ui = it }
                            }
                        },
                        onStartAuto = { source, interval ->
                            vm.setAutoCaptureSource(source)
                            vm.setAutoCaptureInterval(interval)
                            vm.startAutoCapture({ ui = it }, source = source, intervalSeconds = interval)
                        },
                        onStopAuto = { source ->
                            vm.stopAutoCapture({ ui = it }, source)
                        },
                        onRestartServer = { vm.restartServer { ui = it } },
                        onRebootSystem = { vm.rebootRpi { ui = it } },
                        onPoweroffSystem = { vm.poweroffRpi { ui = it } },
                        onGalleryRefresh = { vm.refreshGalleryOnly { ui = it } },
                        onGalleryPrev = { vm.prevImagesPage { ui = it } },
                        onGalleryNext = { vm.nextImagesPage { ui = it } },
                        onGalleryImageClick = {
                            val selectedIndex = ui.images.indexOfFirst { item -> item.filename == it }.coerceAtLeast(0)
                            val filenames = ui.images.map { item -> item.filename }.toTypedArray()
                            val latitudes = DoubleArray(ui.images.size) { idx -> ui.images[idx].metadata?.latitude ?: Double.NaN }
                            val longitudes = DoubleArray(ui.images.size) { idx -> ui.images[idx].metadata?.longitude ?: Double.NaN }
                            val temperatures = DoubleArray(ui.images.size) { idx -> ui.images[idx].metadata?.temperature ?: Double.NaN }
                            val humidities = DoubleArray(ui.images.size) { idx -> ui.images[idx].metadata?.humidity ?: Double.NaN }
                            val pressures = DoubleArray(ui.images.size) { idx -> ui.images[idx].metadata?.pressure ?: Double.NaN }
                            val selected = ui.images.getOrNull(selectedIndex)
                            val intent = Intent(this@MainActivity, ImageViewerActivity::class.java)
                            intent.putExtra(ImageViewerActivity.EXTRA_BASE_URL, ui.baseUrl)
                            intent.putExtra(ImageViewerActivity.EXTRA_FILENAME, it)
                            intent.putExtra(ImageViewerActivity.EXTRA_LATITUDE, selected?.metadata?.latitude)
                            intent.putExtra(ImageViewerActivity.EXTRA_LONGITUDE, selected?.metadata?.longitude)
                            intent.putExtra(ImageViewerActivity.EXTRA_TEMPERATURE, selected?.metadata?.temperature)
                            intent.putExtra(ImageViewerActivity.EXTRA_HUMIDITY, selected?.metadata?.humidity)
                            intent.putExtra(ImageViewerActivity.EXTRA_PRESSURE, selected?.metadata?.pressure)
                            intent.putExtra(ImageViewerActivity.EXTRA_FILENAMES, filenames)
                            intent.putExtra(ImageViewerActivity.EXTRA_INDEX, selectedIndex)
                            intent.putExtra(ImageViewerActivity.EXTRA_LATITUDES, latitudes)
                            intent.putExtra(ImageViewerActivity.EXTRA_LONGITUDES, longitudes)
                            intent.putExtra(ImageViewerActivity.EXTRA_TEMPERATURES, temperatures)
                            intent.putExtra(ImageViewerActivity.EXTRA_HUMIDITIES, humidities)
                            intent.putExtra(ImageViewerActivity.EXTRA_PRESSURES, pressures)
                            startActivity(intent)
                        },
                        onGalleryDeleteClick = { pendingDelete = it }
                    )
                } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.66f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1.0f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        shape = compactShape,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Connections", fontWeight = FontWeight.SemiBold)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                FilledTonalButton(onClick = { vm.discoverLan { ui = it } }, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) {
                                                    Text("Discover")
                                                }
                                                FilledTonalButton(onClick = { vm.setNetworkMode("wifi_only") { ui = it } }, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) {
                                                    Text("WiFi")
                                                }
                                                FilledTonalButton(onClick = { vm.setNetworkMode("ap_only") { ui = it } }, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) {
                                                    Text("AP")
                                                }
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.horizontalScroll(rememberScrollState())
                                            ) {
                                                Text("RPi", fontWeight = FontWeight.SemiBold)
                                                if (ui.discoveredRpiBaseUrls.isNotEmpty()) {
                                                    ui.discoveredRpiBaseUrls.take(2).forEach { host ->
                                                        HostChip(
                                                            label = compactHostLabel(host),
                                                            onClick = {
                                                                vm.updateBaseUrl(host)
                                                                ui = vm.state
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    Text("n/a", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Text("ESP", fontWeight = FontWeight.SemiBold)
                                                if (ui.discoveredEspBaseUrls.isNotEmpty()) {
                                                    ui.discoveredEspBaseUrls.take(2).forEach { host ->
                                                        HostChip(
                                                            label = compactHostLabel(host),
                                                            onClick = {
                                                                vm.selectEspHost(host)
                                                                ui = vm.state
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    Text("n/a", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }

                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        shape = compactShape,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 10.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Operations", fontWeight = FontWeight.SemiBold)
                                            val anyAutoRunning = (ui.autoCaptureRpiRunning == true) || (ui.autoCaptureEspRunning == true)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ElevatedButton(
                                                    onClick = { showShotDialog = true },
                                                    enabled = !ui.busy,
                                                    shape = compactShape,
                                                    contentPadding = compactBtnPadding
                                                ) {
                                                    Text("Shot")
                                                }
                                                FilledTonalButton(
                                                    onClick = { showAutoStartDialog = true },
                                                    enabled = !ui.busy && !anyAutoRunning,
                                                    shape = compactShape,
                                                    contentPadding = compactBtnPadding
                                                ) {
                                                    Text("Start Auto")
                                                }
                                                FilledTonalButton(
                                                    onClick = { vm.stopAutoCapture({ ui = it }, "both") },
                                                    enabled = !ui.busy && anyAutoRunning,
                                                    shape = compactShape,
                                                    contentPadding = compactBtnPadding
                                                ) {
                                                    Text("Stop Auto")
                                                }
                                                Spacer(modifier = Modifier.weight(1f))
                                                FilledTonalButton(
                                                    onClick = { showSystemDialog = true },
                                                    enabled = !ui.busy,
                                                    shape = compactShape,
                                                    contentPadding = compactBtnPadding
                                                ) {
                                                    Text("System")
                                                }
                                            }
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = compactShape,
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (ui.autoCaptureRpiRunning == true || ui.autoCaptureEspRunning == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                            ) {
                                                Text(
                                                    text = "Auto RPi: ${
                                                        if (ui.autoCaptureRpiRunning == true) {
                                                            "running${ui.autoCaptureRpiIntervalSeconds?.let { " (${it}s)" } ?: ""}"
                                                        } else "stopped"
                                                    } | Auto ESP: ${
                                                        if (ui.autoCaptureEspRunning == true) {
                                                            "running${ui.autoCaptureEspIntervalSeconds?.let { " (${it}s)" } ?: ""}"
                                                        } else "stopped"
                                                    }",
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(0.82f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    InfoPanel(
                                        ui = ui,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        shape = compactShape,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text("Gallery", fontWeight = FontWeight.SemiBold)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Items: ${ui.imagesTotalItems}  |  Page: ${ui.imagesPage}/${ui.imagesTotalPages}")
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                FilledTonalButton(
                                                    onClick = {
                                                        val intent = Intent(this@MainActivity, GalleryActivity::class.java)
                                                        intent.putExtra(GalleryActivity.EXTRA_BASE_URL, ui.baseUrl)
                                                        startActivity(intent)
                                                    },
                                                    enabled = !ui.busy,
                                                    shape = compactShape,
                                                    contentPadding = compactBtnPadding
                                                ) {
                                                    Text("Open")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            BottomLogPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.34f),
                                entries = logEntries
                            )
                        }
                        if (ui.busy) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                }

                val viewerModel: Any? = ui.viewerImageBytes ?: ui.viewerImageUrl
                viewerModel?.let { model ->
                    val currentIndex = ui.images.indexOfFirst { it.filename == ui.selectedImageFilename }
                    val selectedItem = if (currentIndex >= 0) ui.images[currentIndex] else null
                    val canNavigate = ui.viewerImageBytes == null && currentIndex >= 0
                    val canPrev = canNavigate && currentIndex > 0
                    val canNext = canNavigate && currentIndex < (ui.images.size - 1)
                    ZoomableImageDialog(
                        imageModel = model,
                        title = ui.viewerTitle ?: "Image",
                        metadata = selectedItem?.metadata,
                        canPrev = canPrev,
                        canNext = canNext,
                        onPrev = {
                            if (!canPrev) return@ZoomableImageDialog
                            vm.openAdjacentImage(-1)
                            ui = vm.state
                        },
                        onNext = {
                            if (!canNext) return@ZoomableImageDialog
                            vm.openAdjacentImage(1)
                            ui = vm.state
                        },
                        onDismiss = {
                            vm.closeViewer()
                            ui = vm.state
                        }
                    )
                }
                if (showSystemDialog) {
                    SystemActionsDialog(
                        busy = ui.busy,
                        onRestartServer = { vm.restartServer { ui = it } },
                        onReboot = { vm.rebootRpi { ui = it } },
                        onPoweroff = { vm.poweroffRpi { ui = it } },
                        onDismiss = { showSystemDialog = false }
                    )
                }
                if (showShotDialog) {
                    ShotDialog(
                        busy = ui.busy,
                        onShotRpi = {
                            vm.oneShotRpi { ui = it }
                            showShotDialog = false
                        },
                        onShotEsp = {
                            vm.oneShotEsp { ui = it }
                            showShotDialog = false
                        },
                        onDismiss = { showShotDialog = false }
                    )
                }
                if (showAutoStartDialog) {
                    StartAutoDialog(
                        busy = ui.busy,
                        initialSource = ui.selectedAutoCaptureSource,
                        initialIntervalSeconds = ui.selectedAutoCaptureIntervalSeconds,
                        onStart = { source, selected ->
                            vm.setAutoCaptureSource(source)
                            vm.setAutoCaptureInterval(selected)
                            vm.startAutoCapture({ ui = it }, source = source, intervalSeconds = selected)
                            showAutoStartDialog = false
                        },
                        onDismiss = { showAutoStartDialog = false }
                    )
                }
                pendingDelete?.let { filename ->
                    AppConfirmDialog(
                        title = "Delete image?",
                        message = "Are you sure you want to delete $filename?",
                        confirmLabel = "Delete",
                        destructive = true,
                        busy = ui.busy,
                        onConfirm = {
                            vm.deleteImage(filename) { ui = it }
                            pendingDelete = null
                        },
                        onDismiss = { pendingDelete = null }
                    )
                }
                if (showDeleteAllConfirm) {
                    AppConfirmDialog(
                        title = "Delete all images?",
                        message = "Are you sure you want to delete all gallery images, labels and json files?",
                        confirmLabel = "Delete all",
                        destructive = true,
                        busy = ui.busy,
                        onConfirm = {
                            vm.deleteAllImages { ui = it }
                            showDeleteAllConfirm = false
                        },
                        onDismiss = { showDeleteAllConfirm = false }
                    )
                }
            }
        }
    }
}

private enum class PhoneTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Home("Home", Icons.Default.Home),
    Capture("Capture", Icons.Default.Settings),
    Gallery("Gallery", Icons.Default.Info),
    System("System", Icons.Default.Build),
    Log("Log", Icons.Default.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun PhoneMainScaffold(
    ui: MainUiState,
    compactShape: RoundedCornerShape,
    compactBtnPadding: PaddingValues,
    logEntries: List<String>,
    onDiscover: () -> Unit,
    onSetWifi: () -> Unit,
    onSetAp: () -> Unit,
    onSelectRpiHost: (String) -> Unit,
    onSelectEspHost: (String) -> Unit,
    onSetCaptureSource: (String) -> Unit,
    onSetCaptureInterval: (Int) -> Unit,
    onShot: (String) -> Unit,
    onStartAuto: (String, Int) -> Unit,
    onStopAuto: (String) -> Unit,
    onRestartServer: () -> Unit,
    onRebootSystem: () -> Unit,
    onPoweroffSystem: () -> Unit,
    onGalleryRefresh: () -> Unit,
    onGalleryPrev: () -> Unit,
    onGalleryNext: () -> Unit,
    onGalleryImageClick: (String) -> Unit,
    onGalleryDeleteClick: (String) -> Unit
) {
    var tab by remember { mutableStateOf(PhoneTab.Home) }
    var systemConfirmAction by remember { mutableStateOf<String?>(null) }
    val anyAutoRunning = (ui.autoCaptureRpiRunning == true) || (ui.autoCaptureEspRunning == true)
    val selectedSource = ui.selectedAutoCaptureSource
    val selectedInterval = ui.selectedAutoCaptureIntervalSeconds
    val selectedRunning = when (selectedSource) {
        "esp" -> ui.autoCaptureEspRunning == true
        "both" -> anyAutoRunning
        else -> ui.autoCaptureRpiRunning == true
    }
    val intervalOptions = listOf(30, 60, 120, 180, 300)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                PhoneTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) }
                    )
                }
            }
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(8.dp)
        ) {
            when (tab) {
                PhoneTab.Home -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = compactShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Connections", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(onClick = onDiscover, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) { Text("Discover") }
                                FilledTonalButton(onClick = onSetWifi, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) { Text("WiFi") }
                                FilledTonalButton(onClick = onSetAp, enabled = !ui.busy, shape = compactShape, contentPadding = compactBtnPadding) { Text("AP") }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                Text("RPi", fontWeight = FontWeight.SemiBold)
                                if (ui.discoveredRpiBaseUrls.isNotEmpty()) {
                                    ui.discoveredRpiBaseUrls.take(3).forEach { host ->
                                        HostChip(label = compactHostLabel(host), onClick = { onSelectRpiHost(host) })
                                    }
                                } else {
                                    Text("n/a", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                Text("ESP", fontWeight = FontWeight.SemiBold)
                                if (ui.discoveredEspBaseUrls.isNotEmpty()) {
                                    ui.discoveredEspBaseUrls.take(3).forEach { host ->
                                        HostChip(label = compactHostLabel(host), onClick = { onSelectEspHost(host) })
                                    }
                                } else {
                                    Text("n/a", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            InfoPanel(ui = ui, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                PhoneTab.Capture -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = compactShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Capture", fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = { onSetCaptureSource("rpi") },
                                    label = { Text(if (selectedSource == "rpi") "• RPi" else "RPi") }
                                )
                                AssistChip(
                                    onClick = { onSetCaptureSource("esp") },
                                    label = { Text(if (selectedSource == "esp") "• ESP" else "ESP") }
                                )
                                AssistChip(
                                    onClick = { onSetCaptureSource("both") },
                                    label = { Text(if (selectedSource == "both") "• Both" else "Both") }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { onShot(selectedSource) },
                                    enabled = !ui.busy,
                                    shape = compactShape,
                                    contentPadding = compactBtnPadding
                                ) { Text("Shot") }
                                if (selectedRunning) {
                                    FilledTonalButton(
                                        onClick = { onStopAuto(selectedSource) },
                                        enabled = !ui.busy,
                                        shape = compactShape,
                                        contentPadding = compactBtnPadding
                                    ) { Text("Stop Auto") }
                                } else {
                                    FilledTonalButton(
                                        onClick = { onStartAuto(selectedSource, selectedInterval) },
                                        enabled = !ui.busy,
                                        shape = compactShape,
                                        contentPadding = compactBtnPadding
                                    ) { Text("Start Auto") }
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                intervalOptions.forEach { sec ->
                                    val label = when (sec) {
                                        30 -> "30s"
                                        60 -> "1m"
                                        120 -> "2m"
                                        180 -> "3m"
                                        300 -> "5m"
                                        else -> "${sec}s"
                                    }
                                    AssistChip(
                                        onClick = { onSetCaptureInterval(sec) },
                                        label = { Text(if (selectedInterval == sec) "• $label" else label) }
                                    )
                                }
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = compactShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (anyAutoRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("Source: ${selectedSource.uppercase()}", fontWeight = FontWeight.SemiBold)
                                    Text("Auto RPi: ${if (ui.autoCaptureRpiRunning == true) "running${ui.autoCaptureRpiIntervalSeconds?.let { " (${it}s)" } ?: ""}" else "stopped"}", fontWeight = FontWeight.SemiBold)
                                    Text("Auto ESP: ${if (ui.autoCaptureEspRunning == true) "running${ui.autoCaptureEspIntervalSeconds?.let { " (${it}s)" } ?: ""}" else "stopped"}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                PhoneTab.Gallery -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = compactShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Gallery", fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Items: ${ui.imagesTotalItems}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                FilledTonalButton(
                                    onClick = onGalleryPrev,
                                    enabled = !ui.busy && ui.imagesPage > 1,
                                    shape = compactShape,
                                    contentPadding = compactBtnPadding
                                ) { Text("Prev") }
                                Text("${ui.imagesPage}/${ui.imagesTotalPages}")
                                FilledTonalButton(
                                    onClick = onGalleryNext,
                                    enabled = !ui.busy && ui.imagesPage < ui.imagesTotalPages,
                                    shape = compactShape,
                                    contentPadding = compactBtnPadding
                                ) { Text("Next") }
                            }
                            PhoneGalleryGrid(
                                modifier = Modifier.weight(1f),
                                baseUrl = ui.baseUrl,
                                items = ui.images,
                                refreshing = ui.busy,
                                onRefresh = onGalleryRefresh,
                                onImageClick = onGalleryImageClick,
                                onDeleteClick = onGalleryDeleteClick
                            )
                        }
                    }
                }

                PhoneTab.System -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = compactShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("System", fontWeight = FontWeight.SemiBold)
                            FilledTonalButton(
                                onClick = onRestartServer,
                                enabled = !ui.busy,
                                shape = compactShape,
                                contentPadding = compactBtnPadding
                            ) { Text("Restart Server") }
                            FilledTonalButton(
                                onClick = { systemConfirmAction = "reboot" },
                                enabled = !ui.busy,
                                shape = compactShape,
                                contentPadding = compactBtnPadding
                            ) { Text("Reboot RPi") }
                            FilledTonalButton(
                                onClick = { systemConfirmAction = "poweroff" },
                                enabled = !ui.busy,
                                shape = compactShape,
                                contentPadding = compactBtnPadding
                            ) { Text("Power Off RPi") }
                            if (systemConfirmAction != null) {
                                val actionText = if (systemConfirmAction == "reboot") "Reboot Raspberry Pi?" else "Power off Raspberry Pi?"
                                AppConfirmDialog(
                                    title = "Confirm action",
                                    message = actionText,
                                    confirmLabel = "Confirm",
                                    destructive = true,
                                    busy = ui.busy,
                                    onConfirm = {
                                        if (systemConfirmAction == "reboot") onRebootSystem() else onPoweroffSystem()
                                        systemConfirmAction = null
                                    },
                                    onDismiss = { systemConfirmAction = null }
                                )
                            }
                            InfoPanel(ui = ui, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                PhoneTab.Log -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = compactShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Log", fontWeight = FontWeight.SemiBold)
                            BottomLogPanel(
                                modifier = Modifier.fillMaxSize(),
                                entries = logEntries
                            )
                        }
                    }
                }
            }
            if (ui.busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterialApi::class)
private fun PhoneGalleryGrid(
    modifier: Modifier,
    baseUrl: String,
    items: List<it.unipg.agriapp.data.ImageItem>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = onRefresh
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pullRefresh(pullRefreshState)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                        ) {
                            AsyncImage(
                                model = buildThumbnailUrl(baseUrl, item.filename),
                                contentDescription = item.filename,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .clickable { onImageClick(item.filename) },
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(999.dp))
                                    .clickable { onDeleteClick(item.filename) }
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text("X", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(formatCompactDateTimeGrid(item.filename), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@androidx.compose.runtime.Composable
private fun ShotDialog(
    busy: Boolean,
    onShotRpi: () -> Unit,
    onShotEsp: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Capture source", fontWeight = FontWeight.SemiBold)
                Text("Choose which device should take the next shot.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElevatedButton(onClick = onShotRpi, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("RPi") }
                    ElevatedButton(onClick = onShotEsp, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("ESP") }
                    FilledTonalButton(onClick = onDismiss, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StartAutoDialog(
    busy: Boolean,
    initialSource: String,
    initialIntervalSeconds: Int,
    onStart: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var source by remember {
        mutableStateOf(
            when (initialSource) {
                "esp" -> "esp"
                "both" -> "both"
                else -> "rpi"
            }
        )
    }
    var selected by remember { mutableStateOf(initialIntervalSeconds) }
    val options = listOf(30, 60, 120, 180, 300)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Start auto capture", fontWeight = FontWeight.SemiBold)
                Text("Choose source and interval")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { source = "rpi" },
                        label = { Text(if (source == "rpi") "• RPi" else "RPi") }
                    )
                    AssistChip(
                        onClick = { source = "esp" },
                        label = { Text(if (source == "esp") "• ESP" else "ESP") }
                    )
                    AssistChip(
                        onClick = { source = "both" },
                        label = { Text(if (source == "both") "• Both" else "Both") }
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEach { sec ->
                        val label = when (sec) {
                            30 -> "30s"
                            60 -> "1 min"
                            120 -> "2 min"
                            180 -> "3 min"
                            300 -> "5 min"
                            else -> "${sec}s"
                        }
                        AssistChip(
                            onClick = { selected = sec },
                            label = { Text(if (selected == sec) "• $label" else label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElevatedButton(onClick = { onStart(source, selected) }, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("Start") }
                    FilledTonalButton(onClick = onDismiss, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SystemActionsDialog(
    busy: Boolean,
    onRestartServer: () -> Unit,
    onReboot: () -> Unit,
    onPoweroff: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmAction by remember { mutableStateOf<String?>(null) }
    val actionButtonWidth: Dp = 150.dp

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("System actions", fontWeight = FontWeight.SemiBold)
                Text("Maintenance operations for Raspberry Pi.")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onRestartServer,
                        enabled = !busy,
                        modifier = Modifier.width(actionButtonWidth),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("Restart Server") }
                    Text("Restart only the AgriApp server service.", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { confirmAction = "reboot" },
                        enabled = !busy,
                        modifier = Modifier.width(actionButtonWidth),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("Reboot RPi") }
                    Text("Reboot the Raspberry Pi device.", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { confirmAction = "poweroff" },
                        enabled = !busy,
                        modifier = Modifier.width(actionButtonWidth),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("Poweroff RPi") }
                    Text("Power off the Raspberry Pi device.", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss, enabled = !busy, shape = RoundedCornerShape(6.dp)) { Text("Close") }
                }
            }
        }
    }

    if (confirmAction != null) {
        val action = confirmAction ?: ""
        val title = if (action == "reboot") "Reboot Raspberry Pi?" else "Poweroff Raspberry Pi?"
        val msg = if (action == "reboot") {
            "Are you sure? Raspberry Pi will be rebooted."
        } else {
            "Are you sure? Raspberry Pi will be powered off."
        }
        AppConfirmDialog(
            title = title,
            message = msg,
            confirmLabel = "Confirm",
            destructive = true,
            busy = busy,
            onConfirm = {
                if (action == "reboot") onReboot() else onPoweroff()
                confirmAction = null
            },
            onDismiss = { confirmAction = null }
        )
    }
}

@androidx.compose.runtime.Composable
private fun AppConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Cancel") }
                    Button(
                        onClick = onConfirm,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        } else ButtonDefaults.buttonColors()
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun InfoPanel(
    ui: MainUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            val freeMb = ui.system?.disk_free_bytes?.div(1024 * 1024) ?: 0
            Text("Status", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Free storage: ${freeMb} MB")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val apOn = ui.network?.ap_active == true
                val clientOn = ui.network?.client_active == true
                StatusBadge("AP ${if (apOn) "ON" else "OFF"}", ok = apOn)
                StatusBadge("WiFi ${if (clientOn) "ON" else "OFF"}", ok = clientOn)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusBadge(label: String, ok: Boolean) {
    val bg = if (ok) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
    val fg = if (ok) Color(0xFF1B5E20) else Color(0xFF8D4E00)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@androidx.compose.runtime.Composable
private fun HostChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compactHostLabel(raw: String): String {
    return raw
        .removePrefix("http://")
        .removePrefix("https://")
        .removeSuffix(":5000")
        .removeSuffix(":80")
}

@androidx.compose.runtime.Composable
private fun BottomLogPanel(
    modifier: Modifier = Modifier,
    entries: List<String>
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(4.dp)
            ) {
                val trackColor = MaterialTheme.colorScheme.outlineVariant
                val thumbColor = MaterialTheme.colorScheme.primary
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp),
                    state = listState
                ) {
                    items(entries) { line ->
                        Text(line)
                    }
                }

                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(6.dp)
                ) {
                    val total = entries.size.coerceAtLeast(1)
                    val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                    val first = listState.firstVisibleItemIndex.coerceAtLeast(0)

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(size.width, size.width)
                    )

                    val minThumbFraction = 0.10f
                    val thumbFraction = (visible.toFloat() / total.toFloat()).coerceIn(minThumbFraction, 1f)
                    val thumbHeight = size.height * thumbFraction
                    val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
                    val progress = if (total <= visible) 0f else (first.toFloat() / (total - visible).toFloat()).coerceIn(0f, 1f)
                    val top = maxTop * progress

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = CornerRadius(size.width, size.width)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@androidx.compose.runtime.Composable
private fun ImageList(
    modifier: Modifier = Modifier,
    ui: MainUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = onRefresh
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pullRefresh(pullRefreshState)
    ) {
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        val thumbColor = MaterialTheme.colorScheme.primary
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp),
                state = listState
            ) {
                items(ui.images) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = buildThumbnailUrl(ui.baseUrl, item.filename),
                            contentDescription = item.filename,
                            modifier = Modifier
                                .size(68.dp)
                                .clickable { onImageClick(item.filename) }
                                .clipToBounds(),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onImageClick(item.filename) }
                        ) {
                                Text(item.filename, fontWeight = FontWeight.Medium)
                                Text("${item.upload_time}  |  ${formatBytes(item.file_size_bytes)}  |  ${formatResolution(item.image_width, item.image_height)}")
                                Text(formatGridGpsEnv(item.metadata))
                            }
                            TextButton(onClick = { onDeleteClick(item.filename) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(6.dp)
            ) {
                val total = ui.images.size.coerceAtLeast(1)
                val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val first = listState.firstVisibleItemIndex.coerceAtLeast(0)

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(size.width, size.width)
                )

                val minThumbFraction = 0.10f
                val thumbFraction = (visible.toFloat() / total.toFloat()).coerceIn(minThumbFraction, 1f)
                val thumbHeight = size.height * thumbFraction
                val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
                val progress = if (total <= visible) 0f else (first.toFloat() / (total - visible).toFloat()).coerceIn(0f, 1f)
                val top = maxTop * progress

                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(size.width, size.width)
                )
            }
        }
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val b = if (bytes < 0) 0L else bytes
    val mb = 1024L * 1024L
    val kb = 1024L
    return when {
        b >= mb -> String.format("%.2f MB", b.toDouble() / mb.toDouble())
        b >= kb -> String.format("%.1f kB", b.toDouble() / kb.toDouble())
        else -> "$b B"
    }
}

private fun buildThumbnailUrl(baseUrl: String, filename: String): String {
    val cleanBase = baseUrl.trimEnd('/')
    val encoded = Uri.encode(filename)
    return "$cleanBase/api/v1/images/$encoded/thumbnail?w=320"
}

private fun formatResolution(width: Int?, height: Int?): String {
    val w = width ?: 0
    val h = height ?: 0
    return if (w > 0 && h > 0) "${w}x${h}" else "-"
}

private fun formatCompactDateTime(filename: String): String {
    val stamp = filename.substringAfter('_', "").substringBefore('.')
    if (stamp.length != 15 || stamp[8] != '-') return filename
    return try {
        val inFmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        val date = inFmt.parse(stamp) ?: return filename
        val outFmt = java.text.SimpleDateFormat("d MMM yyyy HH:mm:ss", java.util.Locale.getDefault())
        outFmt.format(java.util.Date(date.time))
    } catch (_: Exception) {
        filename
    }
}

private fun formatCompactDateTimeGrid(filename: String): String {
    val source = when {
        filename.lowercase().startsWith("rpi_") -> "RPi"
        filename.lowercase().startsWith("esp_") -> "ESP"
        else -> "Image"
    }
    val stamp = filename.substringAfter('_', "").substringBefore('.')
    if (stamp.length != 15 || stamp[8] != '-') return filename
    return try {
        val inFmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        val date = inFmt.parse(stamp) ?: return filename
        val outDate = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        val outTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        "$source ${outDate.format(date)}\n${outTime.format(date)}"
    } catch (_: Exception) {
        filename
    }
}

private fun formatGps(latitude: Double?, longitude: Double?): String {
    if (latitude == null || longitude == null) return "GPS: n/a"
    return String.format("GPS: %.6f, %.6f", latitude, longitude)
}

private fun formatGridGpsEnv(metadata: ImageMetadata?): String {
    val gps = formatGps(metadata?.latitude, metadata?.longitude)
    val t = metadata?.temperature?.let { String.format("%.1f C", it) } ?: "n/a"
    val h = metadata?.humidity?.let { String.format("%.1f %%", it) } ?: "n/a"
    val p = metadata?.pressure?.let { String.format("%.1f hPa", it) } ?: "n/a"
    return "$gps | T: $t | H: $h | P: $p"
}

private fun formatModalMetadata(metadata: ImageMetadata?): String {
    return formatGridGpsEnv(metadata)
}

@androidx.compose.runtime.Composable
private fun ZoomableImageDialog(
    imageModel: Any,
    title: String,
    metadata: ImageMetadata?,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var swipeAccumX by remember { mutableFloatStateOf(0f) }
    var lastSwipeMs by remember { mutableStateOf(0L) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "File name: $title",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            formatModalMetadata(metadata),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(onClick = onPrev, enabled = canPrev, shape = RoundedCornerShape(6.dp)) { Text("Prev") }
                    FilledTonalButton(onClick = onNext, enabled = canNext, shape = RoundedCornerShape(6.dp)) { Text("Next") }
                    FilledTonalButton(onClick = onDismiss, shape = RoundedCornerShape(6.dp)) { Text("Close") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(title) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                if (newScale == 1f) {
                                    if (abs(pan.x) > abs(pan.y)) {
                                        swipeAccumX += pan.x
                                        val now = System.currentTimeMillis()
                                        if ((now - lastSwipeMs) > 320L && abs(swipeAccumX) > 120f) {
                                            if (swipeAccumX > 0f && canPrev) onPrev()
                                            if (swipeAccumX < 0f && canNext) onNext()
                                            lastSwipeMs = now
                                            swipeAccumX = 0f
                                        }
                                    } else {
                                        swipeAccumX = 0f
                                    }
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    swipeAccumX = 0f
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                                scale = newScale
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val imageModifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                    if (imageModel is ByteArray) {
                        val bitmap = BitmapFactory.decodeByteArray(imageModel, 0, imageModel.size)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = title,
                                modifier = imageModifier
                            )
                        } else {
                            Text("Invalid image data")
                        }
                    } else {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = title,
                            modifier = imageModifier
                        )
                    }
                }
            }
        }
    }
}
