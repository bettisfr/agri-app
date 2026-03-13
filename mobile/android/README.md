# AgriApp Android Scaffold

This folder contains a minimal Android tablet client scaffold (Kotlin + Compose).

Implemented now:
- default host `http://raspberrypi.local:5000` + LAN discovery
- actions: `Health`, `Status`, `OneShot`, `Gallery`
- wired to AgriApp REST API (`/api/v1/...`)

## Open in Android Studio
1. Open folder: `mobile/android`
2. Let Gradle sync.
3. Run app on your USB-debug tablet.

## USB debug quick check
- Connect tablet via USB debug.
- In Android Studio click `Run` on this project.
- Verify requests hit your RPi API (`/api/v1/health`, `/api/v1/system/status`, etc.).

## Notes
- `AndroidManifest.xml` enables cleartext LAN HTTP for local `http://<rpi-ip>:5000`.
- This is intentionally MVP scaffold; next pass can add:
  - LAN discovery (mDNS + subnet scan)
  - image preview modal
  - admin actions (restart/reboot)
