package it.unipg.agriapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.unipg.agriapp.data.ApiClient
import it.unipg.agriapp.data.CaptureResponse
import it.unipg.agriapp.data.HealthResponse
import it.unipg.agriapp.data.ImageItem
import it.unipg.agriapp.data.NetworkModeRequest
import it.unipg.agriapp.data.NetworkModeResponse
import it.unipg.agriapp.data.SystemStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class MainUiState(
    val baseUrl: String = "http://raspberrypi.local:5000",
    val connectedHost: String? = null,
    val health: HealthResponse? = null,
    val system: SystemStatusResponse? = null,
    val network: NetworkModeResponse? = null,
    val images: List<ImageItem> = emptyList(),
    val selectedImageFilename: String? = null,
    val discoveredRpiBaseUrls: List<String> = emptyList(),
    val discoveredEspBaseUrls: List<String> = emptyList(),
    val selectedEspBaseUrl: String? = null,
    val viewerImageUrl: String? = null,
    val viewerImageBytes: ByteArray? = null,
    val viewerTitle: String? = null,
    val log: String = "Ready",
    val busy: Boolean = false
)

class MainViewModel : ViewModel() {
    var state: MainUiState = MainUiState()
        private set

    fun updateBaseUrl(value: String) {
        state = state.copy(baseUrl = value)
    }

    fun checkHealth(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().health()
        state = state.copy(health = res, connectedHost = state.baseUrl, log = "Health: ${res.status}")
    }

    fun loadSystem(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().systemStatus()
        state = state.copy(system = res, connectedHost = state.baseUrl, log = "System: ${res.hostname}")
    }

    fun loadNetworkMode(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().networkMode()
        state = state.copy(network = res, connectedHost = state.baseUrl, log = "Network mode: ${res.mode}")
    }

    fun setNetworkMode(mode: String, onState: (MainUiState) -> Unit) = runCall(onState) {
        val expectedBase = when (mode) {
            "ap_only" -> "http://192.168.4.1:5000"
            "wifi_only" -> "http://raspberrypi.local:5000"
            else -> state.baseUrl
        }
        try {
            val res = api().setNetworkMode(NetworkModeRequest(mode))
            state = state.copy(
                baseUrl = expectedBase,
                network = res,
                log = if (res.status == "success") "Network mode set: ${res.mode}" else "Network mode failed"
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val netSwitchLikely = msg.contains("software caused connection abort") ||
                msg.contains("failed to connect") ||
                msg.contains("timeout")
            if (netSwitchLikely) {
                state = state.copy(
                    baseUrl = expectedBase,
                    log = "Network switch in progress. Press Discover on the new network."
                )
            } else {
                throw e
            }
        }
    }

    fun loadImages(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = 1, pageSize = 20)
        state = state.copy(
            images = res.items,
            selectedImageFilename = state.selectedImageFilename ?: res.items.firstOrNull()?.filename,
            connectedHost = state.baseUrl,
            log = "Images loaded: ${res.items.size}/${res.total_items}"
        )
    }

