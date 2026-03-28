"use strict";

const ROUTES = {
    getImages: "/api/v1/images",
    imageStatus: "/api/v1/images",
    deleteImage: "/api/v1/images/delete",
    deleteAll: "/api/v1/images/delete-all",
    downloadAll: "/api/v1/download/dataset",
    downloadVisible: "/api/v1/download/dataset-selected",
    labelPage: "/label",
};

const gallery = document.getElementById("gallery");
const socket = typeof io === "function" ? io() : null;

const galleryState = {
    pageImages: [],
    currentPage: 1,
    pageSize: 24,
    totalItems: 0,
    totalPages: 1,
    shownStart: 0,
    shownEnd: 0,
    globalTotal: 0,
    globalLabeled: 0,
    activeLabelerFilename: null,
};

const dom = {
    filterInput: () => document.getElementById("filterInput"),
    labeledCounter: () => document.getElementById("labeledCounter"),
    pageSizeSelect: () => document.getElementById("pageSizeSelect"),
    columnsSelect: () => document.getElementById("columnsSelect"),
    paginationInfo: () => document.getElementById("paginationInfo"),
    paginationControls: () => document.getElementById("paginationControls"),
    downloadAllBtn: () => document.getElementById("downloadDatasetAllBtn"),
    downloadVisibleBtn: () => document.getElementById("downloadDatasetVisibleBtn"),
    deleteAllBtn: () => document.getElementById("deleteAllBtn"),
    labeledFilterRadios: () => document.querySelectorAll('input[name="labeledFilter"]'),
    labelerOverlay: () => document.getElementById("labelerModalOverlay"),
    labelerFrame: () => document.getElementById("labelerModalFrame"),
    labelerCloseBtn: () => document.getElementById("labelerModalCloseBtn"),
    labelerPrevBtn: () => document.getElementById("labelerModalPrevBtn"),
    labelerNextBtn: () => document.getElementById("labelerModalNextBtn"),
    labelerStatusTag: () => document.getElementById("labelerModalStatusTag"),
    labelerTitleText: () => document.getElementById("labelerModalTitleText"),
    labelerMeta: () => document.getElementById("labelerModalMeta"),
    confirmOverlay: () => document.getElementById("confirmModalOverlay"),
    confirmMessage: () => document.getElementById("confirmModalMessage"),
    confirmCancel: () => document.getElementById("confirmModalCancel"),
    confirmDelete: () => document.getElementById("confirmModalDelete"),
};

function toNumber(value, fallback) {
    return Number.isFinite(value) ? value : fallback;
}

function getSelectedLabelFilterValue() {
    const selected = document.querySelector('input[name="labeledFilter"]:checked');
    return selected ? selected.value : "all";
}

function buildGetImagesUrl() {
    const params = new URLSearchParams();
    const filterValue = dom.filterInput()?.value?.trim() || "";
    const labeledFilter = getSelectedLabelFilterValue();

    if (filterValue) params.set("filter", filterValue);
    if (labeledFilter === "non_labeled") params.set("only_labeled", "1");
    if (labeledFilter === "labeled") params.set("labeled_only", "1");

    params.set("page", String(galleryState.currentPage));
    params.set("page_size", String(galleryState.pageSize));

    return `${ROUTES.getImages}?${params.toString()}`;
}

function buildThumbnailUrl(filename) {
    return `${ROUTES.imageStatus}/${encodeURIComponent(filename)}/thumbnail?w=320`;
}

