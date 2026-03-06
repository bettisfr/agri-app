// -------------------- GLOBAL STATE --------------------
let currentImage = null;      // { name: "img_....jpg" }
let labels = [];
let selectedId = null;
let dragMode = "idle";
let activeHandle = -1;
let createMode = false;
let drawStartPt = null;
let drawPreview = null;

const DEL_SIZE = 32;   // bigger icon box
const DEL_PAD = 6;     // slightly more spacing

const STATIC_UPLOADS_BASE = "/static/uploads/images";
const LABEL_ALPHA = 0.0;   // 0 = fully transparent, 1 = fully opaque

const APP = 0; // 0 = bugs (cimici), 1 = insects

// -------------------- CLASS DEFINITIONS --------------------
let CLASS_DEFS;

if (APP === 0) {
    CLASS_DEFS = [
        {id: 0, label: "Halyomorpha halys", color: "#e6194B"} // red
    ];
} else {
    CLASS_DEFS = [
        {id: 0, label: "Camponotus vagus", color: "#e6194B"}, // red
        {id: 1, label: "Plagiolepis pygmaea", color: "#3cb44b"}, // green
        {id: 2, label: "Crematogaster scutellaris", color: "#ffe119"}, // yellow
        {id: 3, label: "Temnothorax spp.", color: "#4363d8"}, // blue
        {id: 4, label: "Dolichoderus quadripunctatus", color: "#f58231"}, // orange
        {id: 5, label: "Colobopsis truncata", color: "#911eb4"}  // purple
    ];
}

// Derived maps for quick lookup
const CLASS_MAP = CLASS_DEFS.reduce((acc, c) => {
    acc[c.id] = c.label;
    return acc;
}, {});

const CLASS_COLOR = CLASS_DEFS.reduce((acc, c) => {
    acc[c.id] = c.color;
    return acc;
}, {});

// -------------------- ZOOM STATE --------------------
const zoomState = {
    scale: 1,
    minScale: 0.25,
    maxScale: 4,
    x: 0,
    y: 0,
    isPanning: false,
    startX: 0,
    startY: 0,
};

function applyTransform() {
    const stage = document.getElementById("imageArea");
    if (!stage) return;
    stage.style.transformOrigin = "0 0";
    stage.style.transform =
        `translate(${zoomState.x}px, ${zoomState.y}px) scale(${zoomState.scale})`;
}


// -------------------- INIT --------------------
document.addEventListener("DOMContentLoaded", () => {
    initBboxInteraction();
    initZoom();

    setStatus("Waiting for image parameter (?image=...).");

    const saveBtn = document.getElementById("saveTxtBtn");
    if (saveBtn) {
        saveBtn.addEventListener("click", () => {
            saveLabels();
        });
    }

    const downloadBtn = document.getElementById("downloadCurrentBtn");
    if (downloadBtn) {
        downloadBtn.addEventListener("click", () => {
            if (!currentImage || !currentImage.name) {
                setStatus("No image loaded, cannot download.", "error");
                return;
            }
            const url = `/download-image-with-labels?image=${encodeURIComponent(currentImage.name)}`;
            window.location.href = url;
        });
    }

    // No file picker, no placeholder: read ?image=... and load server-side image
    initFromQueryParam();
});


/* -------------------- IMAGE LOADING (server-side) -------------------- */

function initFromQueryParam() {
    const params = new URLSearchParams(window.location.search);
    const imageName = params.get("image");

    if (!imageName) {
        setStatus("No image specified. Call as /label?image=filename.jpg");
        return;
    }

    loadServerImage(imageName);
}

