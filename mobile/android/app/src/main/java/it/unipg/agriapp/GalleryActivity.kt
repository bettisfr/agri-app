package it.unipg.agriapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import it.unipg.agriapp.data.ImageMetadata
import it.unipg.agriapp.ui.GalleryUiState
import it.unipg.agriapp.ui.GalleryViewModel
import it.unipg.agriapp.ui.theme.AgriAppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialBaseUrl = intent?.getStringExtra(EXTRA_BASE_URL) ?: "http://raspberrypi.local:5000"
        setContent {
            AgriAppTheme {
                val vm: GalleryViewModel = viewModel()
                var ui by remember { mutableStateOf(vm.state) }
                var pendingDelete by remember { mutableStateOf<String?>(null) }
                var showDeleteAllConfirm by remember { mutableStateOf(false) }
                var refreshing by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    vm.setBaseUrl(initialBaseUrl)
                    ui = vm.state
                    vm.loadImages { ui = it }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text("Gallery", fontWeight = FontWeight.SemiBold) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            actions = {
                                Text(
                                    text = ui.baseUrl.removePrefix("http://"),
                                    modifier = Modifier.padding(end = 12.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Images ${ui.totalItems}", fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { vm.prevPage { ui = it } },
                                            enabled = !ui.busy && ui.page > 1
                                        ) { Text("Prev") }
                                        Text("${ui.page}/${ui.totalPages}")
                                        TextButton(
                                            onClick = { vm.nextPage { ui = it } },
                                            enabled = !ui.busy && ui.page < ui.totalPages
                                        ) { Text("Next") }
                                        TextButton(onClick = { vm.refresh { ui = it } }, enabled = !ui.busy) { Text("Refresh") }
                                        TextButton(
                                            onClick = { showDeleteAllConfirm = true },
                                            enabled = !ui.busy && ui.images.isNotEmpty()
                                        ) { Text("Delete All") }
                                    }
                                }
                                GalleryImageList(
                                    modifier = Modifier.weight(1f),
                                    ui = ui,
                                    refreshing = refreshing,
                                    onRefresh = {
                                        refreshing = true
                                        vm.refresh {
                                            ui = it
                                            if (!it.busy) refreshing = false
                                        }
                                    },
                                    onImageClick = {
                                        vm.openImage(it)
                                        ui = vm.state
                                    },
                                    onDeleteClick = { pendingDelete = it }
                                )
                                Text(
                                    ui.log,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (ui.busy) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x44000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }

                ui.viewerImageUrl?.let { model ->
                    val currentIndex = ui.images.indexOfFirst { it.filename == ui.selectedImageFilename }
                    val selectedItem = if (currentIndex >= 0) ui.images[currentIndex] else null
                    val canPrev = currentIndex > 0
                    val canNext = currentIndex >= 0 && currentIndex < (ui.images.size - 1)
                    ZoomableImageDialog(
                        imageModel = model,
                        title = ui.viewerTitle ?: "Image",
                        metadata = selectedItem?.metadata,
                        canPrev = canPrev,
                        canNext = canNext,
                        onPrev = {
                            vm.openAdjacent(-1)
                            ui = vm.state
                        },
                        onNext = {
                            vm.openAdjacent(1)
                            ui = vm.state
                        },
                        onDismiss = {
                            vm.closeViewer()
                            ui = vm.state
                        }
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
                            TextButton(onClick = { pendingDelete = null }, enabled = !ui.busy) { Text("Cancel") }
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
                                    vm.deleteAll { ui = it }
                                    showDeleteAllConfirm = false
                                },
                                enabled = !ui.busy
                            ) { Text("Delete All") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteAllConfirm = false }, enabled = !ui.busy) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_BASE_URL = "extra_base_url"
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun GalleryImageList(
    modifier: Modifier = Modifier,
    ui: GalleryUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onImageClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    val gridState = rememberLazyGridState()
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.images) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            ) {
                                AsyncImage(
                                    model = buildThumbnailUrl(ui.baseUrl, item.filename),
                                    contentDescription = item.filename,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { onImageClick(item.filename) }
                                        .clipToBounds(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(
                                            Color(0xFFD32F2F).copy(alpha = 0.95f),
                                            RoundedCornerShape(999.dp)
                                        )
                                        .clickable { onDeleteClick(item.filename) }
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text("X", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Column(modifier = Modifier.clickable { onImageClick(item.filename) }) {
                                Text(
                                    formatDisplayTitle(item.filename),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${formatBytes(item.file_size_bytes)} | ${formatResolution(item.image_width, item.image_height)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    formatGps(item.metadata?.latitude, item.metadata?.longitude),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    formatGridEnvOnly(item.metadata),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                val visible = gridState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val first = gridState.firstVisibleItemIndex.coerceAtLeast(0)

                val trackColor = Color(0xFFC7D6C3)
                val thumbColor = Color(0xFF5E7F58)

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

@Composable
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
                            formatGridGpsEnv(metadata),
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
                    AsyncImage(
                        model = imageModel,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                    )
                }
            }
        }
    }
}

private fun buildThumbnailUrl(baseUrl: String, filename: String): String {
    val cleanBase = baseUrl.trimEnd('/')
    val encoded = Uri.encode(filename)
    return "$cleanBase/api/v1/images/$encoded/thumbnail?w=320"
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

private fun formatGridEnvOnly(metadata: ImageMetadata?): String {
    val t = metadata?.temperature?.let { String.format("%.1f C", it) } ?: "n/a"
    val h = metadata?.humidity?.let { String.format("%.1f %%", it) } ?: "n/a"
    val p = metadata?.pressure?.let { String.format("%.1f hPa", it) } ?: "n/a"
    return "T: $t | H: $h | P: $p"
}

private fun formatDisplayTitle(filename: String): String {
    val lower = filename.lowercase(Locale.getDefault())
    val source = when {
        lower.startsWith("rpi_") -> "RPi"
        lower.startsWith("esp_") -> "ESP"
        else -> null
    } ?: return filename

    val stamp = filename
        .substringAfter('_', "")
        .substringBefore('.')
    if (stamp.length != 15 || stamp[8] != '-') return "$source | $filename"

    return try {
        val inFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val date = inFmt.parse(stamp) ?: return "$source | $filename"
        val outFmt = SimpleDateFormat("EEE dd MMM yyyy HH:mm:ss", Locale.getDefault())
        "$source | ${outFmt.format(Date(date.time))}"
    } catch (_: Exception) {
        "$source | $filename"
    }
}
