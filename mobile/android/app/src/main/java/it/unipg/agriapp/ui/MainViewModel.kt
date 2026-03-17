package it.unipg.agriapp.ui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
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
import it.unipg.agriapp.data.EspCaptureLoopStartRequest
import it.unipg.agriapp.data.HealthResponse
import it.unipg.agriapp.data.ImageItem
import it.unipg.agriapp.data.ImagesResponse
import it.unipg.agriapp.data.NetworkModeRequest
import it.unipg.agriapp.data.NetworkModeResponse
import it.unipg.agriapp.data.SystemStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class EspProxyCaptureResult(
    val jpegBytes: ByteArray?,
    val error: String?,
    val savedFilename: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val pressure: Double? = null,
)

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
    val rpiLatencyMs: Long? = null,
    val espLatencyMs: Long? = null,
    val rpiLastSeenTs: Long? = null,
    val espLastSeenTs: Long? = null,
    val localSsid: String? = null,
    val localSubnetPrefix: String? = null,
    val localIp: String? = null,
    val knownWifiConnections: List<String> = emptyList(),
    val preferredWifiConnection: String? = null,
    val selectedEspBaseUrl: String? = null,
    val viewerImageUrl: String? = null,
    val viewerImageBytes: ByteArray? = null,
    val viewerTitle: String? = null,
    val autoCaptureRpiRunning: Boolean? = null,
    val autoCaptureRpiIntervalSeconds: Int? = null,
    val autoCaptureRpiStartedAtTs: Long? = null,
    val autoCaptureEspRunning: Boolean? = null,
    val autoCaptureEspIntervalSeconds: Int? = null,
    val autoCaptureEspStartedAtTs: Long? = null,
    val selectedAutoCaptureIntervalSeconds: Int = 300,
    val selectedAutoCaptureSource: String = "rpi",
    val autoDiscoverNeeded: Boolean = false,
    val autoDiscoverToken: Long = 0L,
    val log: String = "Ready",
    val busy: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val logTag = "AgriAppESP"
    private val prefs = application.getSharedPreferences("agriapp_hosts", Context.MODE_PRIVATE)
    private val prefRpiHosts = "rpi_hosts"
    private val prefEspHosts = "esp_hosts"
    private val prefWifiProfiles = "wifi_profiles"
    private val prefPreferredWifi = "preferred_wifi"
    private val defaultWifiProfiles = listOf("preconfigured", "hotspot", "castelnuovo")
    private var lastKnownNewestFilename: String? = null
    private var lastAutoRpiRunning: Boolean? = null
    private var lastAutoEspRunning: Boolean? = null
    private var lastAutoStopRequestedAtMs: Long = 0L
    var state: MainUiState = MainUiState()
        private set

    init {
        val known = mergeRecent(loadHosts(prefWifiProfiles), defaultWifiProfiles)
        val preferred = prefs.getString(prefPreferredWifi, null)?.trim()?.ifBlank { null }
        state = state.copy(
            knownWifiConnections = known,
            preferredWifiConnection = preferred ?: known.firstOrNull()
        )
        saveHosts(prefWifiProfiles, known)
        refreshLocalNetworkInfo()
    }

    fun updateBaseUrl(value: String) {
        val normalized = normalizeBase(value, withPort5000 = true)
        val old = state.baseUrl
        state = state.copy(
            baseUrl = normalized,
            log = if (old == normalized) "Host selected: ${normalized.removePrefix("http://")}" else "Host changed: ${old.removePrefix("http://")} -> ${normalized.removePrefix("http://")}"
        )
        rememberRpiHost(normalized)
    }

    fun note(message: String) {
        state = state.copy(log = message)
    }

    fun checkHealth(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().health()
        val host = state.baseUrl.removePrefix("http://")
        state = state.copy(health = res, connectedHost = state.baseUrl, log = "Health ${res.status} on $host")
    }

    fun loadSystem(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().systemStatus()
        rememberRpiHost(state.baseUrl)
        val freeMb = res.disk_free_bytes / (1024 * 1024)
        state = state.copy(system = res, connectedHost = state.baseUrl, log = "System ${res.hostname}: free ${freeMb} MB")
    }

    fun loadNetworkMode(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().networkMode()
        rememberRpiHost(state.baseUrl)
        val ap = if (res.ap_active) "AP on" else "AP off"
        val wifi = if (res.client_active) "WiFi on" else "WiFi off"
        val activeClientConn = res.active_wifi_connections.firstOrNull { it != res.ap_connection }
        val mergedWifi = mergeRecent(
            listOfNotNull(state.preferredWifiConnection, activeClientConn, res.wifi_connection),
            state.knownWifiConnections
        )
        val preferred = state.preferredWifiConnection ?: activeClientConn ?: res.wifi_connection ?: mergedWifi.firstOrNull()
        saveHosts(prefWifiProfiles, mergedWifi)
        if (!preferred.isNullOrBlank()) {
            prefs.edit().putString(prefPreferredWifi, preferred).apply()
        }
        state = state.copy(network = res, connectedHost = state.baseUrl, log = "Network ${res.mode} ($ap, $wifi)")
            .copy(
                knownWifiConnections = mergedWifi,
                preferredWifiConnection = preferred
            )
    }

    fun refreshSystemAndNetworkSilently(onState: (MainUiState) -> Unit) {
        viewModelScope.launch {
            try {
                refreshLocalNetworkInfo()
                val sys = api().systemStatus()
                val net = api().networkMode()
                rememberRpiHost(state.baseUrl)
                state = state.copy(
                    system = sys,
                    network = net,
                    connectedHost = state.baseUrl
                )
                val activeClientConn = net.active_wifi_connections.firstOrNull { it != net.ap_connection }
                val mergedWifi = mergeRecent(
                    listOfNotNull(state.preferredWifiConnection, activeClientConn, net.wifi_connection),
                    state.knownWifiConnections
                )
                val preferred = state.preferredWifiConnection ?: activeClientConn ?: net.wifi_connection ?: mergedWifi.firstOrNull()
                saveHosts(prefWifiProfiles, mergedWifi)
                if (!preferred.isNullOrBlank()) {
                    prefs.edit().putString(prefPreferredWifi, preferred).apply()
                }
                state = state.copy(
                    knownWifiConnections = mergedWifi,
                    preferredWifiConnection = preferred
                )
            } catch (_: Exception) {
                // Best-effort silent refresh.
            } finally {
                onState(state)
            }
        }
    }

    fun setNetworkMode(mode: String, onState: (MainUiState) -> Unit) = runCall(onState) {
        val expectedBase = when (mode) {
            "ap_only" -> "http://192.168.4.1:5000"
            "wifi_only" -> "http://raspberrypi.local:5000"
            else -> state.baseUrl
        }
        state = state.copy(log = "Switching network mode to ${mode}...")
        try {
            val req = if (mode == "wifi_only") {
                NetworkModeRequest(mode = mode, wifi_connection = state.preferredWifiConnection)
            } else {
                NetworkModeRequest(mode = mode)
            }
            val res = api().setNetworkMode(req)
            state = state.copy(
                baseUrl = expectedBase,
                network = res,
                log = if (res.status == "success") "Network mode set: ${res.mode}. Reconnecting to ${expectedBase.removePrefix("http://")}..." else "Network mode failed"
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val netSwitchLikely = msg.contains("software caused connection abort") ||
                msg.contains("failed to connect") ||
                msg.contains("timeout")
            if (netSwitchLikely) {
                state = state.copy(
                    baseUrl = expectedBase,
                    log = "Network switch in progress. Link drop expected; waiting for reconnect..."
                )
            } else {
                throw e
            }
        }

        rememberRpiHost(expectedBase)

        var connected = false
        for (attempt in 1..20) {
            if (withContext(Dispatchers.IO) { isAgriAppUp(expectedBase) }) {
                connected = true
                break
            }
            delay(1000)
        }

        if (connected) {
            val apiOnExpected = ApiClient.create(expectedBase)
            val net = try { apiOnExpected.networkMode() } catch (_: Exception) { null }
            val sys = try { apiOnExpected.systemStatus() } catch (_: Exception) { null }
            state = state.copy(
                baseUrl = expectedBase,
                connectedHost = expectedBase,
                network = net ?: state.network,
                system = sys ?: state.system,
                autoDiscoverNeeded = true,
                autoDiscoverToken = System.currentTimeMillis(),
                log = "Network switched. Connected to ${expectedBase.removePrefix("http://")} (${net?.mode ?: "mode n/a"})"
            )
        } else {
            state = state.copy(
                baseUrl = expectedBase,
                connectedHost = null,
                autoDiscoverNeeded = true,
                autoDiscoverToken = System.currentTimeMillis(),
                log = "Network switch requested. Host changed subnet; auto-discover pending."
            )
        }
    }

    fun rebootRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().rebootSystem()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Reboot ${res.status}: $msg ${formatActionTs(res.timestamp)}".trim())
    }

    fun poweroffRpi(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().poweroffSystem()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Poweroff ${res.status}: $msg ${formatActionTs(res.timestamp)}".trim())
    }

    fun restartServer(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: ActionResponse = api().restartServer()
        val msg = res.message ?: "requested"
        state = state.copy(log = "Server restart ${res.status}: $msg ${formatActionTs(res.timestamp)}".trim())
    }

    fun loadImages(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = state.imagesPage, pageSize = state.imagesPageSize)
        rememberRpiHost(state.baseUrl)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(
            connectedHost = state.baseUrl,
            log = "Gallery page ${res.page}/${res.total_pages}: ${res.items.size} items (${res.total_items} total)"
        )
    }

    fun refreshGalleryOnly(onState: (MainUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = 1, pageSize = state.imagesPageSize)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(log = "Gallery refreshed: ${res.total_items} items")
    }

    fun refreshGallerySilently(onState: (MainUiState) -> Unit) {
        viewModelScope.launch {
            try {
                val autoRunning = (state.autoCaptureRpiRunning == true) || (state.autoCaptureEspRunning == true)
                val headRes = if (autoRunning) {
                    api().images(page = 1, pageSize = 1)
                } else {
                    null
                }
                val headItem = headRes?.items?.firstOrNull()
                val newest = headItem?.filename
                if (autoRunning && newest != null && newest != lastKnownNewestFilename) {
                    lastKnownNewestFilename = newest
                    val w = headItem.image_width
                    val h = headItem.image_height
                    val dim = if (w != null && h != null) " (${w}x${h})" else ""
                    val lowQxga = newest.startsWith("esp_") && w != null && h != null && (w < 2048 || h < 1536)
                    state = state.copy(log = "Photo captured: $newest$dim${if (lowQxga) " [WARN below QXGA]" else ""}")
                }
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
        val capturedItem = imagesRes.items.firstOrNull { it.filename == preferred } ?: imagesRes.items.firstOrNull()
        rememberRpiHost(state.baseUrl)
        applyImagesResponse(imagesRes, preferred)
        state = state.copy(
            connectedHost = state.baseUrl,
            viewerImageUrl = null,
            viewerImageBytes = null,
            viewerTitle = null,
            log = "Shot RPi ${res.status}: ${res.latest_filename ?: "no filename"}"
        )
    }

    fun startAutoCapture(onState: (MainUiState) -> Unit, source: String, intervalSeconds: Int = 300) = runCall(onState) {
        if (source == "both") {
            var rpiOk = false
            var espOk = false
            var rpiMsg = ""
            var espMsg = ""

            try {
                val rpiRes: CaptureLoopStatusResponse = api().startCaptureLoop(CaptureLoopStartRequest(interval_seconds = intervalSeconds))
                rpiOk = rpiRes.running
                state = state.copy(
                    autoCaptureRpiRunning = rpiRes.running,
                    autoCaptureRpiIntervalSeconds = rpiRes.interval_seconds,
                    autoCaptureRpiStartedAtTs = if (rpiRes.running) rpiRes.started_at_ts else null
                )
                rpiMsg = if (rpiRes.running) "running" else (rpiRes.message ?: "stopped")
            } catch (e: Exception) {
                rpiMsg = e.message ?: "error"
            }

            val espBase = state.selectedEspBaseUrl
            if (!espBase.isNullOrBlank()) {
                try {
                    val espRes: CaptureLoopStatusResponse = api().startEspCaptureLoop(
                        EspCaptureLoopStartRequest(interval_seconds = intervalSeconds, esp_base = espBase)
                    )
                    espOk = espRes.running
                    state = state.copy(
                        autoCaptureEspRunning = espRes.running,
                        autoCaptureEspIntervalSeconds = espRes.interval_seconds,
                        autoCaptureEspStartedAtTs = if (espRes.running) espRes.started_at_ts else null
                    )
                    espMsg = if (espRes.running) "running" else (espRes.message ?: "stopped")
                } catch (e: Exception) {
                    espMsg = e.message ?: "error"
                }
            } else {
                espMsg = "ESP not selected/found"
            }

            state = state.copy(
                selectedAutoCaptureSource = "both",
                selectedAutoCaptureIntervalSeconds = intervalSeconds,
                log = "Auto BOTH start ${intervalSeconds}s -> RPi=$rpiMsg, ESP=$espMsg"
            )
            return@runCall
        }

        if (source == "esp") {
            val espBase = state.selectedEspBaseUrl
            if (espBase.isNullOrBlank()) {
                state = state.copy(log = "ESP not selected/found")
                return@runCall
            }
            val res: CaptureLoopStatusResponse = api().startEspCaptureLoop(
                EspCaptureLoopStartRequest(interval_seconds = intervalSeconds, esp_base = espBase)
            )
            val stateText = if (res.running) "running" else "stopped"
            state = state.copy(
                autoCaptureEspRunning = res.running,
                autoCaptureEspIntervalSeconds = res.interval_seconds,
                autoCaptureEspStartedAtTs = if (res.running) res.started_at_ts else null,
                selectedAutoCaptureSource = "esp",
                selectedAutoCaptureIntervalSeconds = intervalSeconds,
                log = "Auto ESP start ${res.interval_seconds ?: intervalSeconds}s: $stateText"
            )
            return@runCall
        }

        val res: CaptureLoopStatusResponse = api().startCaptureLoop(CaptureLoopStartRequest(interval_seconds = intervalSeconds))
        val stateText = if (res.running) "running" else "stopped"
        state = state.copy(
            autoCaptureRpiRunning = res.running,
            autoCaptureRpiIntervalSeconds = res.interval_seconds,
            autoCaptureRpiStartedAtTs = if (res.running) res.started_at_ts else null,
            selectedAutoCaptureSource = "rpi",
            selectedAutoCaptureIntervalSeconds = intervalSeconds,
            log = "Auto RPi start ${res.interval_seconds ?: intervalSeconds}s: $stateText"
        )
    }

    fun stopAutoCapture(onState: (MainUiState) -> Unit, source: String) = runCall(onState) {
        lastAutoStopRequestedAtMs = System.currentTimeMillis()
        if (source == "both") {
            var rpiMsg = "stopped"
            var espMsg = "stopped"
            try {
                val rpiRes: CaptureLoopStatusResponse = api().stopCaptureLoop()
                state = state.copy(
                    autoCaptureRpiRunning = rpiRes.running,
                    autoCaptureRpiIntervalSeconds = rpiRes.interval_seconds,
                    autoCaptureRpiStartedAtTs = if (rpiRes.running) rpiRes.started_at_ts else null
                )
                rpiMsg = if (rpiRes.running) "running" else "stopped"
            } catch (e: Exception) {
                rpiMsg = e.message ?: "error"
            }
            try {
                val espRes: CaptureLoopStatusResponse = api().stopEspCaptureLoop()
                state = state.copy(
                    autoCaptureEspRunning = espRes.running,
                    autoCaptureEspIntervalSeconds = espRes.interval_seconds,
                    autoCaptureEspStartedAtTs = if (espRes.running) espRes.started_at_ts else null
                )
                espMsg = if (espRes.running) "running" else "stopped"
            } catch (e: Exception) {
                espMsg = e.message ?: "error"
            }
            state = state.copy(
                selectedAutoCaptureSource = "both",
                log = "Auto BOTH stop -> RPi=$rpiMsg, ESP=$espMsg"
            )
            return@runCall
        }

        if (source == "esp") {
            val res: CaptureLoopStatusResponse = api().stopEspCaptureLoop()
            val stateText = if (res.running) "running" else "stopped"
            state = state.copy(
                autoCaptureEspRunning = res.running,
                autoCaptureEspIntervalSeconds = res.interval_seconds,
                autoCaptureEspStartedAtTs = if (res.running) res.started_at_ts else null,
                selectedAutoCaptureSource = "esp",
                log = "Auto ESP stop: $stateText"
            )
            return@runCall
        }

        val res: CaptureLoopStatusResponse = api().stopCaptureLoop()
        val stateText = if (res.running) "running" else "stopped"
        state = state.copy(
            autoCaptureRpiRunning = res.running,
            autoCaptureRpiIntervalSeconds = res.interval_seconds,
            autoCaptureRpiStartedAtTs = if (res.running) res.started_at_ts else null,
            selectedAutoCaptureSource = "rpi",
            log = "Auto RPi stop: $stateText"
        )
    }

    fun loadAutoCaptureStatus(onState: (MainUiState) -> Unit) = runCall(onState) {
        val rpiRes = api().captureLoopStatus()
        val espRes = try {
            api().espCaptureLoopStatus()
        } catch (_: Exception) {
            null
        }
        val inferredSource = when {
            rpiRes.running && espRes?.running == true -> "both"
            espRes?.running == true -> "esp"
            else -> "rpi"
        }
        val inferredInterval = when (inferredSource) {
            "esp" -> espRes?.interval_seconds
            "both" -> rpiRes.interval_seconds ?: espRes?.interval_seconds
            else -> rpiRes.interval_seconds
        } ?: state.selectedAutoCaptureIntervalSeconds
        state = state.copy(
            autoCaptureRpiRunning = rpiRes.running,
            autoCaptureRpiIntervalSeconds = rpiRes.interval_seconds,
            autoCaptureRpiStartedAtTs = if (rpiRes.running) rpiRes.started_at_ts else null,
            autoCaptureEspRunning = espRes?.running,
            autoCaptureEspIntervalSeconds = espRes?.interval_seconds,
            autoCaptureEspStartedAtTs = if (espRes?.running == true) espRes.started_at_ts else null,
            selectedAutoCaptureSource = inferredSource,
            selectedAutoCaptureIntervalSeconds = inferredInterval
        )

        val stopWasExpected = (System.currentTimeMillis() - lastAutoStopRequestedAtMs) < 20_000
        if (!stopWasExpected) {
            val rpiUnexpectedStop = (lastAutoRpiRunning == true && !rpiRes.running)
            val espUnexpectedStop = (lastAutoEspRunning == true && espRes?.running == false)
            if (rpiUnexpectedStop || espUnexpectedStop) {
                val msg = buildString {
                    append("Auto loop changed unexpectedly:")
                    if (rpiUnexpectedStop) append(" RPi stopped")
                    if (espUnexpectedStop) append(" ESP stopped")
                }
                state = state.copy(log = msg)
            }
        }
        lastAutoRpiRunning = rpiRes.running
        lastAutoEspRunning = espRes?.running
    }

    fun loadAutoCaptureStatusSilently(onState: (MainUiState) -> Unit) {
        viewModelScope.launch {
            try {
                val rpiRes = api().captureLoopStatus()
                val espRes = try {
                    api().espCaptureLoopStatus()
                } catch (_: Exception) {
                    null
                }
                val inferredSource = when {
                    rpiRes.running && espRes?.running == true -> "both"
                    espRes?.running == true -> "esp"
                    else -> "rpi"
                }
                val inferredInterval = when (inferredSource) {
                    "esp" -> espRes?.interval_seconds
                    "both" -> rpiRes.interval_seconds ?: espRes?.interval_seconds
                    else -> rpiRes.interval_seconds
                } ?: state.selectedAutoCaptureIntervalSeconds
                state = state.copy(
                    autoCaptureRpiRunning = rpiRes.running,
                    autoCaptureRpiIntervalSeconds = rpiRes.interval_seconds,
                    autoCaptureRpiStartedAtTs = if (rpiRes.running) rpiRes.started_at_ts else null,
                    autoCaptureEspRunning = espRes?.running,
                    autoCaptureEspIntervalSeconds = espRes?.interval_seconds,
                    autoCaptureEspStartedAtTs = if (espRes?.running == true) espRes.started_at_ts else null,
                    selectedAutoCaptureSource = inferredSource,
                    selectedAutoCaptureIntervalSeconds = inferredInterval
                )

                val stopWasExpected = (System.currentTimeMillis() - lastAutoStopRequestedAtMs) < 20_000
                if (!stopWasExpected) {
                    val rpiUnexpectedStop = (lastAutoRpiRunning == true && !rpiRes.running)
                    val espUnexpectedStop = (lastAutoEspRunning == true && espRes?.running == false)
                    if (rpiUnexpectedStop || espUnexpectedStop) {
                        val msg = buildString {
                            append("Auto loop changed unexpectedly:")
                            if (rpiUnexpectedStop) append(" RPi stopped")
                            if (espUnexpectedStop) append(" ESP stopped")
                        }
                        state = state.copy(log = msg)
                    }
                }
                lastAutoRpiRunning = rpiRes.running
                lastAutoEspRunning = espRes?.running
            } catch (_: Exception) {
                // Best-effort silent refresh.
            } finally {
                onState(state)
            }
        }
    }

    fun setAutoCaptureInterval(intervalSeconds: Int) {
        val allowed = setOf(30, 60, 120, 180, 300)
        if (intervalSeconds !in allowed) return
        state = state.copy(selectedAutoCaptureIntervalSeconds = intervalSeconds)
    }

    fun setAutoCaptureSource(source: String) {
        if (source != "rpi" && source != "esp" && source != "both") return
        state = state.copy(selectedAutoCaptureSource = source)
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
        val espResult = withContext(Dispatchers.IO) {
            captureEspJpegViaRpiWithError(rpiBase, espBase)
        }
        state = if (espResult.jpegBytes != null) {
            Log.i(logTag, "OneShot ESP success bytes=${espResult.jpegBytes.size}")
            val now = System.currentTimeMillis()
            val imagesRes = api().images(page = 1, pageSize = state.imagesPageSize)
            val latest = espResult.savedFilename ?: imagesRes.items.firstOrNull()?.filename
            val capturedItem = imagesRes.items.firstOrNull { it.filename == latest } ?: imagesRes.items.firstOrNull()
            applyImagesResponse(imagesRes, latest)
            val dimText = decodeImageDimensions(espResult.jpegBytes)?.let { " ${it.first}x${it.second}" } ?: ""
            val lowQxga = decodeImageDimensions(espResult.jpegBytes)?.let { it.first < 2048 || it.second < 1536 } ?: false
            state.copy(
                selectedEspBaseUrl = espBase,
                espLastSeenTs = now,
                viewerImageUrl = null,
                viewerImageBytes = null,
                viewerTitle = null,
                log = "ESP capture: success ${latest ?: ""}$dimText${if (lowQxga) " [WARN below QXGA]" else ""} (via ${rpiBase.removePrefix("http://")}, esp ${espBase.removePrefix("http://")})".trim()
            )
        } else {
            val err = espResult.error
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
            viewerTitle = filename,
            log = "Open image: $filename"
        )
    }

    fun openAdjacentImage(delta: Int) {
        val current = state.selectedImageFilename ?: return
        val idx = state.images.indexOfFirst { it.filename == current }
        if (idx < 0) return
        val target = idx + delta
        if (target !in state.images.indices) return
        val next = state.images[target].filename
        state = state.copy(
            selectedImageFilename = next,
            viewerImageUrl = "${state.baseUrl}/static/uploads/images/$next",
            viewerImageBytes = null,
            viewerTitle = next,
            log = if (delta < 0) "Viewer prev: $next" else "Viewer next: $next"
        )
    }

    fun deleteImage(filename: String, onState: (MainUiState) -> Unit) = runCall(onState) {
        val res: DeleteImageResponse = api().deleteImage(DeleteImageRequest(filename))
        val imagesRes = api().images(page = state.imagesPage, pageSize = state.imagesPageSize)
        val nextSelected = imagesRes.items.firstOrNull()?.filename
        applyImagesResponse(imagesRes, nextSelected)
        val removedSummary = res.removed?.entries
            ?.filter { it.value }
            ?.joinToString(",") { it.key }
            ?: "none"
        state = state.copy(
            viewerImageUrl = null,
            viewerImageBytes = null,
            viewerTitle = null,
            log = "Delete ${filename}: ${res.status} (removed: $removedSummary)"
        )
    }

    fun deleteAllImages(onState: (MainUiState) -> Unit) = runCall(onState) {
        val before = state.imagesTotalItems
        val res: ActionResponse = api().deleteAllImages()
        val imagesRes = api().images(page = 1, pageSize = state.imagesPageSize)
        applyImagesResponse(imagesRes, null)
        val removed = res.removed_images ?: (before - imagesRes.total_items).coerceAtLeast(0)
        state = state.copy(
            viewerImageUrl = null,
            viewerImageBytes = null,
            viewerTitle = null,
            log = "Delete all: ${res.status} (removed $removed, remaining ${imagesRes.total_items})"
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

    fun setPreferredWifiConnection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val merged = mergeRecent(listOf(trimmed), state.knownWifiConnections)
        saveHosts(prefWifiProfiles, merged)
        prefs.edit().putString(prefPreferredWifi, trimmed).apply()
        state = state.copy(
            knownWifiConnections = merged,
            preferredWifiConnection = trimmed,
            log = "Preferred WiFi set: $trimmed"
        )
    }

    fun discoverLan(onState: (MainUiState) -> Unit) = runCall(onState) {
        refreshLocalNetworkInfo()
        val detectedPrefix = detectCurrentLanPrefix()
        val subnetPrefix = extractSubnetPrefix(state.baseUrl)
        val rpiPrefix = detectedPrefix ?: subnetPrefix ?: "192.168.1."
        val espPrefix = "192.168.4."
        state = state.copy(
            log = "Discovery start: RPi on ${rpiPrefix}0/24, ESP on ${espPrefix}0/24",
            discoveredRpiBaseUrls = emptyList(),
            discoveredEspBaseUrls = emptyList(),
        )
        onState(state)

        val directRpiCandidates = listOf(
            normalizeBase(state.baseUrl, withPort5000 = true),
            "http://raspberrypi.local:5000",
            "http://${rpiPrefix}1:5000",
        )
        val rememberedRpiCandidates = loadHosts(prefRpiHosts)
            .map { normalizeBase(it, withPort5000 = true) }
            .filter { matchesTargetPrefix(it, rpiPrefix) || it.contains("raspberrypi.local") }
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

        val rememberedEspCandidates = loadHosts(prefEspHosts)
            .map { normalizeBase(it, withPort5000 = false) }
            .filter { matchesTargetPrefix(it, espPrefix) }
        val directEspCandidates = listOfNotNull(
            state.selectedEspBaseUrl,
            "http://192.168.4.2",
        )
            .map { normalizeBase(it, withPort5000 = false) }
            .filter { matchesTargetPrefix(it, espPrefix) }

        val quickEspCandidates = (rememberedEspCandidates + directEspCandidates)
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

        val foundEsp = if (quickEspFound.isNotEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                (1..254).chunked(24).flatMap { chunk ->
                    chunk.map { host ->
                        async {
                            val base = "http://${espPrefix}${host}"
                            if (isEspCamUp(base)) base else null
                        }
                    }.awaitAll().filterNotNull()
                }
            }
        }

        val foundEspFinal = (quickEspFound + foundEsp).distinct().sorted()
        if (foundEspFinal.isNotEmpty()) {
            saveHosts(prefEspHosts, mergeRecent(foundEspFinal, rememberedEspCandidates))
        }

        val quickAnyFound = quickRpiFound.isNotEmpty() || foundEspFinal.isNotEmpty()
        val scannedRpi = if (quickAnyFound) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                (1..254).chunked(24).flatMap { chunk ->
                    chunk.map { host ->
                        async {
                            val base = "http://${rpiPrefix}${host}:5000"
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

        val oldBase = state.baseUrl
        val updatedBase = foundRpi.firstOrNull() ?: state.baseUrl
        val now = System.currentTimeMillis()
        val rpiLatency = probeAgriAppLatency(updatedBase)
        val selectedEsp = foundEspFinal.firstOrNull()
        val espLatency = if (!selectedEsp.isNullOrBlank()) {
            probeEspLatency(normalizeBase(selectedEsp, withPort5000 = false))
        } else {
            null
        }
        state = state.copy(
            baseUrl = updatedBase,
            connectedHost = foundRpi.firstOrNull(),
            discoveredRpiBaseUrls = foundRpi,
            discoveredEspBaseUrls = foundEspFinal,
            selectedEspBaseUrl = foundEspFinal.firstOrNull(),
            rpiLatencyMs = rpiLatency,
            espLatencyMs = espLatency,
            rpiLastSeenTs = if (foundRpi.isNotEmpty()) now else state.rpiLastSeenTs,
            espLastSeenTs = if (foundEspFinal.isNotEmpty()) now else state.espLastSeenTs,
            autoDiscoverNeeded = false,
            log = "Discovery ${if (quickAnyFound) "quick" else "deep"} done: RPi=${foundRpi.size}, ESP=${foundEspFinal.size}, selected=${updatedBase.removePrefix("http://")}${if (updatedBase != oldBase) ", host changed" else ""}"
        )
    }

    fun testRpiConnection(onState: (MainUiState) -> Unit) = runCall(onState) {
        val target = normalizeBase(state.baseUrl, withPort5000 = true)
        val latency = probeAgriAppLatency(target)
        val now = System.currentTimeMillis()
        if (latency != null) {
            state = state.copy(
                rpiLatencyMs = latency,
                rpiLastSeenTs = now,
                log = "RPi test ok (${latency} ms)"
            )
        } else {
            state = state.copy(
                rpiLatencyMs = null,
                log = "RPi test failed"
            )
        }
    }

    fun testEspConnection(onState: (MainUiState) -> Unit) = runCall(onState) {
        val espBase = state.selectedEspBaseUrl
        if (espBase.isNullOrBlank()) {
            state = state.copy(log = "ESP not selected/found")
            return@runCall
        }
        val target = normalizeBase(espBase, withPort5000 = false)
        val latency = probeEspLatency(target)
        val now = System.currentTimeMillis()
        if (latency != null) {
            state = state.copy(
                espLatencyMs = latency,
                espLastSeenTs = now,
                log = "ESP test ok (${latency} ms)"
            )
        } else {
            state = state.copy(
                espLatencyMs = null,
                log = "ESP test failed"
            )
        }
    }

    fun consumeAutoDiscoverRequest() {
        if (!state.autoDiscoverNeeded) return
        state = state.copy(autoDiscoverNeeded = false)
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

    private fun refreshLocalNetworkInfo() {
        val prefix = detectCurrentLanPrefix()
        val ip = detectCurrentLocalIp()
        val ssid = detectCurrentSsid()
        state = state.copy(
            localSubnetPrefix = prefix,
            localIp = ip,
            localSsid = ssid
        )
    }

    private fun detectCurrentLocalIp(): String? {
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
                    val hostAddress = ipv4.hostAddress
                    if (!hostAddress.isNullOrBlank()) return hostAddress
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectCurrentSsid(): String? {
        return try {
            val wm = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val raw = wm?.connectionInfo?.ssid ?: return null
            val cleaned = raw.removePrefix("\"").removeSuffix("\"").trim()
            if (cleaned.isBlank() || cleaned.equals("<unknown ssid>", ignoreCase = true)) null else cleaned
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

    private fun probeAgriAppLatency(base: String): Long? {
        return try {
            val start = System.nanoTime()
            val url = URL("$base/api/v1/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1800
            }
            if (conn.responseCode == 200) {
                ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            } else null
        } catch (_: Exception) {
            null
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

    private fun probeEspLatency(base: String): Long? {
        val statusMs = try {
            val start = System.nanoTime()
            val url = URL("$base/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 2200
            }
            if (conn.responseCode == 200) ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L) else null
        } catch (_: Exception) {
            null
        }
        if (statusMs != null) return statusMs

        return try {
            val start = System.nanoTime()
            val url = URL("$base/capture")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 4500
            }
            if (conn.responseCode == 200) ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun captureEspJpegViaRpiWithError(rpiBaseRaw: String, espBase: String): EspProxyCaptureResult {
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
                return EspProxyCaptureResult(jpegBytes = null, error = "HTTP $code ${errBody ?: ""}".trim())
            }

            val savedFilename = conn.getHeaderField("X-AgriApp-Filename")
            val latitude = conn.getHeaderField("X-AgriApp-Latitude")?.toDoubleOrNull()
            val longitude = conn.getHeaderField("X-AgriApp-Longitude")?.toDoubleOrNull()
            val temperature = conn.getHeaderField("X-AgriApp-Temperature")?.toDoubleOrNull()
            val humidity = conn.getHeaderField("X-AgriApp-Humidity")?.toDoubleOrNull()
            val pressure = conn.getHeaderField("X-AgriApp-Pressure")?.toDoubleOrNull()
            val bytes = conn.inputStream.use { it.readBytes() }
            val isJpeg = bytes.size > 4 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[bytes.size - 2] == 0xFF.toByte() &&
                bytes[bytes.size - 1] == 0xD9.toByte()
            if (isJpeg) EspProxyCaptureResult(
                jpegBytes = bytes,
                error = null,
                savedFilename = savedFilename,
                latitude = latitude,
                longitude = longitude,
                temperature = temperature,
                humidity = humidity,
                pressure = pressure,
            ) else {
                Log.e(logTag, "Proxy returned non-JPEG payload size=${bytes.size}")
                EspProxyCaptureResult(jpegBytes = null, error = "invalid jpeg")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Proxy network exception", e)
            EspProxyCaptureResult(jpegBytes = null, error = e.message ?: "network error")
        }
    }

    private fun buildEspProxyDebugCommand(rpiBaseRaw: String, espBase: String): String {
        val rpiBase = normalizeBase(rpiBaseRaw, withPort5000 = true)
        val enc = URLEncoder.encode(espBase, Charsets.UTF_8.name())
        return "GET ${rpiBase}/api/v1/capture/esp/oneshot?esp_base=$enc"
    }

    private fun applyImagesResponse(res: ImagesResponse, preferredSelected: String?) {
        if (res.page == 1) {
            lastKnownNewestFilename = res.items.firstOrNull()?.filename
        }
        state = state.copy(
            images = res.items,
            imagesPage = res.page,
            imagesTotalPages = res.total_pages,
            imagesTotalItems = res.total_items,
            selectedImageFilename = preferredSelected ?: res.items.firstOrNull()?.filename
        )
    }

    private fun formatActionTs(ts: Long?): String {
        if (ts == null || ts <= 0L) return ""
        return try {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            "@${fmt.format(java.util.Date(ts * 1000))}"
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Exception) {
            null
        }
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
