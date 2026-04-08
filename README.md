# AgriApp

Edge-first platform for insect image acquisition, contextual metadata collection (GPS + BME280), gallery curation, and annotation workflows.

The project now runs with a **dual UI model**:

- **RPi UI** (field operations): capture, network/system control, health, logs.
- **Studio UI** (curation workstation): gallery + annotation + dataset import.

Both UIs use the same backend and the same `/api/v1` contract surface.

## 1. Main Runtime Paths

### RPi (field node)
- Home: `/rpi`
- Gallery (read-only inspection): `/rpi/gallery`
- Capture: `/rpi/capture`
- System: `/rpi/system`
- Health: `/rpi/health`
- Log: `/rpi/log`
- Label page (viewer mode): `/rpi/label?image=<filename>`

### Studio (local curation)
- Home: `/studio`
- Gallery (labeling enabled): `/studio/gallery`
- System (dataset import): `/studio/system`
- Log: `/studio/log`
- Label page (full labeling): `/studio/label?image=<filename>`

### Legacy short paths (still valid, no redirect)
- `/`, `/gallery`, `/capture`, `/system`, `/health`, `/log`, `/label`
- They are served directly according to active runtime role.

## 2. Core Components

- `server.py`
  - Flask + Socket.IO backend on port `5000`
  - UI routes (RPi/Studio)
  - `/api/v1` endpoints for capture, image lifecycle, labels, download/export, health, system, logs

- `client.py`
  - Compatibility shim; capture logic lives in backend modules/scripts.

- `scripts/capture_rpi.sh`
  - Raspberry Pi camera wrapper (oneshot and loop mode through API orchestration).

- `scripts/capture_esp.py`
  - ESP serial/http helper for ESP capture proxy paths.

- `scripts/run_server.sh`
  - Server launcher with `.env.systemd` loading and pyenv/venv support.

- `deploy_rpi.sh`
  - Daily operations helper:
    - sync/mount/ssh
    - reload server (remote + local)
    - ESP build/flash
    - Android build/install/run

## 3. Quick Start

Install dependencies:

```bash
pip install -r requirements.txt
```

Run locally:

```bash
python3 -B server.py
```

Open:

```text
http://127.0.0.1:5000/studio
http://127.0.0.1:5000/rpi
```

### Runtime role selection

The same backend can run as `rpi` or `studio` UI role.

Set role in `.env.systemd`:

```bash
APP_ROLE=rpi
# or
APP_ROLE=studio
```

If `APP_ROLE` is not set, role is inferred from `APP_BRAND` (`studio` keyword -> Studio mode, otherwise RPi mode).

## 4. RPi Deployment Workflow

Sync code to Raspberry Pi:

```bash
./deploy_rpi.sh --sync
```

Reload backend on Raspberry Pi:

```bash
./deploy_rpi.sh --reload-server
```

Reload local Studio service:

```bash
./deploy_rpi.sh --reload-local-server
```

Mount remote home:

```bash
./deploy_rpi.sh --mount
./deploy_rpi.sh --umount
```

Useful deploy modes (full list: `./deploy_rpi.sh --help`):

- `--sync`, `--sync-dry`
- `--reload-server` (RPi service)
- `--reload-local-server` (local Studio service)
- `--local-service <name>` (override local service name)
- `--esp-build`, `--esp-flash`
- `--android-build`, `--android-install`, `--android-run`, `--android-cir`

## 5. Service Management

### RPi service

```bash
systemctl --user start agriapp-server.service
systemctl --user stop agriapp-server.service
systemctl --user restart agriapp-server.service
systemctl --user status agriapp-server.service
journalctl --user -u agriapp-server.service -f
```

### Local Studio service

```bash
scripts/install_local_user_service.sh
systemctl --user restart agriapp-local.service
systemctl --user status agriapp-local.service
```

## 6. Capture Operations

### API one-shot (RPi)

```bash
curl -X POST http://<rpi-ip>:5000/api/v1/capture/rpi/oneshot
```

### API loop (RPi)

```bash
curl http://<rpi-ip>:5000/api/v1/capture/loop/status

curl -X POST http://<rpi-ip>:5000/api/v1/capture/loop/start \
  -H "Content-Type: application/json" \
  -d '{"interval_seconds":300}'

curl -X POST http://<rpi-ip>:5000/api/v1/capture/loop/stop
```

### ESP API capture (optional path)

```bash
curl "http://<rpi-ip>:5000/api/v1/capture/esp/oneshot?esp_base=http://192.168.4.239"
```

Notes:
- ESP endpoints remain in `/api/v1`.
- Current web RPi surface is intentionally simplified and field-focused.

### Direct CLI one-shot (RPi)

```bash
python3 client.py --oneshot
```

## 7. Export / Import Workflow

### Export (RPi web gallery)
- `Export all` -> full dataset ZIP
- `Export visible` -> current paginated/filtered selection ZIP

Export names include timestamp:
- `agriapp_dataset_YYYYMMDD-HHMMSS.zip`
- `agriapp_dataset_visible_YYYYMMDD-HHMMSS.zip`

### Import (Studio web system)
- Open `/studio/system`
- Upload exported ZIP
- Optional `Overwrite`
- Read JSON report (imported/skipped/errors)

## 8. Metadata (GPS + BME280)

Per-image sidecar metadata is stored in:
- `static/uploads/metadata/<stem>.json`

Fields:
- `latitude`, `longitude`
- `temperature`, `humidity`, `pressure`
- `user_comment`

Best-effort policy:
- capture does not fail when one sensor is unavailable.
- values are persisted when available.

## 9. GPS Notes

GPS serial access is lock-protected in backend. For manual serial diagnostics, stop the server first:

```bash
systemctl --user stop agriapp-server.service
cd ~/agri-app
python3 test/test-gps.py --port /dev/serial0 --baud 9600 --watch --raw
systemctl --user start agriapp-server.service
```

## 10. Data Folders

- Images: `static/uploads/images`
- Labels TXT: `static/uploads/labels`
- Labels JSON: `static/uploads/jsons`
- Metadata: `static/uploads/metadata`
- Thumbnails cache: `static/uploads/thumbs`

## 11. Environment File (`.env.systemd`)

Path:

```text
~/agri-app/.env.systemd
```

Example:

```bash
PYENV_ROOT=/home/fra/pyenv
LABELER_READONLY=1
APP_BRAND="AgriApp RPi"
APP_ICON=🌿
# Optional explicit role: rpi | studio
# APP_ROLE=rpi
```

## 12. Notes

- Bootstrap and Socket.IO assets are vendored locally (`static/vendor`) for offline/AP robustness.
- ESP APIs remain available; daily field baseline currently prioritizes RPi capture path.
- Canonical operational API namespace is `/api/v1`.
- Script reference: `scripts/README.md`.
- `scripts/esp_capture.sh` is legacy/deprecated; prefer `scripts/capture_esp.py`.
- If UI style changes are not visible after deploy, force reload browser cache (`Ctrl+F5`).
