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
    val metadata: ImageMetadata? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val returncode: Int? = null
)

data class CaptureLoopStartRequest(
    val interval_seconds: Int = 300
)

data class EspCaptureLoopStartRequest(
    val interval_seconds: Int = 300,
    val esp_base: String
)

data class CaptureLoopStatusResponse(
    val status: String = "",
    val running: Boolean = false,
    val pid: Int? = null,
    val interval_seconds: Int? = null,
    val next_capture_in_seconds: Int? = null,
    val started_at_ts: Long? = null,
    val esp_base: String? = null,
    val log_path: String? = null,
    val message: String? = null
)

data class ImageMetadata(
    val temperature: Double? = null,
    val pressure: Double? = null,
    val humidity: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val user_comment: String? = null
)

data class ImageItem(
    val filename: String = "",
    val upload_time: String = "",
    val file_size_bytes: Long = 0,
    val image_width: Int? = null,
    val image_height: Int? = null,
    val metadata: ImageMetadata? = null,
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

data class ActionResponse(
    val status: String = "",
    val message: String? = null,
    val timestamp: Long? = null,
    val removed_images: Int? = null,
    val removed_labels: Int? = null,
    val removed_jsons: Int? = null,
    val removed_metadata: Int? = null,
    val removed_thumbnails: Int? = null,
    val errors_count: Int? = null
)

data class DeleteImageRequest(
    val filename: String
)

data class DeleteImageResponse(
    val status: String = "",
    val removed: Map<String, Boolean>? = null,
    val message: String? = null
)
