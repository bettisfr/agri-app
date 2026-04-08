# Project Layout

## Root

- `server.py`
  - Main Flask backend (UI routes + `/api/v1` APIs).
- `client.py`
  - Compatibility shim for capture entrypoint.
- `deploy_rpi.sh`
  - Sync/deploy/reload helper (RPi + local service + Android + ESP tasks).
- `requirements.txt`
  - Python dependencies.

## Backend Modules

- `backend/`
  - `capture_client.py`
  - `gps_reader.py`
  - `bme_reader.py`
  - `photo_metadata.py`

## Web Frontend

- `templates/`
  - `base.html`
  - dual navbars:
    - `partials/navbar_rpi.html`
    - `partials/navbar_studio.html`
  - RPi/Studio pages:
    - `index.html`, `gallery.html`, `labeler.html`, `log.html`
    - `capture.html`, `health.html`, `system.html` (RPi)
    - `system_studio.html` (Studio import page)

- `static/`
  - `gallery-app.js`, `labeler-app.js`
  - `capture-page.js`, `health-page.js`, `log-page.js`
  - `system-page.js` (RPi system page)
  - `system-studio-page.js` (Studio system page)
  - `site-ui.css`, `labeler-ui.css`
  - `vendor/` local third-party assets (Bootstrap, Socket.IO)
  - `uploads/` runtime data folders:
    - `images/`, `labels/`, `jsons/`, `metadata/`, `thumbs/`

## Mobile

- `mobile/android/`
  - Android control app project.

## Operations

- `scripts/`
  - `run_server.sh`
  - `capture_rpi.sh`
  - `capture_esp.py`
  - install helpers

- `systemd/`
  - user service templates (`agriapp-server.service` etc.).

## Documentation

- `docs/api-v1.md`
- `docs/project-layout.md`
- `docs/software-doc-latex/`
  - thesis-style software report sources.

## Compatibility / Legacy

- `old/`
  - deprecated scripts kept for reference only.
