package it.unipg.agriapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.unipg.agriapp.ui.theme.AgriAppTheme
import kotlin.math.abs

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
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                var currentIndex by remember { mutableStateOf(initialIndex) }
                var swipeAccumX by remember { mutableFloatStateOf(0f) }
                var lastSwipeMs by remember { mutableStateOf(0L) }

                val activeFilename = if (currentIndex in filenames.indices) filenames[currentIndex] else filename
                val imageUrl = "$baseUrl/static/uploads/images/$activeFilename"
                val activeLatitude = valueAt(latitudes, currentIndex) ?: latitude
                val activeLongitude = valueAt(longitudes, currentIndex) ?: longitude
                val activeTemperature = valueAt(temperatures, currentIndex) ?: temperature
                val activeHumidity = valueAt(humidities, currentIndex) ?: humidity
                val activePressure = valueAt(pressures, currentIndex) ?: pressure

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
                                    .fillMaxHeight(0.82f)
                                    .clipToBounds()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = activeFilename,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = offsetX
                                            translationY = offsetY
                                        }
                                        .pointerInput(activeFilename, currentIndex) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                                val canSwipe = scale <= 1.02f && zoom in 0.98f..1.02f && filenames.size > 1
                                                if (canSwipe) {
                                                    swipeAccumX += pan.x
                                                    val now = System.currentTimeMillis()
                                                    if (abs(swipeAccumX) >= 140f && (now - lastSwipeMs) > 350L) {
                                                        val delta = if (swipeAccumX > 0f) -1 else 1
                                                        val target = currentIndex + delta
                                                        if (target in filenames.indices) {
                                                            currentIndex = target
                                                            scale = 1f
                                                            offsetX = 0f
                                                            offsetY = 0f
                                                            lastSwipeMs = now
                                                        }
                                                        swipeAccumX = 0f
                                                    }
                                                } else if (newScale == 1f) {
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                    swipeAccumX = 0f
                                                } else {
                                                    offsetX += pan.x
                                                    offsetY += pan.y
                                                    swipeAccumX = 0f
                                                }
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
                        }
                    }
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

private fun valueAt(values: DoubleArray?, index: Int): Double? {
    if (values == null || index !in values.indices) return null
    val v = values[index]
    return if (v.isNaN()) null else v
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
