# AgriApp Android App

This folder contains the Android tablet control app (Kotlin + Compose) for AgriApp.

Current features:
- LAN discovery (cache-first) for Raspberry Pi and ESP-CAM endpoints.
- Capture actions:
  - `Shot` dialog (`RPi` or `ESP` source).
  - Auto capture start/stop with interval presets (1/2/3/5/10 min).
- Gallery:
  - paginated list from backend API.
  - thumbnail previews.
  - open image in fullscreen dialog, zoom/pan, prev/next navigation.
  - single delete and delete-all workflows.
- System/network:
  - RPi status panel (free space + AP/WiFi badges).
  - network mode actions (WiFi/AP).
  - system actions dialog (restart server, reboot, poweroff).
- Bottom live log panel (scrollable, auto-scroll to latest event).
- Wired to AgriApp REST API under `/api/v1/...`.

## Open in Android Studio
1. Open folder: `mobile/android`
2. Let Gradle sync.
3. Run app on your USB-debug tablet.

## USB debug quick check
- Connect tablet via USB debug.
- In Android Studio click `Run` on this project.
- Verify requests hit your RPi API (`/api/v1/health`, `/api/v1/system/status`, `/api/v1/capture/rpi/oneshot`, etc.).

## Notes
- `AndroidManifest.xml` enables cleartext LAN HTTP for local `http://<rpi-ip>:5000`.
- Build/install/run shortcut from repo root:

```bash
./deploy_rpi.sh --android-cir
```
