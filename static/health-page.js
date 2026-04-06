(() => {
    const ESP_KEY = "agriapp_capture_esp_base";
    const espInput = document.getElementById("healthEspBase");
    const refreshBtn = document.getElementById("healthRefreshBtn");
    const updatedEl = document.getElementById("healthUpdated");

    const cells = {
        rpi: { status: document.getElementById("hc-rpi-status"), details: document.getElementById("hc-rpi-details") },
        esp: { status: document.getElementById("hc-esp-status"), details: document.getElementById("hc-esp-details") },
        camera: { status: document.getElementById("hc-camera-status"), details: document.getElementById("hc-camera-details") },
        gps: { status: document.getElementById("hc-gps-status"), details: document.getElementById("hc-gps-details") },
        bme: { status: document.getElementById("hc-bme-status"), details: document.getElementById("hc-bme-details") },
        storage: { status: document.getElementById("hc-storage-status"), details: document.getElementById("hc-storage-details") },
    };

    let timer = null;
    let busy = false;

    function fmtGb(bytes) {
        if (typeof bytes !== "number") return "n/a";
        return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    }

    function fmt1(value) {
        if (value == null) return "n/a";
        const n = Number(value);
        if (!Number.isFinite(n)) return "n/a";
        return n.toFixed(1);
    }

    function setStatus(el, raw) {
        const status = (raw || "n/a").toLowerCase();
        el.style.fontWeight = "700";
        if (status === "online") {
            el.style.color = "#198754";
        } else if (status === "warn") {
            el.style.color = "#fd7e14";
        } else if (status === "offline") {
            el.style.color = "#dc3545";
        } else {
            el.style.color = "#6c757d";
        }
        el.textContent = status;
    }

    async function fetchChecklist() {
        if (busy) return;
        busy = true;
        if (refreshBtn) refreshBtn.disabled = true;
        if (updatedEl) updatedEl.textContent = "refreshing...";
        try {
            const esp = (espInput.value || "").trim();
            if (esp) localStorage.setItem(ESP_KEY, esp);
            const q = esp ? `?esp_base=${encodeURIComponent(esp)}` : "";
            const sep = q ? "&" : "?";
            const res = await fetch(`/api/v1/health/checklist${q}${sep}_ts=${Date.now()}`, { cache: "no-store" });
            const data = await res.json();
            if (!res.ok || data.status !== "success") {
                throw new Error(data.message || `HTTP ${res.status}`);
            }
            const c = data.checklist || {};

            setStatus(cells.rpi.status, c.rpi?.status);
            cells.rpi.details.textContent = c.rpi?.hostname || "-";

            setStatus(cells.esp.status, c.esp?.status);
            cells.esp.details.textContent = c.esp?.base
                ? `${c.esp.base}${c.esp.latency_ms != null ? ` (${c.esp.latency_ms} ms)` : ""}`
                : (c.esp?.message || "-");

            setStatus(cells.camera.status, c.camera?.status);
            cells.camera.details.textContent = c.camera?.message || "-";

            setStatus(cells.gps.status, c.gps?.status);
            if (c.gps?.latitude != null && c.gps?.longitude != null) {
                cells.gps.details.textContent = `${c.gps.latitude.toFixed(6)}, ${c.gps.longitude.toFixed(6)} (${c.gps.port || "port n/a"})`;
            } else {
                cells.gps.details.textContent = "no fix";
            }

            setStatus(cells.bme.status, c.bme?.status);
            if (c.bme?.temperature != null || c.bme?.humidity != null || c.bme?.pressure != null) {
                cells.bme.details.textContent = `T=${fmt1(c.bme.temperature)} C, H=${fmt1(c.bme.humidity)} %, P=${fmt1(c.bme.pressure)} hPa`;
            } else {
                cells.bme.details.textContent = "no reading";
            }

            setStatus(cells.storage.status, c.storage?.status);
            cells.storage.details.textContent = `${fmtGb(c.storage?.free_bytes)} free / ${fmtGb(c.storage?.total_bytes)}`;

            updatedEl.textContent = new Date().toLocaleTimeString();
        } catch (e) {
            updatedEl.textContent = `error: ${e.message}`;
        } finally {
            busy = false;
            if (refreshBtn) refreshBtn.disabled = false;
        }
    }

    function init() {
        const savedEsp = localStorage.getItem(ESP_KEY) || "";
        espInput.value = savedEsp;
        refreshBtn.addEventListener("click", fetchChecklist);
        timer = setInterval(fetchChecklist, 8000);
        fetchChecklist();
        window.addEventListener("beforeunload", () => {
            if (timer) clearInterval(timer);
        });
    }

    init();
})();
