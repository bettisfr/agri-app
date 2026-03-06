const socket = io();  // Connect to the WebSocket server
const gallery = document.querySelector('#gallery');  // Select gallery container

// NEW: base path now points to images/ subfolder
const STATIC_UPLOADS_BASE = "/static/uploads/images";
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
    activeLabelerFilename: null
};

function buildMetadataHTML(imageData) {
    const labeledText = imageData.is_labeled ? 'Labeled' : 'To label';
    const labeledClass = imageData.is_labeled ? 'ok' : 'todo';
    return `
        <div class="gallery-meta-name">${imageData.filename}</div>
        <div class="gallery-meta-row">
            <span><strong>${imageData.labels_count ?? 0}</strong> labels</span>
            <span class="gallery-meta-pill ${labeledClass}">${labeledText}</span>
        </div>
    `;
}

function openLabelerModal(filename) {
    const overlay = document.getElementById('labelerModalOverlay');
    const frame = document.getElementById('labelerModalFrame');
    const fullscreenBtn = document.getElementById('labelerModalFullscreenBtn');
    if (!overlay || !frame) {
        window.location.href = `/label?image=${encodeURIComponent(filename)}`;
        return;
    }

    overlay.classList.remove('fullscreen');
    if (fullscreenBtn) {
        fullscreenBtn.textContent = 'Full screen';
    }
    galleryState.activeLabelerFilename = filename;
    frame.src = `/label?image=${encodeURIComponent(filename)}`;
    overlay.classList.add('open');
    document.body.style.overflow = 'hidden';
}

function closeLabelerModal() {
    const overlay = document.getElementById('labelerModalOverlay');
    const frame = document.getElementById('labelerModalFrame');
    const fullscreenBtn = document.getElementById('labelerModalFullscreenBtn');
    if (!overlay || !frame) return;
    const wasOpen = overlay.classList.contains('open');
    overlay.classList.remove('open');
    overlay.classList.remove('fullscreen');
    if (fullscreenBtn) {
        fullscreenBtn.textContent = 'Full screen';
    }
    frame.src = 'about:blank';
    document.body.style.overflow = '';
    if (wasOpen) {
        refreshSingleImageCard(galleryState.activeLabelerFilename);
    }
    galleryState.activeLabelerFilename = null;
}

async function refreshSingleImageCard(filename) {
    if (!filename) return;
    const findCardByFilename = (name) => {
        const cards = document.querySelectorAll('.gallery-item[data-filename]');
        for (const c of cards) {
            if (c.dataset.filename === name) return c;
        }
        return null;
    };

    const delays = [0, 250, 700, 1300];
    for (const delayMs of delays) {
        if (delayMs > 0) {
            await new Promise(resolve => setTimeout(resolve, delayMs));
        }
        try {
            const res = await fetch(`/image-status?filename=${encodeURIComponent(filename)}`);
            if (!res.ok) continue;
            const data = await res.json();
            if (data.status !== 'success') continue;

            const card = findCardByFilename(data.filename);
            if (!card) return;

            const metadataDiv = card.querySelector('.image-metadata');
            if (!metadataDiv) return;

            metadataDiv.innerHTML = buildMetadataHTML({
                filename: data.filename,
                labels_count: data.labels_count,
                is_labeled: data.is_labeled
            });
            return;
        } catch (err) {
            console.warn('refreshSingleImageCard attempt failed:', err);
        }
    }
}

function applyGalleryColumns(value) {
    if (!gallery) return;
    if (!value || value === 'auto') {
        gallery.classList.remove('manual-cols');
        gallery.style.removeProperty('--gallery-cols');
        return;
    }

    const cols = parseInt(value, 10);
    if (Number.isNaN(cols) || cols < 1) return;
    gallery.classList.add('manual-cols');
    gallery.style.setProperty('--gallery-cols', String(cols));
}

