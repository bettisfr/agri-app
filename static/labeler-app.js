"use strict";

// -------------------- CONFIG --------------------
const APP_CONTEXT = "cimici"; // "cimici" | "insects"
const STATIC_UPLOADS_BASE = "/static/uploads/images";
const LABEL_ALPHA = 0.0; // 0 = fully transparent, 1 = fully opaque
const DEL_SIZE = 32;
const DEL_PAD = 6;

const CLASS_CONFIG = {
    cimici: [
        { id: 0, label: "Halyomorpha halys", color: "#e6194B" },
    ],
    insects: [
        { id: 0, label: "Camponotus vagus", color: "#e6194B" },
        { id: 1, label: "Plagiolepis pygmaea", color: "#3cb44b" },
        { id: 2, label: "Crematogaster scutellaris", color: "#ffe119" },
        { id: 3, label: "Temnothorax spp.", color: "#4363d8" },
        { id: 4, label: "Dolichoderus quadripunctatus", color: "#f58231" },
        { id: 5, label: "Colobopsis truncata", color: "#911eb4" },
    ],
};

const CLASS_DEFS = CLASS_CONFIG[APP_CONTEXT] || CLASS_CONFIG.cimici;

const CLASS_MAP = CLASS_DEFS.reduce((acc, c) => {
    acc[c.id] = c.label;
    return acc;
}, {});

const CLASS_COLOR = CLASS_DEFS.reduce((acc, c) => {
    acc[c.id] = c.color;
    return acc;
}, {});

// -------------------- GLOBAL STATE --------------------
let currentImage = null; // { name: "img_....jpg" }
let labels = [];
let selectedId = null;
let dragMode = "idle";
let activeHandle = -1;
let createMode = false;
let drawStartPt = null;
let drawPreview = null;

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

const byId = (id) => document.getElementById(id);

const uiList = {
    updateTpFpSummary() {
        const tpNode = byId("numTp");
        const fpNode = byId("numFp");
        if (!tpNode || !fpNode) return;

        let tp = 0;
        let fp = 0;
        for (const lab of labels) {
            if (lab && lab.is_tp === false) fp += 1;
            else tp += 1;
        }
        tpNode.textContent = String(tp);
        fpNode.textContent = String(fp);
    },

    setStatus(msg, kind = "normal") {
        const statusNode = byId("status");
        if (!statusNode) return;

        statusNode.textContent = msg;
        statusNode.style.fontWeight = "normal";
        statusNode.style.color = "#000";

        if (kind === "success") {
            statusNode.style.fontWeight = "bold";
            statusNode.style.color = "#28a745";
        } else if (kind === "error") {
            statusNode.style.fontWeight = "bold";
            statusNode.style.color = "#b00020";
        }
    },

    refresh(statusMessage) {
        const img = byId("previewImage");
        const canvas = byId("bboxCanvas");
        drawing.drawBBoxes(img, canvas, labels);
        this.renderLabelsList();

        const numLabels = byId("numLabels");
        if (numLabels) {
            numLabels.textContent = labels.length;
        }
        this.updateTpFpSummary();

        if (statusMessage) {
            this.setStatus(statusMessage);
        }
    },

    renderLabelsList() {
        const container = byId("labelsList");
        if (!container) return;
        container.innerHTML = "";

        if (!labels || labels.length === 0) {
            container.innerHTML = '<div class="labels-empty">(no labels)</div>';
            this.updateTpFpSummary();
            return;
        }

        labels.forEach((lab, i) => {
            const row = document.createElement("div");
            row.classList.add("label-row");
            if (i === selectedId) {
                row.classList.add("selected");
            }
            row.dataset.id = i;

            const selectBtn = document.createElement("button");
            selectBtn.textContent = `#${i}`;
            selectBtn.classList.add("mini-btn", "label-select-btn");
            selectBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                selectedId = i;
                const img = byId("previewImage");
                const canvas = byId("bboxCanvas");
                drawing.drawBBoxes(img, canvas, labels);
                this.renderLabelsList();
            });

            const clsInput = document.createElement("select");
            clsInput.classList.add("label-class-select");
            clsInput.setAttribute("aria-label", `Class for label ${i}`);

            CLASS_DEFS.forEach((classDef) => {
                const opt = document.createElement("option");
                opt.value = String(classDef.id);
                opt.textContent = classDef.label;
                if (classDef.id === lab.cls) {
                    opt.selected = true;
                }
                clsInput.appendChild(opt);
            });

            clsInput.addEventListener("change", (e) => {
                const newVal = parseInt(e.target.value, 10);
                lab.cls = Number.isNaN(newVal) ? 0 : newVal;

                const img = byId("previewImage");
                const canvas = byId("bboxCanvas");
                drawing.drawBBoxes(img, canvas, labels);
                this.renderLabelsList();
            });

            row.appendChild(selectBtn);
            row.appendChild(clsInput);
            container.appendChild(row);
        });
        this.updateTpFpSummary();
    },
};

