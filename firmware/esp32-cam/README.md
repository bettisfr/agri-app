# ESP32-CAM Firmware (AgriApp)

This folder contains the ESP32-CAM sketch and camera server sources used by AgriApp.

## Files
- `esp32-cam.ino`: main sketch (CameraWebServer-based)
- `app_httpd.cpp`, `camera_index.h`, `camera_pins.h`: web server/camera sources
- `board_config.h`, `device_profile.h`: board/sensor tuning
- `save_photo.py`: helper script to fetch one image from `/capture`

## Wi-Fi credentials
1. Copy `secrets.example.h` to `secrets.h`.
2. Set `WIFI_SSID` and `WIFI_PASSWORD` in `secrets.h`.

`secrets.h` is git-ignored and must not be committed.

## Flash
- Open this folder in Arduino IDE.
- Select the correct board profile (`ESP32` if `DEVICE_PROFILE_CAM` is enabled).
- Upload `esp32-cam.ino`.
- Open Serial Monitor at `115200` and look for:
  `Camera Ready! Use 'http://<ip>' to connect`

## Quick capture from repo root
Use the wrapper script:

```bash
./scripts/esp_capture.sh
```

Default behavior:
- URL: `http://192.168.1.50/capture`
- Output: `static/uploads/images/esp_<timestamp>.jpg`
- Settings: `framesize=qxga`, `quality=10`

## Endpoints
- `GET /status`
- `GET /capture`
- `GET /stream`
