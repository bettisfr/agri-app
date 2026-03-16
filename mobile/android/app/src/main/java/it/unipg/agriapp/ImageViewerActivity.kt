package it.unipg.agriapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import it.unipg.agriapp.ui.theme.AgriAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
class ImageViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = (intent?.getStringExtra(EXTRA_BASE_URL) ?: "http://raspberrypi.local:5000").trimEnd('/')
        val filename = intent?.getStringExtra(EXTRA_FILENAME) ?: ""
        val filenames = intent?.getStringArrayExtra(EXTRA_FILENAMES)?.toList().orEmpty()
        val initialIndexRaw = intent?.getIntExtra(EXTRA_INDEX, -1) ?: -1
        val initialIndex = if (filenames.isNotEmpty()) initialIndexRaw.coerceIn(0, filenames.lastIndex) else -1
        val latitudes = intent?.getDoubleArrayExtra(EXTRA_LATITUDES)
        val longitudes = intent?.getDoubleArrayExtra(EXTRA_LONGITUDES)
        val temperatures = intent?.getDoubleArrayExtra(EXTRA_TEMPERATURES)
        val humidities = intent?.getDoubleArrayExtra(EXTRA_HUMIDITIES)
        val pressures = intent?.getDoubleArrayExtra(EXTRA_PRESSURES)
        val latitude = intent?.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)?.takeIf { !it.isNaN() }
        val longitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)?.takeIf { !it.isNaN() }
        val temperature = intent?.getDoubleExtra(EXTRA_TEMPERATURE, Double.NaN)?.takeIf { !it.isNaN() }
        val humidity = intent?.getDoubleExtra(EXTRA_HUMIDITY, Double.NaN)?.takeIf { !it.isNaN() }
        val pressure = intent?.getDoubleExtra(EXTRA_PRESSURE, Double.NaN)?.takeIf { !it.isNaN() }

        setContent {
            AgriAppTheme {
                data class ViewerItem(
                    val filename: String,
                    val latitude: Double? = null,
                    val longitude: Double? = null,
                    val temperature: Double? = null,
                    val humidity: Double? = null,
                    val pressure: Double? = null
                )
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                var viewportWidth by remember { mutableFloatStateOf(1f) }
                var viewportHeight by remember { mutableFloatStateOf(1f) }
                var currentIndex by remember { mutableStateOf(initialIndex) }
                var confirmDelete by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf(false) }
                var deleteError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                var viewerItems by remember {
                    mutableStateOf(
                        if (filenames.isNotEmpty()) {
                            filenames.mapIndexed { idx, fn ->
                                ViewerItem(
                                    filename = fn,
                                    latitude = valueAt(latitudes, idx),
                                    longitude = valueAt(longitudes, idx),
                                    temperature = valueAt(temperatures, idx),
                                    humidity = valueAt(humidities, idx),
                                    pressure = valueAt(pressures, idx),
                                )
                            }
                        } else {
                            listOf(
                                ViewerItem(
                                    filename = filename,
                                    latitude = latitude,
                                    longitude = longitude,
                                    temperature = temperature,
                                    humidity = humidity,
                                    pressure = pressure
                                )
                            )
                        }
                    )
                }

                val safeIndex = currentIndex.coerceIn(0, (viewerItems.size - 1).coerceAtLeast(0))
                if (safeIndex != currentIndex) currentIndex = safeIndex
                val active = viewerItems.getOrNull(currentIndex)
                val activeFilename = active?.filename ?: filename
                val imageUrl = "$baseUrl/static/uploads/images/$activeFilename"
                val activeLatitude = active?.latitude ?: latitude
                val activeLongitude = active?.longitude ?: longitude
                val activeTemperature = active?.temperature ?: temperature
                val activeHumidity = active?.humidity ?: humidity
                val activePressure = active?.pressure ?: pressure

                val titleText = formatViewerTitle(activeFilename)
                val gpsText = if (activeLatitude != null && activeLongitude != null) {
                    String.format("%.6f, %.6f", activeLatitude, activeLongitude)
                } else {
                    "n/a"
                }
                val temperatureText = activeTemperature?.let { String.format("%.1f C", it) } ?: "n/a"
                val humidityText = activeHumidity?.let { String.format("%.1f %%", it) } ?: "n/a"
                val pressureText = activePressure?.let { String.format("%.1f hPa", it) } ?: "n/a"

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    titleText.ifBlank { "Image" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                    }
                ) { inner ->
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = true)
                                    .heightIn(max = 340.dp)
                                    .clipToBounds()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = activeFilename,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { sz ->
                                            viewportWidth = sz.width.toFloat().coerceAtLeast(1f)
                                            viewportHeight = sz.height.toFloat().coerceAtLeast(1f)
                                        }
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = offsetX
                                            translationY = offsetY
                                        }
                                        .pointerInput(activeFilename, currentIndex, viewportWidth, viewportHeight) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                                if (newScale <= 1.01f) {
                                                    scale = 1f
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                    return@detectTransformGestures
                                                }
                                                val maxX = (viewportWidth * (newScale - 1f)) / 2f
                                                val maxY = (viewportHeight * (newScale - 1f)) / 2f
                                                offsetX = (offsetX + pan.x * 1.15f).coerceIn(-maxX, maxX)
                                                offsetY = (offsetY + pan.y * 1.15f).coerceIn(-maxY, maxY)
                                                scale = newScale
                                            }
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("GPS: $gpsText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Temperature: $temperatureText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Humidity: $humidityText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Pressure: $pressureText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (viewerItems.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            if (currentIndex > 0) {
                                                currentIndex -= 1
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        },
                                        enabled = !deleting && currentIndex > 0,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text("Prev") }
                                    FilledTonalButton(
                                        onClick = {
                                            if (currentIndex < viewerItems.lastIndex) {
                                                currentIndex += 1
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        },
                                        enabled = !deleting && currentIndex < viewerItems.lastIndex,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text("Next") }
                                }
                            }
                            Button(
                                onClick = { confirmDelete = true },
                                enabled = !deleting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("Delete") }
                            deleteError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (confirmDelete) {
                    AppDeleteDialog(
                        busy = deleting,
                        title = "Delete image?",
                        message = "Delete $activeFilename?",
                        onDismiss = { confirmDelete = false },
                        onConfirm = {
                            scope.launch {
                                deleting = true
                                val ok = withContext(Dispatchers.IO) {
                                    deleteImageViaApi(baseUrl, activeFilename)
                                }
                                deleting = false
                                confirmDelete = false
                                if (!ok) {
                                    deleteError = "Delete failed"
                                    return@launch
                                }
                                deleteError = null
                                viewerItems = viewerItems.filterNot { it.filename == activeFilename }
                                if (viewerItems.isEmpty()) {
                                    finish()
                                } else if (currentIndex >= viewerItems.size) {
                                    currentIndex = viewerItems.size - 1
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_FILENAMES = "extra_filenames"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_TEMPERATURE = "extra_temperature"
        const val EXTRA_HUMIDITY = "extra_humidity"
        const val EXTRA_PRESSURE = "extra_pressure"
        const val EXTRA_LATITUDES = "extra_latitudes"
        const val EXTRA_LONGITUDES = "extra_longitudes"
        const val EXTRA_TEMPERATURES = "extra_temperatures"
        const val EXTRA_HUMIDITIES = "extra_humidities"
        const val EXTRA_PRESSURES = "extra_pressures"
    }
}

@Composable
private fun AppDeleteDialog(
    busy: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text("Delete") }
                }
            }
        }
    }
}

private fun valueAt(values: DoubleArray?, index: Int): Double? {
    if (values == null || index !in values.indices) return null
    val v = values[index]
    return if (v.isNaN()) null else v
}

private fun deleteImageViaApi(baseUrlRaw: String, filename: String): Boolean {
    return try {
        val base = baseUrlRaw.trimEnd('/')
        val url = URL("$base/api/v1/images/delete")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = """{"filename":"${filename.replace("\"", "\\\"")}"}"""
        conn.outputStream.use { it.write(payload.toByteArray()) }
        val code = conn.responseCode
        code in 200..299
    } catch (_: Exception) {
        false
    }
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

private fun formatViewerTitle(filename: String): String {
    val source = when {
        filename.lowercase().startsWith("rpi_") -> "RPi"
        filename.lowercase().startsWith("esp_") -> "ESP"
        else -> "Image"
    }
    val ts = formatCompactDateTime(filename)
    return if (ts == filename) "$source $filename" else "$source $ts"
}
