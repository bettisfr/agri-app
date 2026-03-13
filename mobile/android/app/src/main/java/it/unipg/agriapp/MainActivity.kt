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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import it.unipg.agriapp.ui.MainUiState
import it.unipg.agriapp.ui.MainViewModel
import it.unipg.agriapp.ui.theme.AgriAppTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
                                .weight(0.44f)
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
                                    FilledTonalButton(onClick = { vm.loadNetworkMode { ui = it } }, enabled = !ui.busy) {
                                        Text("Refresh")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { vm.setNetworkMode("wifi_only") { ui = it } }, enabled = !ui.busy) {
                                        Text("WiFi")
                                    }
                                    FilledTonalButton(onClick = { vm.setNetworkMode("ap_only") { ui = it } }, enabled = !ui.busy) {
                                        Text("AP")
                                    }
                                }

                                if (ui.discoveredRpiBaseUrls.isNotEmpty()) {
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
                                if (ui.discoveredEspBaseUrls.isNotEmpty()) {
                                    Text("ESP found", fontWeight = FontWeight.SemiBold)
                                    ui.discoveredEspBaseUrls.take(4).forEach { host ->
                                        AssistChip(
                                            onClick = {
                                                vm.selectEspHost(host)
                                                ui = vm.state
                                            },
                                            label = {
                                                val label = host.removePrefix("http://")
                                                if (host == ui.selectedEspBaseUrl) Text("$label *") else Text(label)
                                            }
                                        )
                                    }
                                }
                                if (ui.discoveredRpiBaseUrls.isEmpty() && ui.discoveredEspBaseUrls.isEmpty()) {
                                    Text("No hosts found yet")
                                }

                                if (ui.busy) {
                                    CircularProgressIndicator()
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .weight(0.56f)
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
                                        Text("OneShot RPi")
                                    }
                                    ElevatedButton(
                                        onClick = { vm.oneShotEsp { ui = it } },
                                        enabled = !ui.busy
                                    ) {
                                        Text("OneShot ESP")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                }

                                InfoPanel(ui)
                                Text("Latest Images", fontWeight = FontWeight.SemiBold)
                                ImageList(
                                    modifier = Modifier.weight(1f),
                                    ui = ui,
                                    onImageClick = {
                                        vm.selectRpiImage(it)
                                        ui = vm.state
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    LogPanel(ui.log)
                }

                val viewerModel: Any? = ui.viewerImageBytes ?: ui.viewerImageUrl
                viewerModel?.let { model ->
                    ZoomableImageDialog(
                        imageModel = model,
                        title = ui.viewerTitle ?: "Image",
                        onDismiss = {
                            vm.closeViewer()
                            ui = vm.state
                        }
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun InfoPanel(ui: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F9EE))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ui.health?.let { Text("Health: ${it.status} (${it.service} ${it.version})") }
            ui.system?.let {
                Text("Host: ${it.hostname}")
                Text("Free disk: ${it.disk_free_bytes / (1024 * 1024)} MB")
            }
            ui.network?.let {
                Text("Net mode: ${it.mode}")
                Text("AP: ${if (it.ap_active) "on" else "off"} | Client: ${if (it.client_active) "on" else "off"}")
            }
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
            Text("Log: $log")
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImageList(modifier: Modifier = Modifier, ui: MainUiState, onImageClick: (String) -> Unit) {
    LazyColumn(modifier = modifier) {
        items(ui.images) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onImageClick(item.filename) }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(item.filename, fontWeight = FontWeight.Medium)
                    Text(item.upload_time)
                    Text(if (item.is_labeled) "Labeled (${item.labels_count})" else "To label")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ZoomableImageDialog(imageModel: Any, title: String, onDismiss: () -> Unit) {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onDismiss) { Text("Close") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
