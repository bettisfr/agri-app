package it.unipg.agriapp.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.unipg.agriapp.data.ActionResponse
import it.unipg.agriapp.data.ApiClient
import it.unipg.agriapp.data.CaptureResponse
import it.unipg.agriapp.data.CaptureLoopStartRequest
import it.unipg.agriapp.data.CaptureLoopStatusResponse
import it.unipg.agriapp.data.DeleteImageRequest
import it.unipg.agriapp.data.DeleteImageResponse
import it.unipg.agriapp.data.HealthResponse
import it.unipg.agriapp.data.ImageItem
import it.unipg.agriapp.data.ImagesResponse
import it.unipg.agriapp.data.NetworkModeRequest
import it.unipg.agriapp.data.NetworkModeResponse
import it.unipg.agriapp.data.SystemStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MainUiState(
    val baseUrl: String = "http://raspberrypi.local:5000",
    val connectedHost: String? = null,
    val health: HealthResponse? = null,
    val system: SystemStatusResponse? = null,
    val network: NetworkModeResponse? = null,
    val images: List<ImageItem> = emptyList(),
    val imagesPage: Int = 1,
    val imagesTotalPages: Int = 1,
    val imagesTotalItems: Int = 0,
    val imagesPageSize: Int = 20,
    val selectedImageFilename: String? = null,
    val discoveredRpiBaseUrls: List<String> = emptyList(),
    val discoveredEspBaseUrls: List<String> = emptyList(),
    val selectedEspBaseUrl: String? = null,
    val viewerImageUrl: String? = null,
    val viewerImageBytes: ByteArray? = null,
    val viewerTitle: String? = null,
    val autoCaptureRunning: Boolean? = null,
    val autoCaptureIntervalSeconds: Int? = null,
    val autoCaptureStartedAtTs: Long? = null,
    val selectedAutoCaptureIntervalSeconds: Int = 300,
    val log: String = "Ready",
    val busy: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val logTag = "AgriAppESP"
    private val prefs = application.getSharedPreferences("agriapp_hosts", Context.MODE_PRIVATE)
    private val prefRpiHosts = "rpi_hosts"
    private val prefEspHosts = "esp_hosts"
    var state: MainUiState = MainUiState()
        private set

    fun updateBaseUrl(value: String) {
        val normalized = normalizeBase(value, withPort5000 = true)
        state = state.copy(baseUrl = normalized)
        rememberRpiHost(normalized)
    }

    fun checkHealth(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().health()
        state = state.copy(health = res, connectedHost = state.baseUrl, log = "Health: ${res.status}")
    }

    fun loadSystem(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().systemStatus()
        rememberRpiHost(state.baseUrl)
        state = state.copy(system = res, connectedHost = state.baseUrl, log = "System: ${res.hostname}")
    }

    fun loadNetworkMode(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().networkMode()
        rememberRpiHost(state.baseUrl)
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

    fun rebootRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().rebootSystem()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Reboot: ${res.status} ($msg)")
    }

    fun poweroffRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().poweroffSystem()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Poweroff: ${res.status} ($msg)")
    }

    fun restartServer(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().restartServer()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Server restart: ${res.status} ($msg)")
    }

    fun loadImages(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = state.imagesPage, pageSize = state.imagesPageSize)
        rememberRpiHost(state.baseUrl)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(connectedHost = state.baseUrl, log = "Images loaded: ${res.items.size}/${res.total_items}")
    }

    fun refreshGalleryOnly(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = 1, pageSize = state.imagesPageSize)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(log = "Gallery refreshed")
    }

    fun refreshGallerySilently(onState: (MainUiState) -> Unit) {
        viewModelScope.launch {
            try {
                val res = api().images(page = state.imagesPage, pageSize = state.imagesPageSize)
                applyImagesResponse(res, state.selectedImageFilename)
            } catch (_: Exception) {
                // Keep silent: this refresh is best-effort and should not disturb UI flow.
            } finally {
                onState(state)
            }
        }
    }

    fun loadImagesPage(page: Int, onState: (MainUiState) -> Unit) = runCall(onState) {
        val targetPage = page.coerceAtLeast(1)
        val res = api().images(page = targetPage, pageSize = state.imagesPageSize)
        rememberRpiHost(state.baseUrl)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(connectedHost = state.baseUrl, log = "Images page ${res.page}/${res.total_pages}")
    }

    fun nextImagesPage(onState: (MainUiState) -> Unit) {
        if (state.imagesPage >= state.imagesTotalPages) {
            onState(state.copy(log = "Already at last page"))
            return
        }
        loadImagesPage(state.imagesPage + 1, onState)
    }

    fun prevImagesPage(onState: (MainUiState) -> Unit) {
        if (state.imagesPage <= 1) {
            onState(state.copy(log = "Already at first page"))
            return
        }
        loadImagesPage(state.imagesPage - 1, onState)
    }

    fun oneShotRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: CaptureResponse = api().oneShot()
        val imagesRes = api().images(page = 1, pageSize = state.imagesPageSize)
        val preferred = res.latest_filename ?: imagesRes.items.firstOrNull()?.filename
        rememberRpiHost(state.baseUrl)
        applyImagesResponse(imagesRes, preferred)
        state = state.copy(
            connectedHost = state.baseUrl,
            viewerImageUrl = preferred?.let { "${state.baseUrl}/static/uploads/images/$it" },
            viewerImageBytes = null,
            viewerTitle = preferred ?: "RPi capture",
            log = "Capture: ${res.status} ${res.latest_filename ?: ""}".trim()
        )
    }

    fun startAutoCapture(onState: (MainUiState) -> Unit, intervalSeconds: Int = 300) = runCall(onState) {
        val res: CaptureLoopStatusResponse = api().startCaptureLoop(CaptureLoopStartRequest(interval_seconds = intervalSeconds))
        val stateText = if (res.running) "running" else "stopped"
        state = state.copy(
            autoCaptureRunning = res.running,
            autoCaptureIntervalSeconds = res.interval_seconds,
            autoCaptureStartedAtTs = if (res.running) res.started_at_ts else null,
            selectedAutoCaptureIntervalSeconds = intervalSeconds,
            log = "Auto capture: ${res.status} ($stateText, ${res.interval_seconds ?: "-"}s)"
        )
    }

    fun stopAutoCapture(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: CaptureLoopStatusResponse = api().stopCaptureLoop()
        val stateText = if (res.running) "running" else "stopped"
        state = state.copy(
            autoCaptureRunning = res.running,
            autoCaptureIntervalSeconds = res.interval_seconds,
            autoCaptureStartedAtTs = if (res.running) res.started_at_ts else null,
            log = "Auto capture: ${res.status} ($stateText)"
        )
    }

    fun loadAutoCaptureStatus(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: CaptureLoopStatusResponse = api().captureLoopStatus()
        val stateText = if (res.running) "running" else "stopped"
        state = state.copy(
            autoCaptureRunning = res.running,
            autoCaptureIntervalSeconds = res.interval_seconds,
            autoCaptureStartedAtTs = if (res.running) res.started_at_ts else null,
            log = "Auto capture: $stateText ${res.interval_seconds?.let { "(${it}s)" } ?: ""}".trim()
        )
    }

    fun setAutoCaptureInterval(intervalSeconds: Int) {
        val allowed = setOf(60, 120, 180, 300, 600)
        if (intervalSeconds !in allowed) return
        state = state.copy(selectedAutoCaptureIntervalSeconds = intervalSeconds)
    }

    fun oneShotEsp(onState: (MainUiState) -> Unit) = runCall(onState) {
        val espBase = state.selectedEspBaseUrl
        if (espBase.isNullOrBlank()) {
            state = state.copy(log = "ESP not selected/found")
            return@runCall
        }

        val rpiBase = state.discoveredRpiBaseUrls.firstOrNull()
            ?: state.connectedHost
            ?: state.baseUrl
        rememberRpiHost(rpiBase)
        rememberEspHost(espBase)

        val debugCmd = buildEspProxyDebugCommand(rpiBase, espBase)
        Log.i(logTag, "OneShot ESP request: $debugCmd")
        val (jpegBytes, err) = withContext(Dispatchers.IO) {
            captureEspJpegViaRpiWithError(rpiBase, espBase)
        }
        state = if (jpegBytes != null) {
            Log.i(logTag, "OneShot ESP success bytes=${jpegBytes.size}")
            val imagesRes = api().images(page = 1, pageSize = state.imagesPageSize)
            val latest = imagesRes.items.firstOrNull()?.filename
            applyImagesResponse(imagesRes, latest)
            state.copy(
                selectedEspBaseUrl = espBase,
                viewerImageUrl = null,
                viewerImageBytes = jpegBytes,
                viewerTitle = "ESP capture",
                log = "ESP capture: success (via ${rpiBase.removePrefix("http://")}, esp ${espBase.removePrefix("http://")})"
            )
        } else {
            Log.e(logTag, "OneShot ESP failed err=${err ?: "unknown"} cmd=$debugCmd")
            state.copy(
                log = if (err.isNullOrBlank()) {
                    "ESP capture: failed (via ${rpiBase.removePrefix("http://")})"
                } else {
                    "ESP capture: failed (via ${rpiBase.removePrefix("http://")}) $err"
                }
            )
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

    fun deleteImage(filename: String, onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: DeleteImageResponse = api().deleteImage(DeleteImageRequest(filename))
        val imagesRes = api().images(page = state.imagesPage, pageSize = state.imagesPageSize)
        val nextSelected = imagesRes.items.firstOrNull()?.filename
        applyImagesResponse(imagesRes, nextSelected)
        state = state.copy(
            viewerImageUrl = null,
            viewerImageBytes = null,
            viewerTitle = null,
            log = "Delete ${filename}: ${res.status}"
        )
    }

    fun deleteAllImages(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().deleteAllImages()
        val imagesRes = api().images(page = 1, pageSize = state.imagesPageSize)
        applyImagesResponse(imagesRes, null)
        state = state.copy(
            viewerImageUrl = null,
            viewerImageBytes = null,
            viewerTitle = null,
            log = "Delete all: ${res.status}"
        )
    }

    fun closeViewer() {
        state = state.copy(viewerImageUrl = null, viewerImageBytes = null, viewerTitle = null)
    }

    fun selectEspHost(host: String) {
        val normalized = normalizeBase(host, withPort5000 = false)
        state = state.copy(selectedEspBaseUrl = normalized)
        rememberEspHost(normalized)
    }

    fun discoverLan(onState: (MainUiState) -> Unit) = runCall(onState) {
        val detectedPrefix = detectCurrentLanPrefix()
        val subnetPrefix = extractSubnetPrefix(state.baseUrl)
        val targetPrefix = detectedPrefix ?: when (state.network?.mode) {
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
        val rememberedRpiCandidates = loadHosts(prefRpiHosts)
            .map { normalizeBase(it, withPort5000 = true) }
            .filter { matchesTargetPrefix(it, targetPrefix) || it.contains("raspberrypi.local") }
        val quickRpiCandidates = (rememberedRpiCandidates + directRpiCandidates)
            .map { normalizeBase(it, withPort5000 = true) }
            .distinct()

        val quickRpiFound = withContext(Dispatchers.IO) {
            quickRpiCandidates
                .distinct()
                .map { candidate ->
                    async {
                        if (isAgriAppUp(candidate)) candidate else null
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val foundEspFinal = if (targetPrefix != "192.168.4.") {
            emptyList()
        } else {
            val directEspCandidates = listOf(
                state.selectedEspBaseUrl,
                "http://192.168.4.2",
            ).filterNotNull().distinct()
            val rememberedEspCandidates = loadHosts(prefEspHosts)
                .map { normalizeBase(it, withPort5000 = false) }
                .filter { matchesTargetPrefix(it, targetPrefix) }
            val quickEspCandidates = (rememberedEspCandidates + directEspCandidates)
                .map { normalizeBase(it, withPort5000 = false) }
                .distinct()

            val quickEspFound = withContext(Dispatchers.IO) {
                quickEspCandidates
                    .map { candidate ->
                        async {
                            if (isEspCamUp(candidate)) candidate else null
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }

            val quickAnyFound = quickRpiFound.isNotEmpty() || quickEspFound.isNotEmpty()
            val foundEsp = if (quickAnyFound) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    (1..254).chunked(24).flatMap { chunk ->
                        chunk.map { host ->
                            async {
                                val base = "http://${targetPrefix}${host}"
                                if (isEspCamUp(base)) base else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }

            val merged = (quickEspFound + foundEsp).distinct().sorted()
            if (merged.isNotEmpty()) {
                saveHosts(prefEspHosts, mergeRecent(merged, rememberedEspCandidates))
            }
            merged
        }

        val quickAnyFound = quickRpiFound.isNotEmpty() || foundEspFinal.isNotEmpty()
        val scannedRpi = if (quickAnyFound) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                (1..254).chunked(24).flatMap { chunk ->
                    chunk.map { host ->
                        async {
                            val base = "http://${targetPrefix}${host}:5000"
                            if (isAgriAppUp(base)) base else null
                        }
                    }.awaitAll().filterNotNull()
                }
            }
        }
        val foundRpi = (quickRpiFound + scannedRpi).distinct().sorted()
        if (foundRpi.isNotEmpty()) {
            saveHosts(prefRpiHosts, mergeRecent(foundRpi, rememberedRpiCandidates))
        }

        val updatedBase = foundRpi.firstOrNull() ?: state.baseUrl
        state = state.copy(
            baseUrl = updatedBase,
            connectedHost = foundRpi.firstOrNull(),
            discoveredRpiBaseUrls = foundRpi,
            discoveredEspBaseUrls = foundEspFinal,
            selectedEspBaseUrl = foundEspFinal.firstOrNull(),
            log = if (quickAnyFound) {
                "Discovery (quick): RPi=${foundRpi.size}, ESP=${foundEspFinal.size}"
            } else {
                "Discovery (deep): RPi=${foundRpi.size}, ESP=${foundEspFinal.size}"
            }
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
        val input = raw.trim().ifBlank { "http://raspberrypi.local" }
        val normalizedInput = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else {
            "http://$input"
        }

        return try {
            val url = URL(normalizedInput)
            val scheme = url.protocol.ifBlank { "http" }
            val host = url.host.ifBlank { "raspberrypi.local" }
            val port = when {
                url.port > 0 -> url.port
                withPort5000 -> 5000
                scheme == "https" -> 443
                else -> 80
            }
            "$scheme://$host:$port"
        } catch (_: Exception) {
            val host = normalizedInput
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .substringBefore(":")
                .ifBlank { "raspberrypi.local" }
            if (withPort5000) "http://$host:5000" else "http://$host"
        }
    }

    private fun detectCurrentLanPrefix(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val preferred = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { iface ->
                    when {
                        iface.name.startsWith("wlan", ignoreCase = true) -> 0
                        iface.name.startsWith("eth", ignoreCase = true) -> 1
                        else -> 2
                    }
                }
            preferred.forEach { iface ->
                iface.inetAddresses.toList().forEach { address ->
                    val ipv4 = address as? Inet4Address ?: return@forEach
                    if (!ipv4.isSiteLocalAddress || ipv4.isLoopbackAddress) return@forEach
                    val parts = ipv4.hostAddress?.split(".") ?: return@forEach
                    if (parts.size == 4) {
                        return "${parts[0]}.${parts[1]}.${parts[2]}."
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
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
                connectTimeout = 1000
                readTimeout = 2000
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
                connectTimeout = 1200
                readTimeout = 4000
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun captureEspJpegViaRpiWithError(rpiBaseRaw: String, espBase: String): Pair<ByteArray?, String?> {
        return try {
            val rpiBase = normalizeBase(rpiBaseRaw, withPort5000 = true)
            val enc = URLEncoder.encode(espBase, Charsets.UTF_8.name())
            val url = URL("${rpiBase}/api/v1/capture/esp/oneshot?esp_base=$enc")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 30000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(160)
                Log.e(logTag, "Proxy HTTP error code=$code body=${errBody ?: ""}")
                return Pair(null, "HTTP $code ${errBody ?: ""}".trim())
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            val isJpeg = bytes.size > 4 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[bytes.size - 2] == 0xFF.toByte() &&
                bytes[bytes.size - 1] == 0xD9.toByte()
            if (isJpeg) Pair(bytes, null) else {
                Log.e(logTag, "Proxy returned non-JPEG payload size=${bytes.size}")
                Pair(null, "invalid jpeg")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Proxy network exception", e)
            Pair(null, e.message ?: "network error")
        }
    }

    private fun buildEspProxyDebugCommand(rpiBaseRaw: String, espBase: String): String {
        val rpiBase = normalizeBase(rpiBaseRaw, withPort5000 = true)
        val enc = URLEncoder.encode(espBase, Charsets.UTF_8.name())
        return "GET ${rpiBase}/api/v1/capture/esp/oneshot?esp_base=$enc"
    }

    private fun applyImagesResponse(res: ImagesResponse, preferredSelected: String?) {
        state = state.copy(
            images = res.items,
            imagesPage = res.page,
            imagesTotalPages = res.total_pages,
            imagesTotalItems = res.total_items,
            selectedImageFilename = preferredSelected ?: res.items.firstOrNull()?.filename
        )
    }

    private fun loadHosts(key: String): List<String> {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
    }

    private fun saveHosts(key: String, hosts: List<String>) {
        val payload = hosts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .joinToString(",")
        prefs.edit().putString(key, payload).apply()
    }

    private fun rememberRpiHost(base: String) {
        val normalized = normalizeBase(base, withPort5000 = true)
        val merged = mergeRecent(listOf(normalized), loadHosts(prefRpiHosts))
        saveHosts(prefRpiHosts, merged)
    }

    private fun rememberEspHost(base: String) {
        val normalized = normalizeBase(base, withPort5000 = false)
        val merged = mergeRecent(listOf(normalized), loadHosts(prefEspHosts))
        saveHosts(prefEspHosts, merged)
    }

    private fun mergeRecent(primary: List<String>, fallback: List<String>): List<String> {
        return (primary + fallback)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
    }

    private fun matchesTargetPrefix(base: String, targetPrefix: String): Boolean {
        return try {
            val normalized = if (base.startsWith("http://") || base.startsWith("https://")) {
                base
            } else {
                "http://$base"
            }
            val host = URL(normalized).host ?: return false
            host.startsWith(targetPrefix)
        } catch (_: Exception) {
            false
        }
    }
}