    fun oneShotRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: CaptureResponse = api().oneShot()
        val imagesRes = api().images(page = 1, pageSize = 20)
        val preferred = res.latest_filename ?: imagesRes.items.firstOrNull()?.filename
        state = state.copy(
            images = imagesRes.items,
            selectedImageFilename = preferred,
            connectedHost = state.baseUrl,
            viewerImageUrl = preferred?.let { "${state.baseUrl}/static/uploads/images/$it" },
            viewerImageBytes = null,
            viewerTitle = preferred ?: "RPi capture",
            log = "Capture: ${res.status} ${res.latest_filename ?: ""}".trim()
        )
    }

    fun oneShotEsp(onState: (MainUiState) -> Unit) = runCall(onState) {
        val espBase = state.selectedEspBaseUrl ?: state.discoveredEspBaseUrls.firstOrNull()
        if (espBase == null) {
            state = state.copy(log = "ESP not selected/found")
            return@runCall
        }
        val profileOk = setEspCaptureProfile(espBase)
        val jpegBytes = captureEspJpeg(espBase)
        state = if (jpegBytes != null) {
            state.copy(
                selectedEspBaseUrl = espBase,
                viewerImageUrl = null,
                viewerImageBytes = jpegBytes,
                viewerTitle = "ESP capture",
                log = if (profileOk) "ESP capture: success" else "ESP capture: success (profile skipped)"
            )
        } else {
            state.copy(log = if (profileOk) "ESP capture: failed" else "ESP capture: failed (profile set failed)")
        }
    }

    fun selectRpiImage(filename: String) {
        state = state.copy(
            selectedImageFilename = filename,
            viewerImageUrl = "${state.baseUrl}/static/uploads/images/$filename",
            viewerImageBytes = null,
            viewerTitle = filename
        )
    }

    fun closeViewer() {
        state = state.copy(viewerImageUrl = null, viewerImageBytes = null, viewerTitle = null)
    }

    fun selectEspHost(host: String) {
        state = state.copy(selectedEspBaseUrl = host)
    }

    fun discoverLan(onState: (MainUiState) -> Unit) = runCall(onState) {
        val subnetPrefix = extractSubnetPrefix(state.baseUrl)
        val targetPrefix = when (state.network?.mode) {
            "ap_only" -> "192.168.4."
            "wifi_only", "hybrid_debug" -> "192.168.1."
            else -> if (subnetPrefix == "192.168.4.") "192.168.4." else "192.168.1."
        }
        state = state.copy(
            log = "Discovery on ${targetPrefix}0/24 ...",
            discoveredRpiBaseUrls = emptyList(),
            discoveredEspBaseUrls = emptyList(),
        )
        onState(state)

        val directRpiCandidates = if (targetPrefix == "192.168.4.") {
            listOf(
                "http://192.168.4.1:5000",
                normalizeBase(state.baseUrl, withPort5000 = true),
            )
        } else {
            listOf(
                normalizeBase(state.baseUrl, withPort5000 = true),
                "http://raspberrypi.local:5000",
            )
        }

        val directRpiFound = withContext(Dispatchers.IO) {
            directRpiCandidates
                .distinct()
                .map { candidate ->
                    async {
                        if (isAgriAppUp(candidate)) candidate else null
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val scannedRpi = withContext(Dispatchers.IO) {
            (1..254).chunked(24).flatMap { chunk ->
                chunk.map { host ->
                    async {
                        val base = "http://${targetPrefix}${host}:5000"
                        if (isAgriAppUp(base)) base else null
                    }
                }.awaitAll().filterNotNull()
            }
        }

        val foundRpi = (directRpiFound + scannedRpi).distinct().sorted()

        val foundEsp = withContext(Dispatchers.IO) {
            (1..254).chunked(24).flatMap { chunk ->
                chunk.map { host ->
                    async {
                        val base = "http://${targetPrefix}${host}"
                        if (isEspCamUp(base)) base else null
                    }
                }.awaitAll().filterNotNull()
            }
        }.sorted()

        val updatedBase = foundRpi.firstOrNull() ?: state.baseUrl
        state = state.copy(
            baseUrl = updatedBase,
            connectedHost = foundRpi.firstOrNull(),
            discoveredRpiBaseUrls = foundRpi,
            discoveredEspBaseUrls = foundEsp,
            selectedEspBaseUrl = foundEsp.firstOrNull(),
            log = "Discovery: RPi=${foundRpi.size}, ESP=${foundEsp.size}"
        )
    }

    private fun runCall(onState: (MainUiState) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            state = state.copy(busy = true)
            onState(state)
            try {
                block()
            } catch (e: Exception) {
                state = state.copy(log = "Error: ${e.message}")
            } finally {
                state = state.copy(busy = false)
                onState(state)
            }
        }
    }

    private fun api() = ApiClient.create(state.baseUrl)

    private fun extractSubnetPrefix(baseUrl: String): String? {
        val host = baseUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")

        val parts = host.split(".")
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() == null }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun normalizeBase(raw: String, withPort5000: Boolean): String {
        var base = raw.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://$base"
        }
        base = base.substringBefore("/")
        val hostPort = base.substringAfter("://", base)
        if (withPort5000 && !hostPort.contains(":")) {
            base += ":5000"
        }
        return base
    }

    private fun isAgriAppUp(base: String): Boolean {
        return try {
            val url = URL("$base/api/v1/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 400
                readTimeout = 600
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun isEspCamUp(base: String): Boolean {
        val statusOk = try {
            val url = URL("$base/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 300
                readTimeout = 500
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
        if (statusOk) return true

        return try {
            val url = URL("$base/capture")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 300
                readTimeout = 500
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun setEspCaptureProfile(base: String): Boolean {
        // UXGA is currently the highest stable mode for this board.
        val setQuality = espControl(base, "quality", "10")
        val setFramesize = espControl(base, "framesize", "15") // UXGA
        return setQuality && setFramesize
    }

    private fun espControl(base: String, varName: String, value: String): Boolean {
        return try {
            val url = URL("$base/control?var=$varName&val=$value")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 2000
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun captureEspJpeg(base: String): ByteArray? {
        return try {
            val url = URL("$base/capture")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 12000
            }
            val code = conn.responseCode
            if (code !in 200..299) return null

            val bytes = conn.inputStream.use { it.readBytes() }
            val isJpeg = bytes.size > 4 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[bytes.size - 2] == 0xFF.toByte() &&
                bytes[bytes.size - 1] == 0xD9.toByte()
            if (isJpeg) bytes else null
        } catch (_: Exception) {
            null
        }
    }
}
