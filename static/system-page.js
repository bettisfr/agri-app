(() => {
    const PREF_WIFI_KEY = "agriapp_preferred_wifi";
    const LAST_WIFI_URL_KEY = "agriapp_last_wifi_url";
    const ESP_BASE_KEY = "agriapp_capture_esp_base";
    const defaultWifiProfiles = ["preconfigured", "castelnuovo", "hotspot"];

    const state = {
        preferredWifi: localStorage.getItem(PREF_WIFI_KEY) || "preconfigured",
        busy: false,
    };

    const sysHost = document.getElementById("sysHost");
    const sysFree = document.getElementById("sysFree");
    const netMode = document.getElementById("netMode");
    const netActiveWifi = document.getElementById("netActiveWifi");
    const wifiBadge = document.getElementById("wifiBadge");
    const apBadge = document.getElementById("apBadge");
    const rpiStatusBadge = document.getElementById("rpiStatusBadge");
    const espStatusBadge = document.getElementById("espStatusBadge");
    const accessUrlLink = document.getElementById("accessUrlLink");
    const systemMessage = document.getElementById("systemMessage");

    const wifiButtons = Array.from(document.querySelectorAll(".wifi-btn"));
    const setWifiBtn = document.getElementById("setWifiBtn");
    const setApBtn = document.getElementById("setApBtn");
    const refreshSystemBtn = document.getElementById("refreshSystemBtn");
    const restartServerBtn = document.getElementById("restartServerBtn");
    const rebootBtn = document.getElementById("rebootBtn");
    const poweroffBtn = document.getElementById("poweroffBtn");

    function setMessage(line) {
        systemMessage.textContent = line;
    }

    function setOnlineBadge(el, online, labelWhenOffline = "offline", labelWhenOnline = "online") {
        if (!el) return;
        el.style.fontWeight = "700";
        if (online) {
            el.style.color = "#198754";
            el.textContent = labelWhenOnline;
        } else {
            el.style.color = "#dc3545";
            el.textContent = labelWhenOffline;
        }
    }

    function isApHost(hostname) {
        return hostname === "192.168.4.1";
    }

    function getPreferredWifiUrl() {
        const saved = localStorage.getItem(LAST_WIFI_URL_KEY);
        if (saved) return saved;
        return "http://raspberrypi.local:5000";
    }

    function setAccessUrl(url) {
        if (!url) {
            accessUrlLink.textContent = "n/a";
            accessUrlLink.removeAttribute("href");
            return;
        }
        accessUrlLink.textContent = url;
        accessUrlLink.href = url;
    }

    function setBusy(v) {
        state.busy = v;
        const d = !!v;
        setWifiBtn.disabled = d;
        setApBtn.disabled = d;
        refreshSystemBtn.disabled = d;
        restartServerBtn.disabled = d;
        rebootBtn.disabled = d;
        poweroffBtn.disabled = d;
        wifiButtons.forEach((b) => (b.disabled = d));
    }

    function renderPreferredWifi() {
        wifiButtons.forEach((btn) => {
            const wifi = btn.dataset.wifi || "";
            const active = wifi === state.preferredWifi;
            btn.classList.toggle("btn-secondary", active);
            btn.classList.toggle("btn-outline-secondary", !active);
            btn.textContent = active ? `• ${wifi}` : wifi;
        });
    }

    function toGb(bytes) {
        if (typeof bytes !== "number") return "n/a";
        return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
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
            throw new Error(data?.message || txt || `HTTP ${res.status}`);
        }
        return data;
    }

    async function refreshStatus() {
        const [sys, net] = await Promise.all([
            apiJson("/api/v1/system/status"),
            apiJson("/api/v1/network/mode"),
        ]);

        sysHost.textContent = sys.hostname || "n/a";
        sysFree.textContent = `${toGb(sys.disk_free_bytes)} / ${toGb(sys.disk_total_bytes)}`;

        netMode.textContent = net.mode || "n/a";
        netActiveWifi.textContent = (net.active_wifi_connections || []).join(", ") || "n/a";
        wifiBadge.textContent = net.client_active ? "ON" : "OFF";
        apBadge.textContent = net.ap_active ? "ON" : "OFF";

        const here = window.location.origin;
        if (!isApHost(window.location.hostname)) {
            localStorage.setItem(LAST_WIFI_URL_KEY, here);
        }
        if (net.mode === "ap_only") {
            setAccessUrl("http://192.168.4.1:5000");
        } else if (net.mode === "wifi_only") {
            setAccessUrl(isApHost(window.location.hostname) ? getPreferredWifiUrl() : here);
        } else {
            setAccessUrl(here);
        }

        const known = (net.active_wifi_connections || [])
            .filter((n) => n && n !== net.ap_connection);
        const merged = Array.from(new Set([...defaultWifiProfiles, ...known]));
        if (!merged.includes(state.preferredWifi)) {
            state.preferredWifi = net.wifi_connection || merged[0] || "preconfigured";
            localStorage.setItem(PREF_WIFI_KEY, state.preferredWifi);
        }
        renderPreferredWifi();

        // RPi status (same backend)
        setOnlineBadge(rpiStatusBadge, true, "offline", "online");

        // ESP status (via backend probe to avoid CORS)
        const espBase = (localStorage.getItem(ESP_BASE_KEY) || "").trim();
        if (!espBase) {
            espStatusBadge.style.fontWeight = "700";
            espStatusBadge.style.color = "#6c757d";
            espStatusBadge.textContent = "n/a";
        } else {
            try {
                const espRes = await apiJson(`/api/v1/esp/status?esp_base=${encodeURIComponent(espBase)}`);
                if (espRes.reachable) {
                    const ms = typeof espRes.latency_ms === "number" ? `${espRes.latency_ms} ms` : "ok";
                    setOnlineBadge(espStatusBadge, true, "offline", `online (${ms})`);
                } else {
                    setOnlineBadge(espStatusBadge, false, "offline", "online");
                }
            } catch (_) {
                setOnlineBadge(espStatusBadge, false, "offline", "online");
            }
        }
    }

    async function setNetworkMode(mode) {
        if (mode === "ap_only") {
            setAccessUrl("http://192.168.4.1:5000");
        } else if (mode === "wifi_only") {
            setAccessUrl(getPreferredWifiUrl());
        }

        const body =
            mode === "wifi_only"
                ? { mode, wifi_connection: state.preferredWifi }
                : { mode };
        const res = await apiJson("/api/v1/network/mode", {
            method: "POST",
            body: JSON.stringify(body),
        });
        setMessage(`Network ${res.status}: ${res.mode || mode}. Open: ${accessUrlLink.textContent}`);
    }

    async function doAction(path, label) {
        const res = await apiJson(path, { method: "POST", body: "{}" });
        setMessage(`${label}: ${res.status} ${res.message || ""}`.trim());
    }

    function wire() {
        wifiButtons.forEach((btn) => {
            btn.addEventListener("click", () => {
                state.preferredWifi = btn.dataset.wifi || "preconfigured";
                localStorage.setItem(PREF_WIFI_KEY, state.preferredWifi);
                renderPreferredWifi();
                setMessage(`Preferred WiFi: ${state.preferredWifi}`);
            });
        });

        refreshSystemBtn.addEventListener("click", async () => {
            try {
                setBusy(true);
                await refreshStatus();
                setMessage("System refreshed");
            } catch (e) {
                setMessage(`Refresh failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        setWifiBtn.addEventListener("click", async () => {
            try {
                setBusy(true);
                await setNetworkMode("wifi_only");
                await refreshStatus();
            } catch (e) {
                setMessage(`WiFi mode failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        setApBtn.addEventListener("click", async () => {
            try {
                setBusy(true);
                await setNetworkMode("ap_only");
                await refreshStatus();
            } catch (e) {
                setMessage(`AP mode failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        restartServerBtn.addEventListener("click", async () => {
            try {
                setBusy(true);
                await doAction("/api/v1/system/server/restart", "Restart server");
            } catch (e) {
                setMessage(`Restart server failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        rebootBtn.addEventListener("click", async () => {
            if (!window.confirm("Reboot Raspberry Pi?")) return;
            try {
                setBusy(true);
                await doAction("/api/v1/system/reboot", "Reboot");
            } catch (e) {
                setMessage(`Reboot failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        poweroffBtn.addEventListener("click", async () => {
            if (!window.confirm("Poweroff Raspberry Pi?")) return;
            try {
                setBusy(true);
                await doAction("/api/v1/system/poweroff", "Poweroff");
            } catch (e) {
                setMessage(`Poweroff failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });
    }

    async function init() {
        renderPreferredWifi();
        wire();
        try {
            setBusy(true);
            await refreshStatus();
            setMessage("Ready");
        } catch (e) {
            setMessage(`Init failed: ${e.message}`);
        } finally {
            setBusy(false);
        }
    }

    init();
})();