function confirmDeleteDialog(message) {
    return new Promise((resolve) => {
        const overlay = document.getElementById('confirmModalOverlay');
        const messageEl = document.getElementById('confirmModalMessage');
        const cancelBtn = document.getElementById('confirmModalCancel');
        const deleteBtn = document.getElementById('confirmModalDelete');

        if (!overlay || !messageEl || !cancelBtn || !deleteBtn) {
            resolve(window.confirm(message));
            return;
        }

        messageEl.textContent = message;
        overlay.classList.add('open');
        deleteBtn.focus();

        let done = false;

        const cleanup = (result) => {
            if (done) return;
            done = true;
            overlay.classList.remove('open');
            cancelBtn.removeEventListener('click', onCancel);
            deleteBtn.removeEventListener('click', onConfirm);
            overlay.removeEventListener('click', onBackdrop);
            document.removeEventListener('keydown', onKeydown);
            resolve(result);
        };

        const onCancel = () => cleanup(false);
        const onConfirm = () => cleanup(true);
        const onBackdrop = (e) => {
            if (e.target === overlay) cleanup(false);
        };
        const onKeydown = (e) => {
            if (e.key === 'Escape') cleanup(false);
        };

        cancelBtn.addEventListener('click', onCancel);
        deleteBtn.addEventListener('click', onConfirm);
        overlay.addEventListener('click', onBackdrop);
        document.addEventListener('keydown', onKeydown);
    });
}

// Fetch and display all images from the server
function loadGalleryImages() {
    const filterInput = document.getElementById('filterInput');
    const onlyLabeledCheckbox = document.getElementById('onlyLabeledCheckbox');

    const params = [];

    if (filterInput && filterInput.value.trim() !== '') {
        params.push('filter=' + encodeURIComponent(filterInput.value.trim()));
    }

    if (onlyLabeledCheckbox && onlyLabeledCheckbox.checked) {
        params.push('only_labeled=1');  // means NON-labeled only (backend logic)
    }

    params.push('page=' + galleryState.currentPage);
    params.push('page_size=' + galleryState.pageSize);

    const url = '/get-images?' + params.join('&');
    fetch(url)
    .then(response => response.json())
    .then(data => {
        galleryState.pageImages = Array.isArray(data.items) ? data.items : [];
        galleryState.totalItems = Number.isFinite(data.total_items) ? data.total_items : galleryState.pageImages.length;
        galleryState.totalPages = Number.isFinite(data.total_pages) ? data.total_pages : 1;
        galleryState.currentPage = Number.isFinite(data.page) ? data.page : galleryState.currentPage;
        galleryState.shownStart = Number.isFinite(data.shown_start) ? data.shown_start : 0;
        galleryState.shownEnd = Number.isFinite(data.shown_end) ? data.shown_end : galleryState.pageImages.length;
        galleryState.globalTotal = Number.isFinite(data.global_total) ? data.global_total : galleryState.totalItems;
        galleryState.globalLabeled = Number.isFinite(data.global_labeled) ? data.global_labeled : 0;

        const counter = document.getElementById('labeledCounter');
        if (counter) {
            counter.textContent = `${galleryState.globalLabeled} / ${galleryState.globalTotal} labeled (shown ${galleryState.totalItems})`;
        }
        renderCurrentPage();
    });
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

    const sorted = [...pages]
        .filter(p => p >= 1 && p <= totalPages)
        .sort((a, b) => a - b);

    const tokens = [];
    for (let i = 0; i < sorted.length; i++) {
        const page = sorted[i];
        const prev = sorted[i - 1];
        if (prev && page - prev > 1) tokens.push('...');
        tokens.push(page);
    }
    return tokens;
}