async function loadServerImage(filename) {
    const img = document.getElementById("previewImage");
    const canvas = document.getElementById("bboxCanvas");

    const imgURL = `${STATIC_UPLOADS_BASE}/${filename}`;

    img.onload = () => {
        fitCanvasToImage(img, canvas);  // sets natural size for img + canvas
        resetZoomToFit();               // scales & centers to fit viewer
        drawBBoxes(img, canvas, labels);
    };


    img.onerror = () => {
        setStatus(`Cannot load image: ${imgURL}`);
    };

    img.src = imgURL;

    currentImage = {name: filename};
    labels = [];
    selectedId = null;

    // --- NEW: load labels (with is_tp) from server ---
    try {
        const res = await fetch(`/get_labels?image=${encodeURIComponent(filename)}`);
        if (res.ok) {
            const data = await res.json();
            if (data.status === "success" && Array.isArray(data.labels)) {
                labels = data.labels.map(l => ({
                    cls: l.cls ?? 0,
                    x_center: l.x_center,
                    y_center: l.y_center,
                    width: l.width,
                    height: l.height,
                    // default: true if missing
                    is_tp: (l.is_tp !== false),
                }));
            }
        }
    } catch (err) {
        console.warn("Error while fetching labels:", err);
    }

    drawBBoxes(img, canvas, labels);
    renderLabelsList();
    document.getElementById("imgName").textContent = filename;
    document.getElementById("numLabels").textContent = labels.length;

    const controls = document.getElementById("controlsArea");
    if (controls) {
        controls.classList.remove("disabled");
    }

    setStatus(`Loaded ${filename} (${labels.length} labels)`);
}


/* -------------------- ICONS & STATUS -------------------- */

function drawDeleteIcon(ctx, x, y, size = DEL_SIZE) {
    const r = 3;
    ctx.save();
    ctx.fillStyle = "rgba(255,255,255,0.95)";
    ctx.strokeStyle = "red";
    ctx.lineWidth = 1.5;
    const w = size, h = size;
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.strokeStyle = "red";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x + 4, y + 4);
    ctx.lineTo(x + w - 4, y + h - 4);
    ctx.moveTo(x + w - 4, y + 4);
    ctx.lineTo(x + 4, y + h - 4);
    ctx.stroke();
    ctx.restore();
}

function drawFpIcon(ctx, x, y, size = DEL_SIZE, color = "black") {
    const r = 3;
    ctx.save();

    // background
    ctx.fillStyle = "rgba(255,255,255,0.95)";
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;

    const w = size, h = size;

    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();

    ctx.fill();
    ctx.stroke();

    // "O" symbol
    ctx.fillStyle = color;
    ctx.font = `${size * 0.65}px sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText("O", x + w / 2, y + h / 2);

    ctx.restore();
}


function drawClassIcon(ctx, text, x, y, size, color = "black") {
    const r = 3;   // corner radius
    ctx.save();

    // background
    ctx.fillStyle = "rgba(255,255,255,0.92)";
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;

    // square shape
    const w = size;
    const h = size;

    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();

    ctx.fill();
    ctx.stroke();

    // text
    ctx.fillStyle = color;
    ctx.font = `${size * 0.55}px sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(String(text), x + w / 2, y + h / 2);

    ctx.restore();
}


function isInsideDeleteIcon(px, py, boxX, boxY, boxW, boxH) {
    const ix = boxX + boxW - DEL_PAD - DEL_SIZE;
    const iy = boxY + DEL_PAD;
    return (px >= ix && px <= ix + DEL_SIZE && py >= iy && py <= iy + DEL_SIZE);
}

function isInsideFpIcon(px, py, boxX, boxY, boxW, boxH) {
    const ix = boxX + boxW - DEL_PAD - DEL_SIZE;
    const iy = boxY + boxH - DEL_PAD - DEL_SIZE;
    return (px >= ix && px <= ix + DEL_SIZE && py >= iy && py <= iy + DEL_SIZE);
}


function setStatus(msg, kind = "normal") {
    const s = document.getElementById("status");
    if (!s) return;

    s.textContent = msg;

    // reset to default
    s.style.fontWeight = "normal";
    s.style.color = "#000";

    if (kind === "success") {
        s.style.fontWeight = "bold";
        s.style.color = "#28a745"; // green
    } else if (kind === "error") {
        s.style.fontWeight = "bold";
        s.style.color = "#b00020"; // red
    }
}


/* -------------------- PARSING / SAVING LABELS -------------------- */
function parseYoloTxt(text) {
    const lines = text.split(/\r?\n/);
    const out = [];
    for (const line of lines) {
        const parts = line.trim().split(/\s+/);
        if (parts.length === 5) {
            const [cls, xc, yc, w, h] = parts.map(Number);
            out.push({
                cls,
                x_center: xc,
                y_center: yc,
                width: w,
                height: h,
                is_fp: false      // default: TP
            });
        }
    }
    return out;
}


