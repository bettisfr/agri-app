package it.unipg.agriapp

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import it.unipg.agriapp.data.ImageMetadata
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgriAppTheme {
                val compactShape = RoundedCornerShape(6.dp)
                val compactBtnPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
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
                        vm.loadSystem { ui = it }
                        vm.loadNetworkMode { ui = it }
                    }
                }
                LaunchedEffect(ui.autoCaptureRpiRunning, ui.autoCaptureEspRunning) {
                    while (ui.autoCaptureRpiRunning == true || ui.autoCaptureEspRunning == true) {
                        delay(12_000)
                        vm.refreshGallerySilently { ui = it }
                        vm.loadAutoCaptureStatus { ui = it }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFE8F5E9),
                                    Color(0xFFF4FBF2),
                                    Color(0xFFFFFFFF),
                                )
                            )
                        )
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = compactShape,
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FCF6)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "AgriApp Control",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Host: ${ui.connectedHost?.removePrefix("http://") ?: "not connected"}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(0.36f)
                                    .fillMaxSize(),
                                shape = compactShape,
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFDF8)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
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

                                    if (ui.discoveredRpiBaseUrls.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.horizontalScroll(rememberScrollState())
                                        ) {
                                            Text("RPi", fontWeight = FontWeight.SemiBold)
                                            ui.discoveredRpiBaseUrls.take(4).forEach { host ->
                                                HostChip(
                                                    label = compactHostLabel(host),
                                                    onClick = {
                                                        vm.updateBaseUrl(host)
                                                        ui = vm.state
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (ui.discoveredEspBaseUrls.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.horizontalScroll(rememberScrollState())
                                        ) {
                                            Text("ESP", fontWeight = FontWeight.SemiBold)
                                            ui.discoveredEspBaseUrls.take(4).forEach { host ->
                                                HostChip(
                                                    label = compactHostLabel(host),
                                                    onClick = {
                                                        vm.selectEspHost(host)
                                                        ui = vm.state
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (ui.discoveredRpiBaseUrls.isEmpty() && ui.discoveredEspBaseUrls.isEmpty()) {
                                        Text("No hosts found yet")
                                    }

                                    InfoPanel(ui)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(0.64f)
                                    .fillMaxSize(),
                                shape = compactShape,
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFDF8)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Operations", fontWeight = FontWeight.SemiBold)
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
                                            enabled = !ui.busy,
                                            shape = compactShape,
                                            contentPadding = compactBtnPadding
                                        ) {
                                            Text("Start Auto")
                                        }
                                        FilledTonalButton(
                                            onClick = { vm.stopAutoCapture({ ui = it }, ui.selectedAutoCaptureSource) },
                                            enabled = !ui.busy,
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
                                            containerColor = if (ui.autoCaptureRpiRunning == true || ui.autoCaptureEspRunning == true) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
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

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Latest Images", fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { vm.prevImagesPage { ui = it } },
                                            enabled = !ui.busy && ui.imagesPage > 1
                                        ) { Text("Prev") }
                                        Text("${ui.imagesPage}/${ui.imagesTotalPages}")
                                        TextButton(
                                            onClick = { vm.nextImagesPage { ui = it } },
                                            enabled = !ui.busy && ui.imagesPage < ui.imagesTotalPages
                                        ) { Text("Next") }
                                        TextButton(
                                            onClick = { showDeleteAllConfirm = true },
                                            enabled = !ui.busy && ui.images.isNotEmpty()
                                        ) {
                                            Text("Delete All")
                                        }
                                    }
                                }
                                ImageList(
                                    modifier = Modifier.weight(1f),
                                    ui = ui,
                                    refreshing = galleryRefreshing,
                                    onRefresh = {
                                        galleryRefreshing = true
                                        vm.refreshGalleryOnly {
                                            ui = it
                                            if (!it.busy) {
                                                galleryRefreshing = false
                                            }
                                        }
                                    },
                                    onImageClick = {
                                        vm.selectRpiImage(it)
                                        ui = vm.state
                                    },
                                    onDeleteClick = { pendingDelete = it }
                                )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        BottomLogPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            entries = logEntries
                        )
                    }
                    if (ui.busy) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
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
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = { Text("Delete image?") },
                        text = { Text("Are you sure you want to delete $filename?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    vm.deleteImage(filename) { ui = it }
                                    pendingDelete = null
                                },
                                enabled = !ui.busy
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }, enabled = !ui.busy) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                if (showDeleteAllConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteAllConfirm = false },
                        title = { Text("Delete all images?") },
                        text = { Text("Are you sure you want to delete all gallery images, labels and json files?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    vm.deleteAllImages { ui = it }
                                    showDeleteAllConfirm = false
                                },
                                enabled = !ui.busy
                            ) { Text("Delete All") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteAllConfirm = false }, enabled = !ui.busy) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
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
    val options = listOf(60, 120, 180, 300, 600)

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
                            60 -> "1 min"
                            120 -> "2 min"
                            180 -> "3 min"
                            300 -> "5 min"
                            600 -> "10 min"
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
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (action == "reboot") onReboot() else onPoweroff()
                        confirmAction = null
                    },
                    enabled = !busy
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }, enabled = !busy) {
                    Text("No")
                }
            }
        )
    }
}

@androidx.compose.runtime.Composable
private fun InfoPanel(ui: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val freeMb = ui.system?.disk_free_bytes?.div(1024 * 1024) ?: 0
            val apText = if (ui.network?.ap_active == true) "on" else "off"
            val clientText = if (ui.network?.client_active == true) "on" else "off"
            Text("Status", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Free ${freeMb} MB")
            Spacer(Modifier.height(4.dp))
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
    val bg = if (ok) Color(0xFFDDF2DF) else Color(0xFFFFEDD5)
    val fg = if (ok) Color(0xFF1F6B2A) else Color(0xFF9A3412)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
            .background(Color.White, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(label, fontWeight = FontWeight.Medium)
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F9EE))
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text("Log", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .padding(4.dp)
            ) {
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

                    val trackColor = Color(0xFFCFDAC8)
                    val thumbColor = Color(0xFF6F8E6A)

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
                            .padding(bottom = 8.dp)
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

                val trackColor = Color(0xFFCFDAC8)
                val thumbColor = Color(0xFF6F8E6A)

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
