(() => {
    const importDatasetZip = document.getElementById("importDatasetZip");
    const importOverwrite = document.getElementById("importOverwrite");
    const importDatasetBtn = document.getElementById("importDatasetBtn");
    const importReport = document.getElementById("importReport");
    const systemMessage = document.getElementById("systemMessage");

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
    }

    wire();
})();