// Server-side save: POST JSON to /save_labels
async function saveLabels() {
    if (!currentImage || !currentImage.name) {
        setStatus("No image loaded, cannot save.", "error");
        return;
    }

    try {
        const payload = {
            image: currentImage.name,
            labels: labels.map(l => ({
                cls: l.cls ?? 0,
                x_center: l.x_center,
                y_center: l.y_center,
                width: l.width,
                height: l.height,
                is_tp: (l.is_tp !== false)   // only flag we send
            }))
        };

        const res = await fetch("/save_labels", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(payload)
        });

        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }

        const data = await res.json();
        if (data.status !== "success") {
            throw new Error(data.message || "Unknown error");
        }

        // ✅ success: bold + green (handled inside setStatus)
        setStatus(data.message || "Labels saved.", "success");
        if (window.parent && window.parent !== window) {
            window.parent.postMessage(
                {type: "labeler-saved", image: currentImage.name},
                "*"
            );
        }
    } catch (err) {
        // ❌ error: bold + red (handled inside setStatus)
        setStatus(
            "Save failed: " + (err && err.message ? err.message : err),
            "error"
        );
    }
}

/* -------------------- CANVAS & DRAWING -------------------- */

function fitCanvasToImage(imgEl, canvasEl) {
    // Use the image's natural pixel size
    const w = imgEl.naturalWidth;
    const h = imgEl.naturalHeight;
    if (!w || !h) {
        return;
    }

    // Set image display size explicitly
    imgEl.style.width = w + "px";
    imgEl.style.height = h + "px";

    // Match canvas to the image size (1:1 pixels)
    canvasEl.width = w;
    canvasEl.height = h;
    canvasEl.style.width = w + "px";
    canvasEl.style.height = h + "px";
}


function drawBBoxes(imgEl, canvasEl, labs) {
    if (!imgEl || !canvasEl) return;
    // fitCanvasToImage(imgEl, canvasEl);

    const ctx = canvasEl.getContext("2d");
    ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);

    if (!labs || labs.length === 0) return;
    const W = canvasEl.width, H = canvasEl.height;

    for (let i = 0; i < labs.length; i++) {
        const lab = labs[i];
        const x = (lab.x_center - lab.width / 2) * W;
        const y = (lab.y_center - lab.height / 2) * H;
        const w = lab.width * W;
        const h = lab.height * H;

        const selected = (i === selectedId);
        ctx.lineWidth = selected ? 8 : 7;

        // choose color based on class
        const col = CLASS_COLOR[lab.cls] || "#ff0000";

        // stroke + fill
        ctx.strokeStyle = col;
        ctx.fillStyle = hexToRGBA(col, LABEL_ALPHA);

        ctx.fillRect(x, y, w, h);
        ctx.strokeRect(x, y, w, h);

        // species initials for the label badge
        const species = CLASS_MAP[lab.cls] || lab.cls;
        const initials = species.split(/\s+/).map(w => w[0]).join("").toUpperCase();
        drawClassIcon(ctx, initials, x + DEL_PAD - 5, y + DEL_PAD - 25, DEL_SIZE);

        // delete icon (top-right)
        drawDeleteIcon(ctx, x + w - DEL_PAD - DEL_SIZE, y + DEL_PAD, DEL_SIZE);

        const isTp = (lab.is_tp !== false);   // default true

        // FP icon (bottom-right): colored if NOT TP
        const fpColor = isTp ? "#333333" : col;
        drawFpIcon(ctx, x + w - DEL_PAD - DEL_SIZE, y + h - DEL_PAD - DEL_SIZE, DEL_SIZE, fpColor);

        // diagonal cross if NOT TP
        if (!isTp) {
            ctx.save();
            ctx.strokeStyle = col;
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(x, y);
            ctx.lineTo(x + w, y + h);
            ctx.moveTo(x + w, y);
            ctx.lineTo(x, y + h);
            ctx.stroke();
            ctx.restore();
        }


        if (selected) {
            drawHandles(ctx, x, y, w, h);
        }
    }
}


function hexToRGBA(hex, alpha) {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `rgba(${r},${g},${b},${alpha})`;
}


function drawHandles(ctx, x, y, w, h) {
    const r = 5;
    const pts = handlePoints(x, y, w, h);
    ctx.save();
    ctx.fillStyle = "#fff";
    ctx.strokeStyle = "red";
    ctx.lineWidth = 2;
    for (const p of pts) {
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
    }
    ctx.restore();
}