function buildMetadataHTML(imageData) {
    const labeledText = imageData.is_labeled ? "Labeled" : "To label";
    const labeledClass = imageData.is_labeled ? "ok" : "todo";
    const sizeText = formatBytes(imageData.file_size_bytes ?? 0);
    const latLonText = formatLatLon(imageData.metadata);
    const envText = formatEnvSensors(imageData.metadata);
    const detailLines = [];
    if (latLonText !== "GPS: n/a") detailLines.push(latLonText);
    if (envText) detailLines.push(envText);
    if (detailLines.length === 0) detailLines.push("Metadata: n/a");
    const detailHtml = detailLines
        .map((line) => `<div class=\"gallery-meta-row gallery-meta-gps\">${line}</div>`)
        .join("");

    return `
        <div class="gallery-meta-name">${imageData.filename}</div>
        <div class="gallery-meta-row">
            <span><strong>${imageData.labels_count ?? 0}</strong> labels</span>
            <span>${sizeText}</span>
            <span class="gallery-meta-pill ${labeledClass}">${labeledText}</span>
        </div>
        ${detailHtml}
    `;
}

function formatBytes(bytes) {
    const value = Number(bytes) || 0;
    if (value >= 1024 * 1024) {
        return `${(value / (1024 * 1024)).toFixed(2)} MB`;
    }
    if (value >= 1024) {
        return `${(value / 1024).toFixed(1)} kB`;
    }
    return `${value} B`;
}

function formatLatLon(metadata) {
    const lat = Number(metadata?.latitude);
    const lon = Number(metadata?.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        return "GPS: n/a";
    }
    if (Math.abs(lat) < 1e-9 && Math.abs(lon) < 1e-9) {
        return "GPS: n/a";
    }
    return `GPS: ${lat.toFixed(6)}, ${lon.toFixed(6)}`;
}

function formatModalMetadata(imageData) {
    const md = imageData?.metadata || {};
    const gps = formatLatLon(md);
    const env = formatEnvSensors(md);
    const parts = [];
    if (gps !== "GPS: n/a") parts.push(gps);
    if (env) parts.push(env);
    if (parts.length === 0) return "Metadata: n/a";
    return parts.join(" | ");
}

function formatEnvSensors(metadata) {
    const temperature = Number(metadata?.temperature);
    const humidity = Number(metadata?.humidity);
    const pressure = Number(metadata?.pressure);

    const parts = [];
    if (Number.isFinite(temperature)) parts.push(`T: ${temperature.toFixed(1)} C`);
    if (Number.isFinite(humidity)) parts.push(`H: ${humidity.toFixed(1)} %`);
    if (Number.isFinite(pressure)) parts.push(`P: ${pressure.toFixed(1)} hPa`);
    return parts.join(" | ");
}

function findCardByFilename(filename) {
    const cards = document.querySelectorAll(".gallery-item[data-filename]");
    for (const card of cards) {
        if (card.dataset.filename === filename) return card;
    }
    return null;
}

function updateLabeledCounter() {
    const counter = dom.labeledCounter();
    if (!counter) return;
    counter.textContent = `${galleryState.globalLabeled} / ${galleryState.globalTotal} labeled (shown ${galleryState.totalItems})`;
}

function applyGalleryColumns(value) {
    if (!gallery) return;

    if (!value || value === "auto") {
        gallery.classList.remove("manual-cols");
        gallery.style.removeProperty("--gallery-cols");
        return;
    }

    const cols = parseInt(value, 10);
    if (Number.isNaN(cols) || cols < 1) return;

    gallery.classList.add("manual-cols");
    gallery.style.setProperty("--gallery-cols", String(cols));
}

function openLabelerModal(filename) {
    const overlay = dom.labelerOverlay();
    const frame = dom.labelerFrame();

    if (!overlay || !frame) {
        window.location.href = `${ROUTES.labelPage}?image=${encodeURIComponent(filename)}`;
        return;
    }

    overlay.classList.add("fullscreen");

    galleryState.activeLabelerFilename = filename;
    frame.src = `${ROUTES.labelPage}?image=${encodeURIComponent(filename)}`;
    overlay.classList.add("open");
    document.body.style.overflow = "hidden";
    updateModalNavigationButtons();
    updateModalStatusTag();
}