function renderCurrentPage() {
    const paginationInfo = document.getElementById('paginationInfo');
    const paginationControls = document.getElementById('paginationControls');
    const totalItems = galleryState.totalItems;
    const totalPages = galleryState.totalPages;

    gallery.innerHTML = "";
    galleryState.pageImages.forEach(imageData => addImageToGallery(imageData, false));

    if (paginationInfo) {
        if (totalItems === 0) {
            paginationInfo.textContent = "No images to show";
        } else {
            paginationInfo.textContent = `Showing ${galleryState.shownStart}-${galleryState.shownEnd} of ${totalItems}`;
        }
    }

    if (!paginationControls) return;
    paginationControls.innerHTML = "";
    if (totalItems === 0 || totalPages <= 1) return;

    const prevBtn = document.createElement('button');
    prevBtn.className = 'gallery-page-btn';
    prevBtn.textContent = 'Prev';
    prevBtn.disabled = galleryState.currentPage === 1;
    prevBtn.dataset.page = String(galleryState.currentPage - 1);
    paginationControls.appendChild(prevBtn);

    const tokens = buildPageTokens(totalPages, galleryState.currentPage);
    tokens.forEach(token => {
        if (token === '...') {
            const dots = document.createElement('span');
            dots.className = 'gallery-page-dots';
            dots.textContent = '...';
            paginationControls.appendChild(dots);
            return;
        }

        const btn = document.createElement('button');
        btn.className = 'gallery-page-btn';
        btn.textContent = String(token);
        btn.dataset.page = String(token);
        if (token === galleryState.currentPage) {
            btn.classList.add('active');
        }
        paginationControls.appendChild(btn);
    });

    const nextBtn = document.createElement('button');
    nextBtn.className = 'gallery-page-btn';
    nextBtn.textContent = 'Next';
    nextBtn.disabled = galleryState.currentPage === totalPages;
    nextBtn.dataset.page = String(galleryState.currentPage + 1);
    paginationControls.appendChild(nextBtn);
}


// Add an image to the gallery with optional real-time effect
function addImageToGallery(imageData, isRealTime = true) {
    console.log("Adding image:", imageData.filename); // Debugging

    const div = document.createElement('div');
    div.classList.add('col', 'gallery-item');   // add gallery-item for CSS positioning
    div.dataset.filename = imageData.filename;

    // ---- delete button (top-right X) ----
    const deleteBtn = document.createElement('button');
    deleteBtn.classList.add('img-delete-btn');
    deleteBtn.textContent = '×';

    // do NOT open labeler when clicking X
    deleteBtn.addEventListener('click', async (e) => {
        e.stopPropagation();   // prevent click from bubbling to div
        const ok = await confirmDeleteDialog(
            `Delete image "${imageData.filename}" and its labels?`
        );
        if (!ok) return;

        fetch('/delete-image', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ filename: imageData.filename })
        })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'success' || data.status === 'partial') {
                    loadGalleryImages();
                } else {
                    alert('Delete failed: ' + (data.message || 'unknown error'));
                }
            })
            .catch(err => {
                console.error('Delete error:', err);
                alert('Delete failed: ' + err);
            });
    });

    // Image element with lazy loading
    const img = document.createElement('img');
    img.classList.add('image-gallery-img');

    // CHANGED: now uses images/ subfolder
    img.dataset.src = `${STATIC_UPLOADS_BASE}/${imageData.filename}`;
    img.alt = imageData.filename;
    img.style.visibility = 'hidden';

    // When clicking the image (or the whole div), open labeler
    div.style.cursor = 'pointer';
    div.addEventListener('click', () => {
        openLabelerModal(imageData.filename);
    });

    const metadataDiv = document.createElement('div');
    metadataDiv.classList.add('image-metadata');

    metadataDiv.innerHTML = buildMetadataHTML(imageData);

    // order: X button on top, then img, then metadata
    div.appendChild(deleteBtn);
    div.appendChild(img);
    div.appendChild(metadataDiv);

    if (isRealTime) {
        gallery.prepend(div);
    } else {
        gallery.appendChild(div);
    }

    lazyLoadImage(img);
}