const zoom = {
    applyTransform() {
        const stage = byId("imageArea");
        if (!stage) return;

        stage.style.transformOrigin = "0 0";
        stage.style.transform = `translate(${zoomState.x}px, ${zoomState.y}px) scale(${zoomState.scale})`;
    },

    init() {
        const stage = byId("imageArea");
        if (!stage) return;

        this.applyTransform();

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

            zoomState.x = zoomState.x + offsetX * (1 - scaleFactor);
            zoomState.y = zoomState.y + offsetY * (1 - scaleFactor);
            zoomState.scale = newScale;

            this.applyTransform();
        }, { passive: false });

        stage.addEventListener("mousedown", (e) => {
            if (e.button !== 1 && e.button !== 2) return;

            e.preventDefault();
            zoomState.isPanning = true;
            zoomState.startX = e.clientX - zoomState.x;
            zoomState.startY = e.clientY - zoomState.y;
        });

        window.addEventListener("mousemove", (e) => {
            if (!zoomState.isPanning) return;
            zoomState.x = e.clientX - zoomState.startX;
            zoomState.y = e.clientY - zoomState.startY;
            this.applyTransform();
        });

        window.addEventListener("mouseup", () => {
            zoomState.isPanning = false;
        });

        stage.addEventListener("contextmenu", (e) => e.preventDefault());

        window.addEventListener("resize", () => {
            if (!currentImage || !currentImage.name) return;
            this.resetToFit();
        });
    },

    resetToFit() {
        const viewer = byId("viewer");
        const img = byId("previewImage");
        if (!viewer || !img) return;

        const imgW = img.naturalWidth;
        const imgH = img.naturalHeight;
        if (!imgW || !imgH) return;

        const vw = viewer.clientWidth;
        const vh = viewer.clientHeight;
        if (!vw || !vh) return;

        const fitPadding = 18;
        const fitW = Math.max(vw - fitPadding * 2, 1);
        const fitH = Math.max(vh - fitPadding * 2, 1);
        const scale = Math.min(fitW / imgW, fitH / imgH, 1);

        zoomState.scale = scale;
        zoomState.x = (vw - imgW * scale) / 2;
        zoomState.y = (vh - imgH * scale) / 2;

        this.applyTransform();
    },
};