function handlePoints(x, y, w, h) {
    const cx = x + w / 2, cy = y + h / 2;
    return [
        {x, y},
        {x: cx, y},
        {x: x + w, y},
        {x: x + w, y: cy},
        {x: x + w, y: y + h},
        {x: cx, y: y + h},
        {x, y: y + h},
        {x, y: cy}
    ];
}

/* -------------------- INTERACTION (mouse) -------------------- */

function initBboxInteraction() {
    const canvas = document.getElementById("bboxCanvas");
    const img = document.getElementById("previewImage");
    if (!canvas || !img) return;

    const posFromEvent = ev => {
        const rect = canvas.getBoundingClientRect();
        const sx = canvas.width / rect.width;
        const sy = canvas.height / rect.height;
        return {
            x: (ev.clientX - rect.left) * sx,
            y: (ev.clientY - rect.top) * sy
        };
    };

    let startPt = null;
    let startBox = null;

    canvas.addEventListener("mousedown", ev => {
        const pt = posFromEvent(ev);
        const W = canvas.width, H = canvas.height;

        // Left button → create, move, resize logic
        if (ev.button === 0) {
            // --- 1) Do we hit DELETE icon? ---
            for (let i = labels.length - 1; i >= 0; i--) {
                const b = getBoxPx(labels[i], W, H);
                if (isInsideDeleteIcon(pt.x, pt.y, b.x, b.y, b.w, b.h)) {
                    labels.splice(i, 1);
                    if (selectedId !== null) {
                        if (selectedId === i) selectedId = null;
                        else if (selectedId > i) selectedId -= 1;
                    }
                    refreshUI("Label deleted.");
                    return;
                }
            }

            // --- 2) Hit FP icon? → toggle TP/FP ---
            for (let i = labels.length - 1; i >= 0; i--) {
                const b = getBoxPx(labels[i], W, H);
                if (isInsideFpIcon(pt.x, pt.y, b.x, b.y, b.w, b.h)) {
                    labels[i].is_tp = !(labels[i].is_tp !== false);
                    refreshUI("Toggled TP/FP.");
                    return;
                }
            }

            // --- 3) Hit a resize handle? ---
            const h = hitTestHandle(pt, labels, W, H);
            if (h) {
                selectedId = h.id;
                activeHandle = h.handle;
                dragMode = "resize";
                startPt = pt;
                startBox = getBoxPx(labels[selectedId], W, H);
                drawBBoxes(img, canvas, labels);
                renderLabelsList();
                return;
            }

            // --- 4) Hit an existing box? → move it
            const id = hitTestBox(pt, labels, W, H);
            if (id !== null) {
                selectedId = id;
                activeHandle = -1;
                dragMode = "move";
                startPt = pt;
                startBox = getBoxPx(labels[selectedId], W, H);
                drawBBoxes(img, canvas, labels);
                renderLabelsList();
                return;
            }

            // --- 5) Empty space → start DRAWING a new box ---
            createMode = true;
            drawStartPt = pt;
            drawPreview = { x: pt.x, y: pt.y, w: 0, h: 0 };
            dragMode = "drawing";
            selectedId = null;
            return;
        }

        // Right/middle → panning handled elsewhere
    });


    canvas.addEventListener("mousemove", ev => {
        const pt = posFromEvent(ev);
        const W = canvas.width, H = canvas.height;

        if (dragMode === "drawing" && drawStartPt) {
            const x0 = Math.min(drawStartPt.x, pt.x);
            const y0 = Math.min(drawStartPt.y, pt.y);
            const w = Math.abs(pt.x - drawStartPt.x);
            const h = Math.abs(pt.y - drawStartPt.y);
            drawPreview = {x: x0, y: y0, w, h};
            drawBBoxes(img, canvas, labels);
            const ctx = canvas.getContext("2d");
            ctx.save();
            ctx.strokeStyle = "red";
            ctx.fillStyle = "rgba(255,0,0,0.12)";
            ctx.lineWidth = 2;
            ctx.fillRect(x0, y0, w, h);
            ctx.strokeRect(x0, y0, w, h);
            ctx.restore();
            return;
        }

        if (dragMode === "idle" || selectedId === null) return;

        const lab = labels[selectedId];
        if (dragMode === "move") {
            const dx = pt.x - startPt.x;
            const dy = pt.y - startPt.y;
            let x = startBox.x + dx;
            let y = startBox.y + dy;
            x = Math.max(0, Math.min(W - startBox.w, x));
            y = Math.max(0, Math.min(H - startBox.h, y));
            lab.x_center = (x + startBox.w / 2) / W;
            lab.y_center = (y + startBox.h / 2) / H;
        } else if (dragMode === "resize") {
            const nb = resizeFromHandle(activeHandle, startBox, startPt, pt);
            nb.w = Math.max(2, Math.min(W, nb.w));
            nb.h = Math.max(2, Math.min(H, nb.h));
            nb.x = Math.max(0, Math.min(W - nb.w, nb.x));
            nb.y = Math.max(0, Math.min(H - nb.h, nb.y));
            lab.x_center = (nb.x + nb.w / 2) / W;
            lab.y_center = (nb.y + nb.h / 2) / H;
            lab.width = nb.w / W;
            lab.height = nb.h / H;
        }

        drawBBoxes(img, canvas, labels);
    });

    const endDrag = () => {
        if (dragMode === "drawing" && drawPreview && drawPreview.w >= 2 && drawPreview.h >= 2) {
            const W = canvas.width, H = canvas.height;
            const nb = drawPreview;
            labels.push({
                cls: 0,
                x_center: (nb.x + nb.w / 2) / W,
                y_center: (nb.y + nb.h / 2) / H,
                width: nb.w / W,
                height: nb.h / H,
                is_tp: true     // new boxes are TP by default
            });


            selectedId = labels.length - 1;
            drawBBoxes(img, canvas, labels);
            renderLabelsList();
            document.getElementById("numLabels").textContent = labels.length;
            setStatus("New label added.");
        }

        dragMode = "idle";
        activeHandle = -1;
        drawStartPt = null;
        drawPreview = null;
        createMode = false;
    };

    canvas.addEventListener("mouseup", endDrag);
    canvas.addEventListener("mouseleave", endDrag);
}