document.addEventListener('DOMContentLoaded', () => {
    const downloadAllBtn = document.getElementById('downloadDatasetAllBtn');
    if (downloadAllBtn) {
        downloadAllBtn.addEventListener('click', () => {
            window.location.href = '/download-dataset';
        });
    }
    const downloadVisibleBtn = document.getElementById('downloadDatasetVisibleBtn');
    if (downloadVisibleBtn) {
        downloadVisibleBtn.addEventListener('click', async () => {
            const filenames = galleryState.pageImages.map(img => img.filename);
            if (filenames.length === 0) {
                alert('No visible images to download.');
                return;
            }

            const res = await fetch('/download-dataset-selected', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filenames })
            });

            if (!res.ok) {
                const msg = await res.text();
                alert('Download failed: ' + msg);
                return;
            }

            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'agriapp_dataset_visible.zip';
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);
        });
    }

    const filterInput = document.getElementById('filterInput');
    const onlyLabeledCheckbox = document.getElementById('onlyLabeledCheckbox');
    const pageSizeSelect = document.getElementById('pageSizeSelect');
    const columnsSelect = document.getElementById('columnsSelect');
    const paginationControls = document.getElementById('paginationControls');
    const labelerModalOverlay = document.getElementById('labelerModalOverlay');
    const labelerModalCloseBtn = document.getElementById('labelerModalCloseBtn');
    const labelerModalFullscreenBtn = document.getElementById('labelerModalFullscreenBtn');

    if (filterInput) {
        // reload on typing (you can debounce later if needed)
        filterInput.addEventListener('input', () => {
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    }

    if (onlyLabeledCheckbox) {
        onlyLabeledCheckbox.addEventListener('change', () => {
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    }

    if (pageSizeSelect) {
        pageSizeSelect.addEventListener('change', () => {
            galleryState.pageSize = parseInt(pageSizeSelect.value, 10) || 24;
            galleryState.currentPage = 1;
            loadGalleryImages();
        });
    }

    if (columnsSelect) {
        applyGalleryColumns(columnsSelect.value);
        columnsSelect.addEventListener('change', () => {
            applyGalleryColumns(columnsSelect.value);
        });
    }

    if (paginationControls) {
        paginationControls.addEventListener('click', (e) => {
            const target = e.target.closest('button[data-page]');
            if (!target || target.disabled) return;
            const page = parseInt(target.dataset.page, 10);
            if (Number.isNaN(page) || page < 1) return;
            galleryState.currentPage = page;
            loadGalleryImages();
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    }

    if (labelerModalCloseBtn) {
        labelerModalCloseBtn.addEventListener('click', () => {
            closeLabelerModal();
        });
    }

    if (labelerModalFullscreenBtn && labelerModalOverlay) {
        labelerModalFullscreenBtn.addEventListener('click', () => {
            const isFullscreen = labelerModalOverlay.classList.toggle('fullscreen');
            labelerModalFullscreenBtn.textContent = isFullscreen ? 'Exit full screen' : 'Full screen';
        });
    }

    if (labelerModalOverlay) {
        labelerModalOverlay.addEventListener('click', (e) => {
            if (e.target === labelerModalOverlay) {
                closeLabelerModal();
            }
        });
    }

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeLabelerModal();
        }
    });

});


// Lazy loading for images (loads only when they are near the viewport)
function lazyLoadImage(img) {
    const observer = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.src = entry.target.dataset.src;  // Load image
                entry.target.onload = () => entry.target.style.visibility = 'visible'; // Show after loading
                observer.unobserve(entry.target); // Stop observing after loading
            }
        });
    }, { rootMargin: '100px' }); // Load images slightly before they appear on screen

    observer.observe(img);
}

// Listen for real-time image uploads via WebSockets
socket.on('new_image', (data) => {
    loadGalleryImages();
});

// Load all images when the page loads
loadGalleryImages();
