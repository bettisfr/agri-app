(() => {
    const INTERVAL_KEY = "agriapp_capture_interval";

    const state = {
        interval: parseInt(localStorage.getItem(INTERVAL_KEY) || "300", 10),
        busy: false,
    };

    const allowedIntervals = new Set([30, 60, 120, 180, 300]);
    if (!allowedIntervals.has(state.interval)) {
        state.interval = 300;
    }

    const intervalButtons = Array.from(document.querySelectorAll(".interval-btn"));
    const shotBtn = document.getElementById("shotBtn");
    const startAutoBtn = document.getElementById("startAutoBtn");
    const stopAutoBtn = document.getElementById("stopAutoBtn");
    const rpiLoopStatus = document.getElementById("rpiLoopStatus");
    const effectiveInterval = document.getElementById("effectiveInterval");
    const captureMessage = document.getElementById("captureMessage");

    let statusTimer = null;

    function setMessage(line) {
        captureMessage.textContent = line;
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
        intervalButtons.forEach((b) => (b.disabled = disabled));
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
            const rpi = await apiJson("/api/v1/capture/loop/status");
            rpiLoopStatus.textContent = formatLoopStatus(rpi);
            stopAutoBtn.disabled = state.busy || !rpi?.running;

            const inferred = rpi?.running ? rpi?.interval_seconds : null;

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

    async function startAutoRpi() {
        const res = await apiJson("/api/v1/capture/loop/start", {
            method: "POST",
            body: JSON.stringify({ interval_seconds: state.interval }),
        });
        setMessage(`Auto RPi start: ${res.running ? "running" : "stopped"} (${res.interval_seconds || state.interval}s)`);
    }

    async function stopAutoRpi() {
        const res = await apiJson("/api/v1/capture/loop/stop", { method: "POST", body: "{}" });
        setMessage(`Auto RPi stop: ${res.running ? "running" : "stopped"}`);
    }

    function persistState() {
        localStorage.setItem(INTERVAL_KEY, String(state.interval));
    }

    async function onShot() {
        try {
            setBusy(true);
            await oneShotRpi();
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
            await startAutoRpi();
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
            await stopAutoRpi();
        } catch (e) {
            setMessage(`Auto stop failed: ${e.message}`);
        } finally {
            setBusy(false);
            await refreshLoopStatus();
        }
    }

    function wireEvents() {
        intervalButtons.forEach((btn) => {
            btn.addEventListener("click", () => {
                const sec = parseInt(btn.dataset.interval || "0", 10);
                if (!allowedIntervals.has(sec)) return;
                state.interval = sec;
                renderInterval();
                persistState();
            });
        });

        shotBtn.addEventListener("click", onShot);
        startAutoBtn.addEventListener("click", onStartAuto);
        stopAutoBtn.addEventListener("click", onStopAuto);
    }

    async function init() {
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
