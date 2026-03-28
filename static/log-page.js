(() => {
    const linesSelect = document.getElementById("logLinesSelect");
    const connectBtn = document.getElementById("connectStreamBtn");
    const disconnectBtn = document.getElementById("disconnectStreamBtn");
    const unifiedLogBox = document.getElementById("unifiedLogBox");
    const statusBox = document.getElementById("logPageStatus");

    let es = null;

    function setStatus(msg) {
        statusBox.textContent = msg;
    }

    function closeStream() {
        if (es) {
            es.close();
            es = null;
        }
    }

    function openStream() {
        closeStream();
        const lines = parseInt(linesSelect.value || "120", 10);
        const url = `/api/v1/logs/stream?lines=${encodeURIComponent(lines)}&interval=2`;
        es = new EventSource(url);
        setStatus(`Connecting stream (${lines} lines/source)...`);

        es.onopen = () => {
            setStatus(`Stream connected at ${new Date().toLocaleTimeString()}`);
        };

        es.onmessage = (ev) => {
            try {
                const payload = JSON.parse(ev.data || "{}");
                unifiedLogBox.textContent = payload.text || "(no entries)";
                unifiedLogBox.scrollTop = unifiedLogBox.scrollHeight;
                setStatus(`Updated ${new Date().toLocaleTimeString()} (${payload.lines || lines} lines/source)`);
            } catch (_) {
                setStatus("Stream parse error");
            }
        };

        es.onerror = () => {
            setStatus("Stream disconnected. Waiting for automatic reconnect...");
        };
    }

    function wire() {
        connectBtn.addEventListener("click", openStream);
        disconnectBtn.addEventListener("click", () => {
            closeStream();
            setStatus("Stream disconnected");
        });
        linesSelect.addEventListener("change", openStream);
    }

    function init() {
        wire();
        openStream();
        window.addEventListener("beforeunload", closeStream);
    }

    init();
})();
