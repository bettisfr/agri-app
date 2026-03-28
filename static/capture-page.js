(() => {
    const SOURCE_KEY = "agriapp_capture_source";
    const INTERVAL_KEY = "agriapp_capture_interval";
    const ESP_BASE_KEY = "agriapp_capture_esp_base";

    const state = {
        source: localStorage.getItem(SOURCE_KEY) || "rpi",
        interval: parseInt(localStorage.getItem(INTERVAL_KEY) || "300", 10),
        espBase: localStorage.getItem(ESP_BASE_KEY) || "http://192.168.4.239",
        busy: false,
    };

    const allowedIntervals = new Set([30, 60, 120, 180, 300]);
    if (!allowedIntervals.has(state.interval)) {
        state.interval = 300;
    }

    const sourceButtons = Array.from(document.querySelectorAll(".source-btn"));
    const intervalButtons = Array.from(document.querySelectorAll(".interval-btn"));
    const espBaseInput = document.getElementById("espBaseInput");
    const saveEspBtn = document.getElementById("saveEspBtn");
    const shotBtn = document.getElementById("shotBtn");
    const startAutoBtn = document.getElementById("startAutoBtn");
    const stopAutoBtn = document.getElementById("stopAutoBtn");
    const rpiLoopStatus = document.getElementById("rpiLoopStatus");
    const espLoopStatus = document.getElementById("espLoopStatus");
    const effectiveInterval = document.getElementById("effectiveInterval");
    const captureMessage = document.getElementById("captureMessage");

    let statusTimer = null;

    function setMessage(line) {
        captureMessage.textContent = line;
    }

    function normalizeBase(raw) {
        const v = (raw || "").trim();
        if (!v) return "";
        if (v.startsWith("http://") || v.startsWith("https://")) return v.replace(/\/+$/, "");
        return `http://${v}`.replace(/\/+$/, "");
    }

    async function apiJson(path, init = {}) {
        const res = await fetch(path, {
            ...init,
            headers: {
                "Content-Type": "application/json",
                ...(init.headers || {}),
            },
        });
        const txt = await res.text();
        let data = null;
        try {
            data = txt ? JSON.parse(txt) : null;
        } catch (_) {
            data = null;
        }
        if (!res.ok) {
            const errMsg = data?.message || txt || `HTTP ${res.status}`;
            throw new Error(errMsg);
        }
        return data;
    }

    function setBusy(v) {
        state.busy = v;
        const disabled = !!v;
        shotBtn.disabled = disabled;
        startAutoBtn.disabled = disabled;
        saveEspBtn.disabled = disabled;
        sourceButtons.forEach((b) => (b.disabled = disabled));
        intervalButtons.forEach((b) => (b.disabled = disabled));
    }

    function renderSource() {
        sourceButtons.forEach((btn) => {
            const active = btn.dataset.source === state.source;
            btn.classList.toggle("btn-success", active);
            btn.classList.toggle("btn-outline-success", !active);
            btn.textContent = active ? `• ${btn.dataset.source.toUpperCase()}` : btn.dataset.source.toUpperCase();
        });
    }

    function renderInterval() {
        intervalButtons.forEach((btn) => {
            const sec = parseInt(btn.dataset.interval || "0", 10);
            const active = sec === state.interval;
            btn.classList.toggle("btn-primary", active);
            btn.classList.toggle("btn-outline-primary", !active);
            btn.textContent = active ? `• ${btn.textContent.replace(/^•\s*/, "")}` : btn.textContent.replace(/^•\s*/, "");
        });
    }

    function formatLoopStatus(payload) {
        if (!payload) return "n/a";
        if (payload.running) {
            const sec = payload.interval_seconds ? ` (${payload.interval_seconds}s)` : "";
            return `running${sec}`;
        }
        return "stopped";
    }

    async function refreshLoopStatus() {
        try {
            const [rpi, esp] = await Promise.all([
                apiJson("/api/v1/capture/loop/status"),
                apiJson("/api/v1/capture/esp/loop/status").catch(() => null),
            ]);
            rpiLoopStatus.textContent = formatLoopStatus(rpi);
            espLoopStatus.textContent = formatLoopStatus(esp);
            stopAutoBtn.disabled = state.busy || (!rpi?.running && !esp?.running);

            let inferred = null;
            if (state.source === "esp") {
                inferred = esp?.running ? esp?.interval_seconds : null;
            } else if (state.source === "both") {
                inferred = rpi?.running ? rpi?.interval_seconds : (esp?.running ? esp?.interval_seconds : null);
            } else {
                inferred = rpi?.running ? rpi?.interval_seconds : null;
            }

            if (typeof inferred === "number") {
                effectiveInterval.textContent = `${inferred}s`;
                if (allowedIntervals.has(inferred) && state.interval !== inferred) {
                    state.interval = inferred;
                    persistState();
                    renderInterval();
                }
            } else {
                effectiveInterval.textContent = `${state.interval}s (selected)`;
            }
        } catch (e) {
            rpiLoopStatus.textContent = "n/a";
            espLoopStatus.textContent = "n/a";
            effectiveInterval.textContent = "n/a";
            if (!state.busy) {
                setMessage(`Status error: ${e.message}`);
            }
        }
    }

    async function oneShotRpi() {
        const res = await apiJson("/api/v1/capture/rpi/oneshot", { method: "POST", body: "{}" });
        setMessage(`Shot RPi: ${res.latest_filename || "ok"}`);
    }

    async function oneShotEsp() {
        const espBase = normalizeBase(espBaseInput.value || state.espBase);
        if (!espBase) throw new Error("ESP base URL missing");
        const url = `/api/v1/capture/esp/oneshot?esp_base=${encodeURIComponent(espBase)}`;
        const res = await fetch(url, { method: "GET" });
        if (!res.ok) {
            const txt = await res.text();
            let msg = txt;
            try {
                const j = txt ? JSON.parse(txt) : null;
                msg = j?.message || txt;
            } catch (_) {}
            throw new Error(msg || `HTTP ${res.status}`);
        }
        const filename = res.headers.get("X-AgriApp-Filename") || "esp_capture.jpg";
        setMessage(`Shot ESP: ${filename}`);
    }

    async function startAutoRpi() {
        const res = await apiJson("/api/v1/capture/loop/start", {
            method: "POST",
            body: JSON.stringify({ interval_seconds: state.interval }),
        });
        setMessage(`Auto RPi start: ${res.running ? "running" : "stopped"} (${res.interval_seconds || state.interval}s)`);
    }

    async function startAutoEsp() {
        const espBase = normalizeBase(espBaseInput.value || state.espBase);
        if (!espBase) throw new Error("ESP base URL missing");
        const res = await apiJson("/api/v1/capture/esp/loop/start", {
            method: "POST",
            body: JSON.stringify({ interval_seconds: state.interval, esp_base: espBase }),
        });
        setMessage(`Auto ESP start: ${res.running ? "running" : "stopped"} (${res.interval_seconds || state.interval}s)`);
    }

    async function stopAutoRpi() {
        const res = await apiJson("/api/v1/capture/loop/stop", { method: "POST", body: "{}" });
        setMessage(`Auto RPi stop: ${res.running ? "running" : "stopped"}`);
    }

    async function stopAutoEsp() {
        const res = await apiJson("/api/v1/capture/esp/loop/stop", { method: "POST", body: "{}" });
        setMessage(`Auto ESP stop: ${res.running ? "running" : "stopped"}`);
    }

    function persistState() {
        localStorage.setItem(SOURCE_KEY, state.source);
        localStorage.setItem(INTERVAL_KEY, String(state.interval));
        localStorage.setItem(ESP_BASE_KEY, state.espBase);
    }

    async function onShot() {
        try {
            setBusy(true);
            if (state.source === "rpi") {
                await oneShotRpi();
            } else if (state.source === "esp") {
                await oneShotEsp();
            } else {
                await oneShotRpi();
                await oneShotEsp();
            }
        } catch (e) {
            setMessage(`Shot failed: ${e.message}`);
        } finally {
            setBusy(false);
            await refreshLoopStatus();
        }
    }

    async function onStartAuto() {
        try {
            setBusy(true);
            if (state.source === "rpi") {
                await startAutoRpi();
            } else if (state.source === "esp") {
                await startAutoEsp();
            } else {
                await startAutoRpi();
                await startAutoEsp();
            }
        } catch (e) {
            setMessage(`Auto start failed: ${e.message}`);
        } finally {
            setBusy(false);
            await refreshLoopStatus();
        }
    }

    async function onStopAuto() {
        try {
            setBusy(true);
            if (state.source === "rpi") {
                await stopAutoRpi();
            } else if (state.source === "esp") {
                await stopAutoEsp();
            } else {
                await stopAutoRpi();
                await stopAutoEsp();
            }
        } catch (e) {
            setMessage(`Auto stop failed: ${e.message}`);
        } finally {
            setBusy(false);
            await refreshLoopStatus();
        }
    }

    function wireEvents() {
        sourceButtons.forEach((btn) => {
            btn.addEventListener("click", () => {
                state.source = btn.dataset.source || "rpi";
                renderSource();
                persistState();
            });
        });

        intervalButtons.forEach((btn) => {
            btn.addEventListener("click", () => {
                const sec = parseInt(btn.dataset.interval || "0", 10);
                if (!allowedIntervals.has(sec)) return;
                state.interval = sec;
                renderInterval();
                persistState();
            });
        });

        saveEspBtn.addEventListener("click", () => {
            state.espBase = normalizeBase(espBaseInput.value || "");
            espBaseInput.value = state.espBase;
            persistState();
            setMessage(`ESP host saved: ${state.espBase || "n/a"}`);
        });

        shotBtn.addEventListener("click", onShot);
        startAutoBtn.addEventListener("click", onStartAuto);
        stopAutoBtn.addEventListener("click", onStopAuto);
    }

    async function init() {
        espBaseInput.value = state.espBase;
        renderSource();
        renderInterval();
        wireEvents();
        setMessage("Ready");
        await refreshLoopStatus();
        statusTimer = window.setInterval(refreshLoopStatus, 10000);
    }

    window.addEventListener("beforeunload", () => {
        if (statusTimer) {
            window.clearInterval(statusTimer);
            statusTimer = null;
        }
    });

    init();
})();