function closeLabelerModal() {
    const overlay = dom.labelerOverlay();
    const frame = dom.labelerFrame();
    if (!overlay || !frame) return;

    const wasOpen = overlay.classList.contains("open");
    overlay.classList.remove("open", "fullscreen");

    frame.src = "about:blank";
    document.body.style.overflow = "";

    if (wasOpen) {
        refreshSingleImageCard(galleryState.activeLabelerFilename);
    }
    galleryState.activeLabelerFilename = null;
    updateModalNavigationButtons();
    updateModalStatusTag();
}

function getActiveModalIndex() {
    if (!galleryState.activeLabelerFilename) return -1;
    return galleryState.pageImages.findIndex((img) => img.filename === galleryState.activeLabelerFilename);
}

function navigateModalImage(direction) {
    const idx = getActiveModalIndex();
    if (idx < 0) return;

    const nextIdx = idx + direction;
    if (nextIdx < 0 || nextIdx >= galleryState.pageImages.length) return;

    const nextImage = galleryState.pageImages[nextIdx];
    if (!nextImage || !nextImage.filename) return;
    openLabelerModal(nextImage.filename);
}

function updateModalNavigationButtons() {
    const prevBtn = dom.labelerPrevBtn();
    const nextBtn = dom.labelerNextBtn();
    if (!prevBtn || !nextBtn) return;

    const idx = getActiveModalIndex();
    prevBtn.disabled = idx <= 0;
    nextBtn.disabled = idx < 0 || idx >= galleryState.pageImages.length - 1;
}

function updateModalStatusTag() {
    const statusTag = dom.labelerStatusTag();
    const titleTextEl = dom.labelerTitleText();
    const metaEl = dom.labelerMeta();
    if (!statusTag) return;

    const idx = getActiveModalIndex();
    if (idx < 0) {
        statusTag.textContent = "";
        statusTag.classList.remove("ok", "todo");
        if (titleTextEl) titleTextEl.textContent = "File name: -";
        if (metaEl) metaEl.textContent = "Metadata: n/a";
        return;
    }

    const imageData = galleryState.pageImages[idx];
    const isLabeled = !!imageData?.is_labeled;
    if (titleTextEl) {
        titleTextEl.textContent = `File name: ${imageData?.filename || "-"}`;
    }
    if (metaEl) {
        metaEl.textContent = formatModalMetadata(imageData);
    }
    statusTag.textContent = isLabeled ? "Labeled" : "To label";
    statusTag.classList.toggle("ok", isLabeled);
    statusTag.classList.toggle("todo", !isLabeled);
}

async function refreshSingleImageCard(filename) {
    if (!filename) return;

    const delays = [0, 250, 700, 1300];
    for (const delayMs of delays) {
        if (delayMs > 0) {
            await new Promise((resolve) => setTimeout(resolve, delayMs));
        }

        try {
            const response = await fetch(`${ROUTES.imageStatus}/${encodeURIComponent(filename)}/status`);
            if (!response.ok) continue;

            const data = await response.json();
            if (data.status !== "success") continue;

            const card = findCardByFilename(data.filename);
            if (!card) return;

            const metadataDiv = card.querySelector(".image-metadata");
            if (!metadataDiv) return;

            metadataDiv.innerHTML = buildMetadataHTML({
                filename: data.filename,
                labels_count: data.labels_count,
                is_labeled: data.is_labeled,
                file_size_bytes: data.file_size_bytes,
                metadata: data.metadata,
            });
            const stateItem = galleryState.pageImages.find((img) => img.filename === data.filename);
            if (stateItem) {
                stateItem.labels_count = data.labels_count;
                stateItem.is_labeled = data.is_labeled;
                stateItem.file_size_bytes = data.file_size_bytes ?? stateItem.file_size_bytes;
                stateItem.metadata = data.metadata ?? stateItem.metadata;
            }
            updateModalStatusTag();
            return;
        } catch (err) {
            console.warn("refreshSingleImageCard attempt failed:", err);
        }
    }
}