function getBoxPx(lab, W, H) {
    const w = lab.width * W;
    const h = lab.height * H;
    const x = lab.x_center * W - w / 2;
    const y = lab.y_center * H - h / 2;
    return {x, y, w, h};
}

function hitTestBox(pt, labs, W, H) {
    for (let i = labs.length - 1; i >= 0; i--) {
        const b = getBoxPx(labs[i], W, H);
        if (pt.x >= b.x && pt.x <= b.x + b.w && pt.y >= b.y && pt.y <= b.y + b.h) {
            return i;
        }
    }
    return null;
}

function hitTestHandle(pt, labs, W, H) {
    const r = 7;
    for (let i = labs.length - 1; i >= 0; i--) {
        const b = getBoxPx(labs[i], W, H);
        const pts = handlePoints(b.x, b.y, b.w, b.h);
        for (let h = 0; h < pts.length; h++) {
            const p = pts[h];
            const dx = pt.x - p.x;
            const dy = pt.y - p.y;
            if (dx * dx + dy * dy <= r * r) return {id: i, handle: h};
        }
    }
    return null;
}

function resizeFromHandle(handle, startBox, startPt, curPt) {
    let {x, y, w, h} = startBox;
    const dx = curPt.x - startPt.x;
    const dy = curPt.y - startPt.y;

    switch (handle) {
        case 0:
            x += dx;
            y += dy;
            w -= dx;
            h -= dy;
            break;
        case 1:
            y += dy;
            h -= dy;
            break;
        case 2:
            y += dy;
            w += dx;
            h -= dy;
            break;
        case 3:
            w += dx;
            break;
        case 4:
            w += dx;
            h += dy;
            break;
        case 5:
            h += dy;
            break;
        case 6:
            x += dx;
            w -= dx;
            h += dy;
            break;
        case 7:
            x += dx;
            w -= dx;
            break;
    }

    return {x, y, w, h};
}

/* -------------------- LABEL LIST + ZOOM -------------------- */

function refreshUI(status) {
    const img = document.getElementById("previewImage");
    const canvas = document.getElementById("bboxCanvas");
    drawBBoxes(img, canvas, labels);
    renderLabelsList();
    document.getElementById("numLabels").textContent = labels.length;
    if (status) {
        setStatus(status);
    }
}

