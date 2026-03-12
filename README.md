# AgriApp

Edge app for Raspberry Pi camera capture + local web gallery/labeling.

## What Is In This Repo

- `server.py`
  - Runs the web server on port `5000`.
  - Serves home/gallery/labeler UI.
  - Exposes APIs for image listing, label save/load, delete, and dataset download.

- `client.py`
  - Captures images from Raspberry Pi camera (`rpicam-still` / `libcamera-still`).
  - Supports single shot (`--oneshot`) or periodic capture (`--interval`).
  - Includes autofocus and image tuning options.

- `scripts/run_server.sh`
  - Starts `server.py` loading Python env (pyenv or venv fallback).

- `scripts/run_capture.sh`
  - Starts `client.py` loading Python env (pyenv or venv fallback).
  - With args: forwards args to `client.py` (example: `--oneshot`).
  - Without args: runs continuous capture using `CAPTURE_INTERVAL` (default 30s).

- `systemd/`
  - User services for unattended startup:
    - `agriapp-server.service`
    - `agriapp-capture.service`

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

# Capture (if you choose systemd for capture)
systemctl --user start agriapp-capture.service
systemctl --user stop agriapp-capture.service
systemctl --user restart agriapp-capture.service
systemctl --user status agriapp-capture.service
systemctl --user enable agriapp-capture.service
systemctl --user disable agriapp-capture.service

# Logs
journalctl --user -u agriapp-server.service -n 100 --no-pager
journalctl --user -u agriapp-server.service -f
journalctl --user -u agriapp-capture.service -n 100 --no-pager
journalctl --user -u agriapp-capture.service -f
```

## Notes

- Current preferred deployment strategy:
  - `systemd` for server
  - `cron` (or systemd) for timed capture, depending on scheduling needs.
- The gallery/labeler work on images in:
  - `static/uploads/images`
  - labels in `static/uploads/labels`
  - JSON annotations in `static/uploads/jsons`