function confirmDeleteDialog(message) {
    return new Promise((resolve) => {
        const overlay = dom.confirmOverlay();
        const messageEl = dom.confirmMessage();
        const cancelBtn = dom.confirmCancel();
        const deleteBtn = dom.confirmDelete();

        if (!overlay || !messageEl || !cancelBtn || !deleteBtn) {
            resolve(window.confirm(message));
            return;
        }

        messageEl.textContent = message;
        overlay.classList.add("open");
        deleteBtn.focus();

        let done = false;

        const cleanup = (result) => {
            if (done) return;
            done = true;
            overlay.classList.remove("open");
            cancelBtn.removeEventListener("click", onCancel);
            deleteBtn.removeEventListener("click", onConfirm);
            overlay.removeEventListener("click", onBackdrop);
            document.removeEventListener("keydown", onKeydown);
            resolve(result);
        };

        const onCancel = () => cleanup(false);
        const onConfirm = () => cleanup(true);
        const onBackdrop = (e) => {
            if (e.target === overlay) cleanup(false);
        };
        const onKeydown = (e) => {
            if (e.key === "Escape") cleanup(false);
        };

        cancelBtn.addEventListener("click", onCancel);
        deleteBtn.addEventListener("click", onConfirm);
        overlay.addEventListener("click", onBackdrop);
        document.addEventListener("keydown", onKeydown);
    });
}

async function deleteImage(filename) {
    try {
        const response = await fetch(ROUTES.deleteImage, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ filename }),
        });
        const data = await response.json();

        if (data.status === "success" || data.status === "partial") {
            loadGalleryImages();
            return;
        }
        alert(`Delete failed: ${data.message || "unknown error"}`);
    } catch (err) {
        console.error("Delete error:", err);
        alert(`Delete failed: ${err}`);
    }
}

async function deleteAllImages() {
    try {
        const response = await fetch(ROUTES.deleteAll, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: "{}",
        });
        const data = await response.json();

        if (response.ok && (data.status === "success" || data.status === "partial")) {
            galleryState.currentPage = 1;
            await loadGalleryImages();
            return;
        }
        alert(`Delete all failed: ${data.message || "unknown error"}`);
    } catch (err) {
        console.error("Delete all error:", err);
        alert(`Delete all failed: ${err}`);
    }
}

function lazyLoadImage(img) {
    const observer = new IntersectionObserver((entries, ioObserver) => {
        entries.forEach((entry) => {
            if (!entry.isIntersecting) return;
            entry.target.src = entry.target.dataset.src;
            entry.target.onload = () => {
                entry.target.style.visibility = "visible";
            };
            ioObserver.unobserve(entry.target);
        });
    }, { rootMargin: "100px" });

    observer.observe(img);
}

function addImageToGallery(imageData, prepend = false) {
    if (!gallery) return;

    const card = document.createElement("div");
    card.classList.add("col", "gallery-item");
    card.dataset.filename = imageData.filename;
    card.style.cursor = "pointer";

    const deleteBtn = document.createElement("button");
    deleteBtn.classList.add("img-delete-btn");
    deleteBtn.textContent = "×";
    deleteBtn.addEventListener("click", async (e) => {
        e.stopPropagation();
        const confirmed = await confirmDeleteDialog(`Delete image "${imageData.filename}" and its labels?`);
        if (!confirmed) return;
        await deleteImage(imageData.filename);
    });

    const img = document.createElement("img");
    img.classList.add("image-gallery-img");
    img.dataset.src = buildThumbnailUrl(imageData.filename);
    img.alt = imageData.filename;
    img.style.visibility = "hidden";

    const metadataDiv = document.createElement("div");
    metadataDiv.classList.add("image-metadata");
    metadataDiv.innerHTML = buildMetadataHTML(imageData);

    card.addEventListener("click", () => openLabelerModal(imageData.filename));

    card.append(deleteBtn, img, metadataDiv);
    if (prepend) {
        gallery.prepend(card);
    } else {
        gallery.appendChild(card);
    }

    lazyLoadImage(img);
}

