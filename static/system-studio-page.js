(() => {
    const importDatasetZip = document.getElementById("importDatasetZip");
    const importOverwrite = document.getElementById("importOverwrite");
    const importDatasetBtn = document.getElementById("importDatasetBtn");
    const importReport = document.getElementById("importReport");
    const systemMessage = document.getElementById("systemMessage");
    const lastMaskStatus = document.getElementById("lastMaskStatus");
    const lastMaskUpdated = document.getElementById("lastMaskUpdated");
    const lastMaskSize = document.getElementById("lastMaskSize");
    const refreshLastMaskBtn = document.getElementById("refreshLastMaskBtn");
    const deleteLastMaskBtn = document.getElementById("deleteLastMaskBtn");

    function setMessage(line) {
        if (systemMessage) systemMessage.textContent = line;
    }

    function setBusy(v) {
        const d = !!v;
        if (importDatasetZip) importDatasetZip.disabled = d;
        if (importOverwrite) importOverwrite.disabled = d;
        if (importDatasetBtn) importDatasetBtn.disabled = d;
    }

    function writeImportReport(text) {
        if (importReport) importReport.textContent = text;
    }

    function formatBytes(bytes) {
        const n = Number(bytes || 0);
        if (!Number.isFinite(n) || n <= 0) return "0 B";
        if (n < 1024) return `${n} B`;
        const kb = n / 1024;
        if (kb < 1024) return `${kb.toFixed(1)} KB`;
        const mb = kb / 1024;
        return `${mb.toFixed(1)} MB`;
    }

    function setLastMaskUiAbsent() {
        if (lastMaskStatus) {
            lastMaskStatus.textContent = "Absent";
            lastMaskStatus.style.color = "#b00020";
            lastMaskStatus.style.fontWeight = "700";
        }
        if (lastMaskUpdated) lastMaskUpdated.textContent = "—";
        if (lastMaskSize) lastMaskSize.textContent = "—";
    }

    function setLastMaskUiPresent(data) {
        if (lastMaskStatus) {
            lastMaskStatus.textContent = "Present";
            lastMaskStatus.style.color = "#1e6d34";
            lastMaskStatus.style.fontWeight = "700";
        }
        if (lastMaskUpdated) lastMaskUpdated.textContent = data.updated_at || "—";
        if (lastMaskSize) lastMaskSize.textContent = formatBytes(data.bytes);
    }

    async function refreshLastMask() {
        try {
            const res = await fetch("/api/v1/studio/prompt-mask/last?meta_only=1", { cache: "no-store" });
            if (res.status === 404) {
                setLastMaskUiAbsent();
                return;
            }
            const data = await res.json();
            if (!res.ok || data.status !== "success") {
                throw new Error(data.message || `HTTP ${res.status}`);
            }
            setLastMaskUiPresent(data);
        } catch (e) {
            if (lastMaskStatus) {
                lastMaskStatus.textContent = "Error";
                lastMaskStatus.style.color = "#b00020";
                lastMaskStatus.style.fontWeight = "700";
            }
            if (lastMaskUpdated) lastMaskUpdated.textContent = "—";
            if (lastMaskSize) lastMaskSize.textContent = "—";
        }
    }

    async function deleteLastMask() {
        const ok = window.confirm("Delete the saved last mask?");
        if (!ok) return;
        try {
            const res = await fetch("/api/v1/studio/prompt-mask/last", {
                method: "DELETE",
                cache: "no-store",
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok || data.status !== "success") {
                throw new Error(data.message || `HTTP ${res.status}`);
            }
            setMessage("Last mask deleted.");
            await refreshLastMask();
        } catch (e) {
            setMessage(`Failed to delete last mask: ${e.message}`);
        }
    }

    async function importDatasetZipFile(file, overwrite) {
        const fd = new FormData();
        fd.append("dataset", file);
        fd.append("overwrite", overwrite ? "1" : "0");

        const res = await fetch("/api/v1/studio/import", {
            method: "POST",
            body: fd,
            cache: "no-store",
        });

        const text = await res.text();
        let data = null;
        try {
            data = text ? JSON.parse(text) : null;
        } catch (_) {
            data = null;
        }

        if (!res.ok) {
            throw new Error(data?.message || text || `HTTP ${res.status}`);
        }
        return data || {};
    }

    function wire() {
        if (!importDatasetBtn) return;
        importDatasetBtn.addEventListener("click", async () => {
            const file = importDatasetZip?.files?.[0];
            if (!file) {
                setMessage("Import failed: select a ZIP file first.");
                return;
            }
            try {
                setBusy(true);
                setMessage(`Importing ${file.name}...`);
                writeImportReport("Running import...");
                const data = await importDatasetZipFile(file, !!importOverwrite?.checked);
                writeImportReport(JSON.stringify(data, null, 2));
                setMessage(
                    `Import completed: ${data.imported_total ?? 0} files imported, ${data.errors_count ?? 0} errors.`
                );
            } catch (e) {
                writeImportReport(`Import failed:\n${e.message}`);
                setMessage(`Import failed: ${e.message}`);
            } finally {
                setBusy(false);
            }
        });

        if (refreshLastMaskBtn) {
            refreshLastMaskBtn.addEventListener("click", () => refreshLastMask());
        }
        if (deleteLastMaskBtn) {
            deleteLastMaskBtn.addEventListener("click", () => deleteLastMask());
        }
    }

    wire();
    refreshLastMask();
})();
