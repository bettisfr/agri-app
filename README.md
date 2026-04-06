# AgriApp

Edge app for Raspberry Pi camera capture + local web gallery/labeling.

## What Is In This Repo

- `server.py`
  - Runs the web server on port `5000`.
  - Serves home/gallery/capture/system/log/health/labeler UI.
  - Exposes APIs for image listing, label save/load, delete, and dataset download.

- `client.py`
  - Captures images from Raspberry Pi camera (`rpicam-still` / `libcamera-still`).
  - Supports single shot (`--oneshot`) or periodic capture (`--interval`).
  - Includes autofocus and image tuning options.

- `scripts/run_server.sh`
  - Starts `server.py` loading Python env (pyenv or venv fallback).

- `scripts/capture_rpi.sh`
  - Starts `client.py` loading Python env (pyenv or venv fallback).
  - With args: forwards args to `client.py` (example: `--oneshot`).
  - Without args: runs continuous capture using `CAPTURE_INTERVAL` (default 30s).

- `systemd/`
  - User services for unattended startup:
    - `agriapp-server.service`

- `old/`
  - Deprecated legacy scripts kept only for reference.

## Quick Start

Install dependencies:

```bash
pip install -r requirements.txt
```

Run server:

```bash
python server.py
```

Open:

```text
http://<rpi-ip>:5000/gallery
http://<rpi-ip>:5000/health
```

Single photo capture:

```bash
python client.py --oneshot
```

Continuous capture every 5 minutes:

```bash
python client.py --interval 300
```

## Recommended Camera Commands

Default tuned single shot (current project defaults are already optimized):

```bash
python client.py --oneshot
```

Manual example with explicit values:

```bash
python client.py --oneshot --profile standard --af-mode continuous --af-range full --zoom 1.0 --quality 100 --warmup-ms 2000 --timeout-ms 3000 --af-window 0.35,0.35,0.30,0.30
```

## Environment File

Optional file used by wrapper scripts:

`~/agri-app/.env.systemd`

Example:

```bash
PYENV_ROOT=/home/fra/pyenv
# optional if you use pyenv manager (not needed for plain venv fallback)
# PYENV_VERSION=agriapp-rpi
CAPTURE_INTERVAL=300
# GPS tuning (optional)
# AGRIAPP_GPS_PORTS=/dev/serial0,/dev/ttyACM0,/dev/ttyUSB0
# AGRIAPP_GPS_BAUD=9600
# AGRIAPP_GPS_TIMEOUT=4
# AGRIAPP_GPS_CACHE_TTL=8
```

## GPS Notes

- Backend GPS reads are now guarded by a process lock and short cache TTL to reduce serial contention.
- Default UART preference is `"/dev/serial0"` (GPIO wiring).
- For manual GPS diagnostics, stop the server first to guarantee exclusive serial access:

```bash
systemctl --user stop agriapp-server.service
cd ~/agri-app
python3 test/test-gps.py --port /dev/serial0 --baud 9600 --watch --raw
systemctl --user start agriapp-server.service
```

## Boot Autostart (Systemd User Services)

Install services on Raspberry Pi:

```bash
cd ~/agri-app
./scripts/install_systemd_user_services.sh
```

Enable lingering (required for user services at boot, without interactive login):

```bash
sudo loginctl enable-linger fra
```

### Operational Commands (`--user` required)

```bash
# Server
systemctl --user start agriapp-server.service
systemctl --user stop agriapp-server.service
systemctl --user restart agriapp-server.service
systemctl --user status agriapp-server.service
systemctl --user enable agriapp-server.service
systemctl --user disable agriapp-server.service

# Logs
journalctl --user -u agriapp-server.service -n 100 --no-pager
journalctl --user -u agriapp-server.service -f
```

### Capture Loop via API (recommended)

```bash
# Status
curl http://<rpi-ip>:5000/api/v1/capture/loop/status

# Start every 300 seconds
curl -X POST http://<rpi-ip>:5000/api/v1/capture/loop/start \
  -H "Content-Type: application/json" \
  -d '{"interval_seconds":300}'

# Stop
curl -X POST http://<rpi-ip>:5000/api/v1/capture/loop/stop
```

## Notes

- Current preferred deployment strategy:
  - `systemd` for server
  - timed capture controlled via API (`/api/v1/capture/loop/*`) from web/tablet.
- Canonical API surface is versioned under `/api/v1` (legacy aliases removed).
- Web UI is offline-safe in AP mode (Bootstrap/Socket.IO served locally from `static/vendor`).
- ESP capture preset used by backend one-shot proxy is stabilized to:
  - `framesize=qxga`
  - `quality=10`
- Current ESP camera baseline is OV3660. OV5640 was tested and discarded in this setup due to recurrent optical artifacts.
- The gallery/labeler work on images in:
  - `static/uploads/images`
  - labels in `static/uploads/labels`
  - JSON annotations in `static/uploads/jsons`

## Deploy Helper (Local)

`deploy_rpi.sh` now also supports Android app lifecycle commands:

```bash
# Compile only
./deploy_rpi.sh --android-build

# Install current debug APK on connected tablet
./deploy_rpi.sh --android-install

# Launch app on tablet
./deploy_rpi.sh --android-run

# Compile + Install + Run
./deploy_rpi.sh --android-cir
```
