package it.unipg.agriapp

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import it.unipg.agriapp.ui.MainUiState
import it.unipg.agriapp.ui.MainViewModel
import it.unipg.agriapp.ui.theme.AgriAppTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgriAppTheme {
                val vm: MainViewModel = viewModel()
                var ui by remember { mutableStateOf(vm.state) }
                var discoveryStarted by remember { mutableStateOf(false) }
                var autoBootstrapDone by remember { mutableStateOf(false) }
                var showSystemDialog by remember { mutableStateOf(false) }
                var pendingDelete by remember { mutableStateOf<String?>(null) }
                var showDeleteAllConfirm by remember { mutableStateOf(false) }

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
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000)
                        vm.loadSystem { ui = it }
                    }
                }

                Column(
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
                        .padding(12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FCF6)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
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

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Card(
                            modifier = Modifier
                                .weight(0.36f)
                                .fillMaxSize(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFDF8)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Connections", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { vm.discoverLan { ui = it } }, enabled = !ui.busy) {
                                        Text("Discover")
                                    }
                                    FilledTonalButton(onClick = { vm.setNetworkMode("wifi_only") { ui = it } }, enabled = !ui.busy) {
                                        Text("WiFi")
                                    }
                                    FilledTonalButton(onClick = { vm.setNetworkMode("ap_only") { ui = it } }, enabled = !ui.busy) {
                                        Text("AP")
                                    }
                                }

                                if (ui.discoveredRpiBaseUrls.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        Text("RPi found", fontWeight = FontWeight.SemiBold)
                                        ui.discoveredRpiBaseUrls.take(4).forEach { host ->
                                            AssistChip(
                                                onClick = {
                                                    vm.updateBaseUrl(host)
                                                    ui = vm.state
                                                },
                                                label = { Text(host.removePrefix("http://")) }
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
                                        Text("ESP found", fontWeight = FontWeight.SemiBold)
                                        ui.discoveredEspBaseUrls.take(4).forEach { host ->
                                            AssistChip(
                                                onClick = {
                                                    vm.selectEspHost(host)
                                                    ui = vm.state
                                                },
                                                label = { Text(host.removePrefix("http://")) }
                                            )
                                        }
                                    }
                                }
                                if (ui.discoveredRpiBaseUrls.isEmpty() && ui.discoveredEspBaseUrls.isEmpty()) {
                                    Text("No hosts found yet")
                                }

                                if (ui.busy) {
                                    CircularProgressIndicator()
                                }

                                InfoPanel(ui)
                                LogPanel(ui.log)
                            }
                        }

                        Card(
                            modifier = Modifier
                                .weight(0.64f)
                                .fillMaxSize(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFDF8)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Operations", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ElevatedButton(
                                        onClick = { vm.oneShotRpi { ui = it } },
                                        enabled = !ui.busy
                                    ) {
                                        Text("Shot RPi")
                                    }
                                    ElevatedButton(
                                        onClick = { vm.oneShotEsp { ui = it } },
                                        enabled = !ui.busy
                                    ) {
                                        Text("Shot ESP")
                                    }
                                    FilledTonalButton(
                                        onClick = { vm.loadSystem { ui = it } },
                                        enabled = !ui.busy
                                    ) {
                                        Text("Status")
                                    }
                                    FilledTonalButton(
                                        onClick = { vm.loadImages { ui = it } },
                                        enabled = !ui.busy
                                    ) {
                                        Text("Gallery")
                                    }
                                    FilledTonalButton(
                                        onClick = { showSystemDialog = true },
                                        enabled = !ui.busy
                                    ) {
                                        Text("System")
                                    }
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
                                    onImageClick = {
                                        vm.selectRpiImage(it)
                                        ui = vm.state
                                    },
                                    onDeleteClick = { pendingDelete = it }
                                )
                            }
                        }
                    }

                }

                val viewerModel: Any? = ui.viewerImageBytes ?: ui.viewerImageUrl
                viewerModel?.let { model ->
                    val currentIndex = ui.images.indexOfFirst { it.filename == ui.selectedImageFilename }
                    val canNavigate = ui.viewerImageBytes == null && currentIndex >= 0
                    val canPrev = canNavigate && currentIndex > 0
                    val canNext = canNavigate && currentIndex < (ui.images.size - 1)
                    ZoomableImageDialog(
                        imageModel = model,
                        title = ui.viewerTitle ?: "Image",
                        canPrev = canPrev,
                        canNext = canNext,
                        onPrev = {
                            if (!canPrev) return@ZoomableImageDialog
                            val prev = ui.images[currentIndex - 1].filename
                            vm.selectRpiImage(prev)
                            ui = vm.state
                        },
                        onNext = {
                            if (!canNext) return@ZoomableImageDialog
                            val next = ui.images[currentIndex + 1].filename
                            vm.selectRpiImage(next)
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
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FCF6))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        modifier = Modifier.width(actionButtonWidth)
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
                        modifier = Modifier.width(actionButtonWidth)
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
                        modifier = Modifier.width(actionButtonWidth)
                    ) { Text("Poweroff RPi") }
                    Text("Power off the Raspberry Pi device.", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss, enabled = !busy) { Text("Close") }
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F9EE))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val freeMb = ui.system?.disk_free_bytes?.div(1024 * 1024) ?: 0
            val apText = if (ui.network?.ap_active == true) "on" else "off"
            val clientText = if (ui.network?.client_active == true) "on" else "off"
            val modeText = ui.network?.mode ?: "-"
            Text("Free ${freeMb} MB  |  Mode $modeText  |  AP $apText  |  Client $clientText")
        }
    }
}

@androidx.compose.runtime.Composable
private fun LogPanel(log: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F9EE))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Log", fontWeight = FontWeight.Bold)
            Text(log)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImageList(
    modifier: Modifier = Modifier,
    ui: MainUiState,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    LazyColumn(modifier = modifier) {
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onImageClick(item.filename) }
                    ) {
                        Text(item.filename, fontWeight = FontWeight.Medium)
                        Text("${item.upload_time}  |  ${formatBytes(item.file_size_bytes)}  |  ${formatResolution(item.image_width, item.image_height)}")
                    }
                    TextButton(onClick = { onDeleteClick(item.filename) }) {
                        Text("Delete")
                    }
                }
            }
        }
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

private fun formatResolution(width: Int?, height: Int?): String {
    val w = width ?: 0
    val h = height ?: 0
    return if (w > 0 && h > 0) "${w}x${h}" else "-"
}

@androidx.compose.runtime.Composable
private fun ZoomableImageDialog(
    imageModel: Any,
    title: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onPrev, enabled = canPrev) { Text("Prev") }
                    Button(onClick = onNext, enabled = canNext) { Text("Next") }
                    Button(onClick = onDismiss) { Text("Close") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(title) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                if (newScale == 1f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
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
