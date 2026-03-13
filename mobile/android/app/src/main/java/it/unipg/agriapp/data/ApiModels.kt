package it.unipg.agriapp.data

data class HealthResponse(
    val service: String = "",
    val status: String = "",
    val version: String = ""
)

data class SystemStatusResponse(
    val status: String = "",
    val hostname: String = "",
    val images_dir: String = "",
    val disk_total_bytes: Long = 0,
    val disk_free_bytes: Long = 0,
    val timestamp: Long = 0
)

data class CaptureResponse(
    val status: String = "",
    val message: String? = null,
    val latest_filename: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val returncode: Int? = null
)

data class ImageItem(
    val filename: String = "",
    val upload_time: String = "",
    val is_labeled: Boolean = false,
    val labels_count: Int = 0
)

data class ImagesResponse(
    val items: List<ImageItem> = emptyList(),
    val page: Int = 1,
    val page_size: Int = 24,
    val total_items: Int = 0,
    val total_pages: Int = 1,
    val global_total: Int = 0,
    val global_labeled: Int = 0
)

data class NetworkModeRequest(
    val mode: String
)

data class NetworkModeResponse(
    val status: String = "",
    val mode: String = "",
    val applied_mode: String? = null,
    val ap_active: Boolean = false,
    val client_active: Boolean = false,
    val ap_connection: String? = null,
    val wifi_connection: String? = null,
    val active_wifi_connections: List<String> = emptyList(),
    val message: String? = null
)
