package it.unipg.agriapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.unipg.agriapp.data.ActionResponse
import it.unipg.agriapp.data.ApiClient
import it.unipg.agriapp.data.DeleteImageRequest
import it.unipg.agriapp.data.DeleteImageResponse
import it.unipg.agriapp.data.ImageItem
import it.unipg.agriapp.data.ImagesResponse
import kotlinx.coroutines.launch

data class GalleryUiState(
    val baseUrl: String = "http://raspberrypi.local:5000",
    val images: List<ImageItem> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val pageSize: Int = 20,
    val selectedImageFilename: String? = null,
    val viewerImageUrl: String? = null,
    val viewerTitle: String? = null,
    val log: String = "Ready",
    val busy: Boolean = false
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("agriapp_hosts", android.content.Context.MODE_PRIVATE)
    private val prefRpiHosts = "rpi_hosts"
    var state: GalleryUiState = GalleryUiState()
        private set

    fun setBaseUrl(raw: String) {
        val normalized = normalizeBase(raw)
        state = state.copy(baseUrl = normalized)
        rememberHost(normalized)
    }

    fun loadImages(onState: (GalleryUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = state.page, pageSize = state.pageSize)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(log = "Gallery page ${res.page}/${res.total_pages}")
    }

    fun refresh(onState: (GalleryUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = state.page, pageSize = state.pageSize)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(log = "Gallery refreshed")
    }

    fun nextPage(onState: (GalleryUiState) -> Unit) {
        if (state.page >= state.totalPages) {
            onState(state.copy(log = "Already at last page"))
            return
        }
        loadPage(state.page + 1, onState)
    }

    fun prevPage(onState: (GalleryUiState) -> Unit) {
        if (state.page <= 1) {
            onState(state.copy(log = "Already at first page"))
            return
        }
        loadPage(state.page - 1, onState)
    }

    fun deleteImage(filename: String, onState: (GalleryUiState) -> Unit) = runCall(onState) {
        val deleteResponse: DeleteImageResponse = api().deleteImage(DeleteImageRequest(filename))
        val target = state.page
        val res = api().images(page = target, pageSize = state.pageSize)
        val fallbackPage = if (res.total_pages > 0) target.coerceAtMost(res.total_pages) else 1
        val finalRes = if (fallbackPage != target) api().images(page = fallbackPage, pageSize = state.pageSize) else res
        applyImagesResponse(finalRes, finalRes.items.firstOrNull()?.filename)
        state = state.copy(
            viewerImageUrl = null,
            viewerTitle = null,
            log = "Deleted $filename (${deleteResponse.status})"
        )
    }

    fun deleteAll(onState: (GalleryUiState) -> Unit) = runCall(onState) {
        val deleteAllResponse: ActionResponse = api().deleteAllImages()
        val res = api().images(page = 1, pageSize = state.pageSize)
        applyImagesResponse(res, null)
        state = state.copy(
            viewerImageUrl = null,
            viewerTitle = null,
            log = "All images deleted (${deleteAllResponse.status})"
        )
    }

    fun openImage(filename: String) {
        state = state.copy(
            selectedImageFilename = filename,
            viewerImageUrl = "${state.baseUrl}/static/uploads/images/$filename",
            viewerTitle = filename,
            log = "Open image: $filename"
        )
    }

    fun openAdjacent(delta: Int) {
        val current = state.selectedImageFilename ?: return
        val idx = state.images.indexOfFirst { it.filename == current }
        if (idx < 0) return
        val target = idx + delta
        if (target !in state.images.indices) return
        openImage(state.images[target].filename)
    }

    fun closeViewer() {
        state = state.copy(viewerImageUrl = null, viewerTitle = null)
    }

    private fun loadPage(target: Int, onState: (GalleryUiState) -> Unit) = runCall(onState) {
        val res = api().images(page = target.coerceAtLeast(1), pageSize = state.pageSize)
        applyImagesResponse(res, state.selectedImageFilename)
        state = state.copy(log = "Page ${res.page}/${res.total_pages}")
    }

    private fun applyImagesResponse(res: ImagesResponse, selected: String?) {
        val selectedFilename = selected?.takeIf { name -> res.items.any { it.filename == name } }
            ?: res.items.firstOrNull()?.filename
        state = state.copy(
            images = res.items,
            page = res.page,
            totalPages = res.total_pages.coerceAtLeast(1),
            totalItems = res.total_items,
            selectedImageFilename = selectedFilename
        )
    }

    private fun runCall(onState: (GalleryUiState) -> Unit, block: suspend () -> Unit) {
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

    private fun normalizeBase(raw: String): String {
        val input = raw.trim().ifBlank { "http://raspberrypi.local:5000" }
        val withScheme = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
        return if (withScheme.contains(":5000")) withScheme else "${withScheme.trimEnd('/')}:5000"
    }

    private fun rememberHost(host: String) {
        val current = prefs.getString(prefRpiHosts, "").orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val merged = listOf(host) + current.filterNot { it == host }
        prefs.edit().putString(prefRpiHosts, merged.take(12).joinToString("|")).apply()
    }
}