function renderLabelsList() {
    const container = document.getElementById("labelsList");
    container.innerHTML = "";

    if (!labels || labels.length === 0) {
        container.innerHTML = '<div class="labels-empty">(no labels)</div>';
        return;
    }

    labels.forEach((lab, i) => {
        const row = document.createElement("div");
        row.classList.add("label-row");
        if (i === selectedId) {
            row.classList.add("selected");
        }
        row.dataset.id = i;

        const idx = document.createElement("button");
        idx.textContent = `#${i}`;
        idx.classList.add("mini-btn", "label-select-btn");
        idx.onclick = (e) => {
            e.stopPropagation();
            selectedId = i;
            drawBBoxes(
                document.getElementById("previewImage"),
                document.getElementById("bboxCanvas"),
                labels
            );
            renderLabelsList();
        };
        row.appendChild(idx);

        const clsInput = document.createElement("select");
        clsInput.classList.add("label-class-select");
        clsInput.setAttribute("aria-label", `Class for label ${i}`);

        CLASS_DEFS.forEach(c => {
            const opt = document.createElement("option");
            opt.value = String(c.id);
            opt.textContent = c.label;
            if (c.id === lab.cls) {
                opt.selected = true;
            }
            clsInput.appendChild(opt);
        });

        clsInput.addEventListener("change", (e) => {
            const newVal = parseInt(e.target.value, 10);
            lab.cls = isNaN(newVal) ? 0 : newVal;

            drawBBoxes(
                document.getElementById("previewImage"),
                document.getElementById("bboxCanvas"),
                labels
            );
            renderLabelsList();
        });


        row.appendChild(clsInput);

        container.appendChild(row);
    });
}

function initZoom() {
    const stage = document.getElementById("imageArea");
    if (!stage) return;

    applyTransform();  // apply initial (scale=1) transform

    // --- Zoom with mouse wheel, keeping cursor point fixed ---
    stage.addEventListener("wheel", (e) => {
        e.preventDefault();

        const rect = stage.getBoundingClientRect();
        const offsetX = e.clientX - rect.left;
        const offsetY = e.clientY - rect.top;

        const zoomFactor = 1.1;
        const oldScale = zoomState.scale;
        let newScale = oldScale * (e.deltaY < 0 ? zoomFactor : 1 / zoomFactor);

        newScale = Math.max(zoomState.minScale, Math.min(zoomState.maxScale, newScale));
        const scaleFactor = newScale / oldScale;

        // keep the point under the cursor fixed
        zoomState.x = zoomState.x + offsetX * (1 - scaleFactor);
        zoomState.y = zoomState.y + offsetY * (1 - scaleFactor);

        zoomState.scale = newScale;
        applyTransform();
    }, { passive: false });

    // --- Pan with mouse drag (middle or right button) ---
    stage.addEventListener("mousedown", (e) => {
        if (e.button !== 1 && e.button !== 2) {
            return;
        }
        e.preventDefault();
        zoomState.isPanning = true;
        zoomState.startX = e.clientX - zoomState.x;
        zoomState.startY = e.clientY - zoomState.y;
    });

    window.addEventListener("mousemove", (e) => {
        if (!zoomState.isPanning) return;
        zoomState.x = e.clientX - zoomState.startX;
        zoomState.y = e.clientY - zoomState.startY;
        applyTransform();
    });

    window.addEventListener("mouseup", () => {
        zoomState.isPanning = false;
    });

    stage.addEventListener("contextmenu", (e) => e.preventDefault());

    // Keep initial fit sane when modal/viewport size changes.
    window.addEventListener("resize", () => {
        if (!currentImage || !currentImage.name) return;
        resetZoomToFit();
    });
}

function resetZoomToFit() {
    const viewer = document.getElementById("viewer");
    const img = document.getElementById("previewImage");
    if (!viewer || !img) return;

    const imgW = img.naturalWidth;
    const imgH = img.naturalHeight;
    if (!imgW || !imgH) return;

    const vw = viewer.clientWidth;
    const vh = viewer.clientHeight;
    if (!vw || !vh) return;

    // Leave visual breathing room so the modal never starts "too zoomed".
    const fitPadding = 18;
    const fitW = Math.max(vw - fitPadding * 2, 1);
    const fitH = Math.max(vh - fitPadding * 2, 1);

    // Scale so the whole image fits in the padded viewer, never upscale above 1.
    const scale = Math.min(fitW / imgW, fitH / imgH, 1);

    zoomState.scale = scale;

    // center the image in the viewer
    const offsetX = (vw - imgW * scale) / 2;
    const offsetY = (vh - imgH * scale) / 2;
    zoomState.x = offsetX;
    zoomState.y = offsetY;

    applyTransform();
}
