# AgriApp API v1

Base URL (LAN): `http://<rpi-ip>:5000`

## 1) Health
- Method: `GET`
- Path: `/api/v1/health`
- Purpose: quick service check.

Example:
```bash
curl http://192.168.1.67:5000/api/v1/health
```

Response:
```json
{
  "service": "agriapp",
  "status": "ok",
  "version": "v1"
}
```

## 2) System status
- Method: `GET`
- Path: `/api/v1/system/status`
- Purpose: hostname, images path, disk usage.

Example:
```bash
curl http://192.168.1.67:5000/api/v1/system/status
```

Response fields:
- `status`: `ok|error`
- `hostname`: Raspberry hostname
- `images_dir`: local path on RPi
- `disk_total_bytes`: total disk bytes
- `disk_free_bytes`: available disk bytes
- `timestamp`: unix epoch seconds

## 3) List images (paginated)
- Method: `GET`
- Path: `/api/v1/images`
- Query:
  - `page` (default `1`)
  - `page_size` (default `24`, max `200`)
  - `filter` (filename substring)
  - `only_labeled` (`true/false`) -> returns only `to label`
  - `labeled_only` (`true/false`) -> returns only labeled

Example:
```bash
curl "http://192.168.1.67:5000/api/v1/images?page=1&page_size=10&labeled_only=false"
```

Response fields:
- `items[]`: image list with:
  - `filename`
  - `upload_ts`
  - `upload_time`
  - `metadata` (`latitude`,`longitude`,`temperature`,`pressure`,`humidity`,`user_comment`)
  - `is_labeled`
  - `labels_count`
- `page`, `page_size`, `total_items`, `total_pages`
- `shown_start`, `shown_end`
- `global_total`, `global_labeled`

## 4) Single image status
- Method: `GET`
- Path: `/api/v1/images/<filename>/status`
- Purpose: get current label state for one image.

Example:
```bash
curl "http://192.168.1.67:5000/api/v1/images/img_20260312-202153.jpg/status"
```

Response:
```json
{
  "status": "success",
  "filename": "img_20260312-202153.jpg",
  "is_labeled": false,
  "labels_count": 0
}
```

## 5) Capture oneshot
- Method: `POST`
- Path: `/api/v1/capture/oneshot`
- Purpose: trigger one capture on RPi camera via `scripts/run_capture.sh --oneshot`.

Example:
```bash
curl -X POST http://192.168.1.67:5000/api/v1/capture/oneshot
```

Success response:
```json
{
  "status": "success",
  "message": "oneshot completed",
  "latest_filename": "img_20260312-202153.jpg",
  "metadata": {
    "latitude": 43.0628742,
    "longitude": 12.5511522
  },
  "stdout": "..."
}
```

Notes:
- On capture, if GPS is available on RPi serial (`/dev/ttyACM0` or `/dev/ttyUSB0` by default), image metadata is geotagged.
- GPS behavior can be tuned via env vars: `AGRIAPP_GPS_PORTS`, `AGRIAPP_GPS_BAUD`, `AGRIAPP_GPS_TIMEOUT`.

## 5b) ESP capture via RPi proxy
- Method: `GET`
- Path: `/api/v1/esp/capture`
- Query:
  - `esp_base` (required), e.g. `http://192.168.4.239`
- Purpose: capture one JPEG from ESP and save it in gallery as `esp_*.jpg`.

Example:
```bash
curl --get \
  --data-urlencode "esp_base=http://192.168.4.239" \
  -o esp_capture.jpg \
  "http://192.168.4.1:5000/api/v1/esp/capture"
```

Notes:
- Response body is JPEG binary.
- If locally saved on RPi, response includes header `X-AgriApp-Filename`.
- If RPi GPS fix is available, response may include `X-AgriApp-Latitude` and `X-AgriApp-Longitude`, and metadata is saved for that gallery image.

## 6) Network mode (RPi)
- Method: `GET`
- Path: `/api/v1/network/mode`
- Purpose: read current RPi networking mode.

Example:
```bash
curl http://<rpi-ip>:5000/api/v1/network/mode
```

Response (example):
```json
{
  "status": "success",
  "mode": "hybrid_debug",
  "ap_active": true,
  "client_active": true,
  "active_wifi_connections": ["agriapp-rescue", "HomeWiFi"]
}
```

Set mode:
- Method: `POST`
- Path: `/api/v1/network/mode`
- Body: `{ "mode": "wifi_only|ap_only|hybrid_debug" }`

Example:
```bash
curl -X POST http://<rpi-ip>:5000/api/v1/network/mode \
  -H "Content-Type: application/json" \
  -d '{"mode":"ap_only"}'
```

Error response (example):
```json
{
  "status": "error",
  "returncode": 1,
  "stdout": "...",
  "stderr": "..."
}
```

## Notes for Android MVP
- Use Retrofit/OkHttp with base URL `http://<selected-ip>:5000/`.
- Timeout suggestion: connect 5s, read 30s.
- For `capture/oneshot`, show progress and allow up to ~90s before timeout.
- For discovery MVP, allow manual IP first (`192.168.1.67`) then optional LAN scan.

## Legacy cleanup status
- Legacy aliases `/get_labels`, `/save_labels`, and `/delete-image` have been removed.
- Use only:
  - `GET/POST /api/v1/labels`
  - `POST /api/v1/images/delete`