const drawing = {
    drawDeleteIcon(ctx, x, y, size = DEL_SIZE) {
        const r = 3;
        const w = size;
        const h = size;

        ctx.save();
        ctx.fillStyle = "rgba(255,255,255,0.95)";
        ctx.strokeStyle = "red";
        ctx.lineWidth = 1.5;
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
    },

    drawFpIcon(ctx, x, y, size = DEL_SIZE, color = "black") {
        const r = 3;
        const w = size;
        const h = size;

        ctx.save();
        ctx.fillStyle = "rgba(255,255,255,0.95)";
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;

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

        ctx.fillStyle = color;
        ctx.font = `${size * 0.65}px sans-serif`;
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText("O", x + w / 2, y + h / 2);
        ctx.restore();
    },

    drawClassIcon(ctx, text, x, y, size, color = "black") {
        const r = 3;
        const w = size;
        const h = size;

        ctx.save();
        ctx.fillStyle = "rgba(255,255,255,0.92)";
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;

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

        ctx.fillStyle = color;
        ctx.font = `${size * 0.55}px sans-serif`;
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText(String(text), x + w / 2, y + h / 2);
        ctx.restore();
    },

    isInsideDeleteIcon(px, py, boxX, boxY, boxW, _boxH) {
        const ix = boxX + boxW - DEL_PAD - DEL_SIZE;
        const iy = boxY + DEL_PAD;
        return px >= ix && px <= ix + DEL_SIZE && py >= iy && py <= iy + DEL_SIZE;
    },

    isInsideFpIcon(px, py, boxX, boxY, boxW, boxH) {
        const ix = boxX + boxW - DEL_PAD - DEL_SIZE;
        const iy = boxY + boxH - DEL_PAD - DEL_SIZE;
        return px >= ix && px <= ix + DEL_SIZE && py >= iy && py <= iy + DEL_SIZE;
    },

    fitCanvasToImage(imgEl, canvasEl) {
        const w = imgEl.naturalWidth;
        const h = imgEl.naturalHeight;
        if (!w || !h) return;

        imgEl.style.width = `${w}px`;
        imgEl.style.height = `${h}px`;

        canvasEl.width = w;
        canvasEl.height = h;
        canvasEl.style.width = `${w}px`;
        canvasEl.style.height = `${h}px`;
    },

    hexToRGBA(hex, alpha) {
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `rgba(${r},${g},${b},${alpha})`;
    },

    handlePoints(x, y, w, h) {
        const cx = x + w / 2;
        const cy = y + h / 2;
        return [
            { x, y },
            { x: cx, y },
            { x: x + w, y },
            { x: x + w, y: cy },
            { x: x + w, y: y + h },
            { x: cx, y: y + h },
            { x, y: y + h },
            { x, y: cy },
        ];
    },

    drawHandles(ctx, x, y, w, h) {
        const points = this.handlePoints(x, y, w, h);

        ctx.save();
        ctx.fillStyle = "#fff";
        ctx.strokeStyle = "red";
        ctx.lineWidth = 2;

        for (const point of points) {
            ctx.beginPath();
            ctx.arc(point.x, point.y, 5, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();
        }

        ctx.restore();
    },

    getBoxPx(lab, width, height) {
        const w = lab.width * width;
        const h = lab.height * height;
        const x = lab.x_center * width - w / 2;
        const y = lab.y_center * height - h / 2;
        return { x, y, w, h };
    },

    hitTestBox(point, labs, width, height) {
        for (let i = labs.length - 1; i >= 0; i -= 1) {
            const box = this.getBoxPx(labs[i], width, height);
            if (point.x >= box.x && point.x <= box.x + box.w && point.y >= box.y && point.y <= box.y + box.h) {
                return i;
            }
        }
        return null;
    },

    hitTestHandle(point, labs, width, height) {
        const radius = 7;

        for (let i = labs.length - 1; i >= 0; i -= 1) {
            const box = this.getBoxPx(labs[i], width, height);
            const handlePoints = this.handlePoints(box.x, box.y, box.w, box.h);

            for (let h = 0; h < handlePoints.length; h += 1) {
                const hp = handlePoints[h];
                const dx = point.x - hp.x;
                const dy = point.y - hp.y;
                if (dx * dx + dy * dy <= radius * radius) {
                    return { id: i, handle: h };
                }
            }
        }

        return null;
    },

    resizeFromHandle(handle, startBox, startPt, currentPt) {
        let { x, y, w, h } = startBox;
        const dx = currentPt.x - startPt.x;
        const dy = currentPt.y - startPt.y;

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
            default:
                break;
        }

        return { x, y, w, h };
    },

    drawBBoxes(imgEl, canvasEl, labs) {
        if (!imgEl || !canvasEl) return;

        const ctx = canvasEl.getContext("2d");
        ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);

        if (!labs || labs.length === 0) return;

        const width = canvasEl.width;
        const height = canvasEl.height;

        for (let i = 0; i < labs.length; i += 1) {
            const lab = labs[i];
            const x = (lab.x_center - lab.width / 2) * width;
            const y = (lab.y_center - lab.height / 2) * height;
            const w = lab.width * width;
            const h = lab.height * height;

            const selected = i === selectedId;
            const color = CLASS_COLOR[lab.cls] || "#ff0000";

            ctx.lineWidth = selected ? 8 : 7;
            ctx.strokeStyle = color;
            ctx.fillStyle = this.hexToRGBA(color, LABEL_ALPHA);
            ctx.fillRect(x, y, w, h);
            ctx.strokeRect(x, y, w, h);

            const species = CLASS_MAP[lab.cls] || lab.cls;
            const initials = species.split(/\s+/).map((part) => part[0]).join("").toUpperCase();
            this.drawClassIcon(ctx, initials, x + DEL_PAD - 5, y + DEL_PAD - 25, DEL_SIZE);

            this.drawDeleteIcon(ctx, x + w - DEL_PAD - DEL_SIZE, y + DEL_PAD, DEL_SIZE);

            const isTp = lab.is_tp !== false;
            const fpColor = isTp ? "#333333" : color;
            this.drawFpIcon(ctx, x + w - DEL_PAD - DEL_SIZE, y + h - DEL_PAD - DEL_SIZE, DEL_SIZE, fpColor);

            if (!isTp) {
                ctx.save();
                ctx.strokeStyle = color;
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
                this.drawHandles(ctx, x, y, w, h);
            }
        }
    },

    initInteraction() {
        const canvas = byId("bboxCanvas");
        const img = byId("previewImage");
        if (!canvas || !img) return;

        const posFromEvent = (event) => {
            const rect = canvas.getBoundingClientRect();
            const sx = canvas.width / rect.width;
            const sy = canvas.height / rect.height;
            return {
                x: (event.clientX - rect.left) * sx,
                y: (event.clientY - rect.top) * sy,
            };
        };

        let startPt = null;
        let startBox = null;

        canvas.addEventListener("mousedown", (event) => {
            const point = posFromEvent(event);
            const width = canvas.width;
            const height = canvas.height;

            if (event.button === 0) {
                for (let i = labels.length - 1; i >= 0; i -= 1) {
                    const box = this.getBoxPx(labels[i], width, height);
                    if (this.isInsideDeleteIcon(point.x, point.y, box.x, box.y, box.w, box.h)) {
                        labels.splice(i, 1);
                        if (selectedId !== null) {
                            if (selectedId === i) {
                                selectedId = null;
                            } else if (selectedId > i) {
                                selectedId -= 1;
                            }
                        }
                        uiList.refresh("Label deleted.");
                        return;
                    }
                }

                for (let i = labels.length - 1; i >= 0; i -= 1) {
                    const box = this.getBoxPx(labels[i], width, height);
                    if (this.isInsideFpIcon(point.x, point.y, box.x, box.y, box.w, box.h)) {
                        labels[i].is_tp = !(labels[i].is_tp !== false);
                        uiList.refresh("Toggled TP/FP.");
                        return;
                    }
                }

                const handleHit = this.hitTestHandle(point, labels, width, height);
                if (handleHit) {
                    selectedId = handleHit.id;
                    activeHandle = handleHit.handle;
                    dragMode = "resize";
                    startPt = point;
                    startBox = this.getBoxPx(labels[selectedId], width, height);
                    this.drawBBoxes(img, canvas, labels);
                    uiList.renderLabelsList();
                    return;
                }

                const boxId = this.hitTestBox(point, labels, width, height);
                if (boxId !== null) {
                    selectedId = boxId;
                    activeHandle = -1;
                    dragMode = "move";
                    startPt = point;
                    startBox = this.getBoxPx(labels[selectedId], width, height);
                    this.drawBBoxes(img, canvas, labels);
                    uiList.renderLabelsList();
                    return;
                }

                createMode = true;
                drawStartPt = point;
                drawPreview = { x: point.x, y: point.y, w: 0, h: 0 };
                dragMode = "drawing";
                selectedId = null;
            }
        });

        canvas.addEventListener("mousemove", (event) => {
            const point = posFromEvent(event);
            const width = canvas.width;
            const height = canvas.height;

            if (dragMode === "drawing" && drawStartPt) {
                const x0 = Math.min(drawStartPt.x, point.x);
                const y0 = Math.min(drawStartPt.y, point.y);
                const w = Math.abs(point.x - drawStartPt.x);
                const h = Math.abs(point.y - drawStartPt.y);
                drawPreview = { x: x0, y: y0, w, h };

                this.drawBBoxes(img, canvas, labels);
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
                const dx = point.x - startPt.x;
                const dy = point.y - startPt.y;
                let x = startBox.x + dx;
                let y = startBox.y + dy;

                x = Math.max(0, Math.min(width - startBox.w, x));
                y = Math.max(0, Math.min(height - startBox.h, y));
                lab.x_center = (x + startBox.w / 2) / width;
                lab.y_center = (y + startBox.h / 2) / height;
            } else if (dragMode === "resize") {
                const nextBox = this.resizeFromHandle(activeHandle, startBox, startPt, point);
                nextBox.w = Math.max(2, Math.min(width, nextBox.w));
                nextBox.h = Math.max(2, Math.min(height, nextBox.h));
                nextBox.x = Math.max(0, Math.min(width - nextBox.w, nextBox.x));
                nextBox.y = Math.max(0, Math.min(height - nextBox.h, nextBox.y));

                lab.x_center = (nextBox.x + nextBox.w / 2) / width;
                lab.y_center = (nextBox.y + nextBox.h / 2) / height;
                lab.width = nextBox.w / width;
                lab.height = nextBox.h / height;
            }

            this.drawBBoxes(img, canvas, labels);
        });

        const endDrag = () => {
            if (dragMode === "drawing" && drawPreview && drawPreview.w >= 2 && drawPreview.h >= 2) {
                const width = canvas.width;
                const height = canvas.height;
                const box = drawPreview;

                labels.push({
                    cls: 0,
                    x_center: (box.x + box.w / 2) / width,
                    y_center: (box.y + box.h / 2) / height,
                    width: box.w / width,
                    height: box.h / height,
                    is_tp: true,
                });

                selectedId = labels.length - 1;
                this.drawBBoxes(img, canvas, labels);
                uiList.renderLabelsList();

                const numLabels = byId("numLabels");
                if (numLabels) {
                    numLabels.textContent = labels.length;
                }
                uiList.setStatus("New label added.");
            }

            dragMode = "idle";
            activeHandle = -1;
            drawStartPt = null;
            drawPreview = null;
            createMode = false;
        };

        canvas.addEventListener("mouseup", endDrag);
        canvas.addEventListener("mouseleave", endDrag);
    },
};

