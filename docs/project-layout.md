# Project Layout

This repository is organized into three logical areas:

- `backend/`: Python runtime modules.
  - `capture_client.py`: camera capture worker/CLI used on Raspberry Pi.
- root web app files:
  - `server.py`: Flask + gallery + API endpoints.
  - `templates/`, `static/`: web UI assets.
- `mobile/android/`: reserved for upcoming Android control app.

Operations and deployment:
- `scripts/`: run/install helper scripts (`run_server.sh`, `capture_rpi.sh`, `capture_esp.py`, etc.).
- `systemd/`: user service templates for Raspberry Pi.
- `deploy_rpi.sh`: sync/mount/deploy helper.

Compatibility note:
- `client.py` in project root is now a thin compatibility shim that forwards to `backend.capture_client`.