function buildPageTokens(totalPages, currentPage) {
    if (totalPages <= 7) {
        return Array.from({ length: totalPages }, (_, i) => i + 1);
    }

    const pages = new Set([1, totalPages, currentPage, currentPage - 1, currentPage + 1]);
    if (currentPage <= 3) {
        pages.add(2);
        pages.add(3);
    }
    if (currentPage >= totalPages - 2) {
        pages.add(totalPages - 1);
        pages.add(totalPages - 2);
    }

    const sortedPages = [...pages]
        .filter((page) => page >= 1 && page <= totalPages)
        .sort((a, b) => a - b);

    const tokens = [];
    for (let i = 0; i < sortedPages.length; i += 1) {
        const page = sortedPages[i];
        const prev = sortedPages[i - 1];
        if (prev && page - prev > 1) tokens.push("...");
        tokens.push(page);
    }

    return tokens;
}

function renderPagination() {
    const paginationInfo = dom.paginationInfo();
    const paginationControls = dom.paginationControls();
    const { totalItems, totalPages } = galleryState;

    if (paginationInfo) {
        paginationInfo.textContent = totalItems === 0
            ? "No images to show"
            : `Showing ${galleryState.shownStart}-${galleryState.shownEnd} of ${totalItems}`;
    }

    if (!paginationControls) return;

    paginationControls.innerHTML = "";
    if (totalItems === 0 || totalPages <= 1) return;

    const prevBtn = document.createElement("button");
    prevBtn.className = "gallery-page-btn";
    prevBtn.textContent = "Prev";
    prevBtn.disabled = galleryState.currentPage === 1;
    prevBtn.dataset.page = String(galleryState.currentPage - 1);
    paginationControls.appendChild(prevBtn);

    const tokens = buildPageTokens(totalPages, galleryState.currentPage);
    tokens.forEach((token) => {
        if (token === "...") {
            const dots = document.createElement("span");
            dots.className = "gallery-page-dots";
            dots.textContent = "...";
            paginationControls.appendChild(dots);
            return;
        }

        const btn = document.createElement("button");
        btn.className = "gallery-page-btn";
        btn.textContent = String(token);
        btn.dataset.page = String(token);
        if (token === galleryState.currentPage) {
            btn.classList.add("active");
        }
        paginationControls.appendChild(btn);
    });

    const nextBtn = document.createElement("button");
    nextBtn.className = "gallery-page-btn";
    nextBtn.textContent = "Next";
    nextBtn.disabled = galleryState.currentPage === totalPages;
    nextBtn.dataset.page = String(galleryState.currentPage + 1);
    paginationControls.appendChild(nextBtn);
}

function renderCurrentPage() {
    if (!gallery) return;
    gallery.innerHTML = "";
    galleryState.pageImages.forEach((imageData) => addImageToGallery(imageData, false));
    renderPagination();
    updateModalNavigationButtons();
    updateModalStatusTag();
}

function applyGalleryResponse(data) {
    galleryState.pageImages = Array.isArray(data.items) ? data.items : [];
    galleryState.totalItems = toNumber(data.total_items, galleryState.pageImages.length);
    galleryState.totalPages = toNumber(data.total_pages, 1);
    galleryState.currentPage = toNumber(data.page, galleryState.currentPage);
    galleryState.shownStart = toNumber(data.shown_start, 0);
    galleryState.shownEnd = toNumber(data.shown_end, galleryState.pageImages.length);
    galleryState.globalTotal = toNumber(data.global_total, galleryState.totalItems);
    galleryState.globalLabeled = toNumber(data.global_labeled, 0);
}

async function loadGalleryImages() {
    if (!gallery) return;

    try {
        const response = await fetch(buildGetImagesUrl());
        if (!response.ok) {
            console.error("Failed to fetch images:", response.status);
            return;
        }

        const data = await response.json();
        applyGalleryResponse(data);
        updateLabeledCounter();
        renderCurrentPage();
    } catch (err) {
        console.error("Failed to load gallery images:", err);
    }
}