const api = {
    parseYoloTxt(text) {
        const lines = text.split(/\r?\n/);
        const out = [];
        for (const line of lines) {
            const parts = line.trim().split(/\s+/);
            if (parts.length !== 5) continue;

            const [cls, xc, yc, w, h] = parts.map(Number);
            out.push({
                cls,
                x_center: xc,
                y_center: yc,
                width: w,
                height: h,
                is_fp: false,
            });
        }
        return out;
    },

    async saveLabels() {
        if (!currentImage || !currentImage.name) {
            uiList.setStatus("No image loaded, cannot save.", "error");
            return;
        }

        try {
            const payload = {
                image: currentImage.name,
                labels: labels.map((l) => ({
                    cls: l.cls ?? 0,
                    x_center: l.x_center,
                    y_center: l.y_center,
                    width: l.width,
                    height: l.height,
                    is_tp: l.is_tp !== false,
                })),
            };

            const res = await fetch("/api/v1/labels", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload),
            });

            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }

            const data = await res.json();
            if (data.status !== "success") {
                throw new Error(data.message || "Unknown error");
            }

            uiList.setStatus(data.message || "Labels saved.", "success");
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({ type: "labeler-saved", image: currentImage.name }, "*");
            }
        } catch (err) {
            uiList.setStatus(
                `Save failed: ${err && err.message ? err.message : err}`,
                "error",
            );
        }
    },

    initFromQueryParam() {
        const params = new URLSearchParams(window.location.search);
        const imageName = params.get("image");

        if (!imageName) {
            uiList.setStatus("No image specified. Call as /label?image=filename.jpg");
            return;
        }

        this.loadServerImage(imageName);
    },

    async loadServerImage(filename) {
        const img = byId("previewImage");
        const canvas = byId("bboxCanvas");
        if (!img || !canvas) return;

        const imageUrl = `${STATIC_UPLOADS_BASE}/${filename}`;

        img.onload = () => {
            drawing.fitCanvasToImage(img, canvas);
            zoom.resetToFit();
            drawing.drawBBoxes(img, canvas, labels);
        };

        img.onerror = () => {
            uiList.setStatus(`Cannot load image: ${imageUrl}`);
        };

        img.src = imageUrl;

        currentImage = { name: filename };
        labels = [];
        selectedId = null;

        try {
            const res = await fetch(`/api/v1/labels?image=${encodeURIComponent(filename)}`);
            if (res.ok) {
                const data = await res.json();
                if (data.status === "success" && Array.isArray(data.labels)) {
                    labels = data.labels.map((l) => ({
                        cls: l.cls ?? 0,
                        x_center: l.x_center,
                        y_center: l.y_center,
                        width: l.width,
                        height: l.height,
                        is_tp: l.is_tp !== false,
                    }));
                }
            }
        } catch (err) {
            console.warn("Error while fetching labels:", err);
        }

        drawing.drawBBoxes(img, canvas, labels);
        uiList.renderLabelsList();

        const imgName = byId("imgName");
        const numLabels = byId("numLabels");
        const controls = byId("controlsArea");

        if (imgName) imgName.textContent = filename;
        if (numLabels) numLabels.textContent = labels.length;
        uiList.updateTpFpSummary();
        if (controls) controls.classList.remove("disabled");

        uiList.setStatus(`Loaded ${filename} (${labels.length} labels)`);
    },
};

// -------------------- INIT --------------------
document.addEventListener("DOMContentLoaded", () => {
    drawing.initInteraction();
    zoom.init();

    uiList.setStatus("Waiting for image parameter (?image=...).");

    const saveBtn = byId("saveTxtBtn");
    if (saveBtn) {
        saveBtn.addEventListener("click", () => {
            api.saveLabels();
        });
    }

    const downloadBtn = byId("downloadCurrentBtn");
    if (downloadBtn) {
        downloadBtn.addEventListener("click", () => {
            if (!currentImage || !currentImage.name) {
                uiList.setStatus("No image loaded, cannot download.", "error");
                return;
            }
            const url = `/api/v1/images/download-with-labels?image=${encodeURIComponent(currentImage.name)}`;
            window.location.href = url;
        });
    }

    api.initFromQueryParam();
});
