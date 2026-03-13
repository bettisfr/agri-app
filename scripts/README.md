# Scripts Overview

This folder contains operational helpers for capture, network mode, and service startup.

## Scripts

- `capture_rpi.sh`
  - Main Raspberry Pi capture wrapper.
  - Loads Python environment (`pyenv` or venv fallback) and runs `backend.capture_client`.
  - If called without args, runs periodic mode using `CAPTURE_INTERVAL` (default `30`).
  - Examples:
    - `bash scripts/capture_rpi.sh --oneshot`
    - `bash scripts/capture_rpi.sh --interval 300`

- `capture_esp.py`
  - Main ESP capture helper.
  - Supports HTTP mode (`/capture`) and serial mode.
  - Can set camera controls before capture (for example `framesize`, `quality`).
  - Examples:
    - `python3 scripts/capture_esp.py --url http://192.168.4.239/capture --framesize qxga --set quality=10 --out esp.jpg`
    - `python3 scripts/capture_esp.py /dev/ttyUSB0 esp.jpg`

- `esp_capture.sh`
  - Thin wrapper around `capture_esp.py`.
  - Provides default ESP URL/output when no arguments are provided.

- `capture_pair.sh`
  - Captures one ESP image and one RPi image, then saves both in one local folder.
  - Useful for camera quality comparison campaigns.

- `run_server.sh`
  - Wrapper to launch `server.py` with the configured Python environment.

- `rpi_network_mode.sh`
  - NetworkManager helper for Raspberry Pi network mode.
  - Modes:
    - `wifi_only`
    - `ap_only`
    - `hybrid_debug`
  - Examples:
    - `bash scripts/rpi_network_mode.sh --status`
    - `bash scripts/rpi_network_mode.sh --set ap_only`

- `install_systemd_user_services.sh`
  - Installs and enables user-level `agriapp-server.service`.
  - Also attempts `loginctl enable-linger` for boot autostart without interactive login.

## Notes

- Runtime defaults can be overridden via `~/agri-app/.env.systemd`.
- Prefer `capture_rpi.sh` and `capture_esp.py` for integrations and automation.