async function downloadVisibleImages() {
    const filenames = galleryState.pageImages.map((img) => img.filename);
    if (filenames.length === 0) {
        alert("No visible images to download.");
        return;
    }

    const response = await fetch(ROUTES.downloadVisible, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ filenames }),
    });

    if (!response.ok) {
        alert(`Download failed: ${await response.text()}`);
        return;
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "agriapp_dataset_visible.zip";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
}

function bindControls() {
    const downloadAllBtn = dom.downloadAllBtn();
    if (downloadAllBtn) {
        downloadAllBtn.addEventListener("click", () => {
            window.location.href = ROUTES.downloadAll;
        });
    }

    const downloadVisibleBtn = dom.downloadVisibleBtn();
    if (downloadVisibleBtn) {
        downloadVisibleBtn.addEventListener("click", () => {
            downloadVisibleImages().catch((err) => {
                console.error("Download visible failed:", err);
                alert(`Download failed: ${err}`);
            });
        });
    }

    const deleteAllBtn = dom.deleteAllBtn();
    if (deleteAllBtn) {
        deleteAllBtn.addEventListener("click", async () => {
            const confirmed = await confirmDeleteDialog("Delete ALL images and related labels/metadata?");
            if (!confirmed) return;
            await deleteAllImages();
        });
    }

    const filterInput = dom.filterInput();
    if (filterInput) {
        filterInput.addEventListener("input", () => {
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    }

    dom.labeledFilterRadios().forEach((radio) => {
        radio.addEventListener("change", () => {
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    });

    const pageSizeSelect = dom.pageSizeSelect();
    if (pageSizeSelect) {
        pageSizeSelect.addEventListener("change", () => {
            galleryState.pageSize = parseInt(pageSizeSelect.value, 10) || 24;
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    }

    const columnsSelect = dom.columnsSelect();
    if (columnsSelect) {
        applyGalleryColumns(columnsSelect.value);
        columnsSelect.addEventListener("change", () => {
            applyGalleryColumns(columnsSelect.value);
        });
    }

    const paginationControls = dom.paginationControls();
    if (paginationControls) {
        paginationControls.addEventListener("click", (e) => {
            const target = e.target.closest("button[data-page]");
            if (!target || target.disabled) return;

            const page = parseInt(target.dataset.page, 10);
            if (Number.isNaN(page) || page < 1) return;

            galleryState.currentPage = page;
            loadGalleryImages();
            window.scrollTo({ top: 0, behavior: "smooth" });
        });
    }

    const labelerCloseBtn = dom.labelerCloseBtn();
    const labelerPrevBtn = dom.labelerPrevBtn();
    const labelerNextBtn = dom.labelerNextBtn();
    if (labelerCloseBtn) {
        labelerCloseBtn.addEventListener("click", closeLabelerModal);
    }
    if (labelerPrevBtn) {
        labelerPrevBtn.addEventListener("click", () => navigateModalImage(-1));
    }
    if (labelerNextBtn) {
        labelerNextBtn.addEventListener("click", () => navigateModalImage(1));
    }

    const labelerOverlay = dom.labelerOverlay();
    if (labelerOverlay) {
        labelerOverlay.addEventListener("click", (e) => {
            if (e.target === labelerOverlay) closeLabelerModal();
        });
    }

    document.addEventListener("keydown", (e) => {
        if (!galleryState.activeLabelerFilename) return;
        if (e.key === "Escape") closeLabelerModal();
        if (e.key === "ArrowLeft") navigateModalImage(-1);
        if (e.key === "ArrowRight") navigateModalImage(1);
    });
}

function bindSocketUpdates() {
    if (!socket || !gallery) return;
    socket.on("new_image", () => {
        loadGalleryImages();
    });
}

function initGalleryPage() {
    if (!gallery) return;
    bindControls();
    bindSocketUpdates();
    loadGalleryImages();
}

document.addEventListener("DOMContentLoaded", initGalleryPage);
