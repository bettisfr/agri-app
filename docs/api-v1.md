# AgriApp API v1 (Current)

Base URL:

- RPi: `http://<rpi-ip>:5000`
- Local Studio: `http://127.0.0.1:5000`

This document summarizes active `/api/v1` routes used by RPi UI and Studio UI.

## 1) Health and Runtime

### `GET /api/v1/health`
Quick backend liveness.

### `GET /api/v1/health/checklist`
Live checklist (RPi, camera, GPS, BME280, storage, ESP serial probe).

### `GET /api/v1/system/status`
Hostname + disk usage + runtime timestamp.

### `GET /api/v1/logs`
Unified pull logs.

### `GET /api/v1/logs/stream`
Unified SSE push stream.

## 2) Network and System Control (RPi-oriented)

### `GET /api/v1/network/mode`
Read current network mode + active links.

### `POST /api/v1/network/mode`
Set mode (`wifi_only`, `ap_only`, `hybrid_debug`).

### `POST /api/v1/system/server/restart`
Restart backend service.

### `POST /api/v1/system/reboot`
Reboot Raspberry Pi (sudo policy required).

### `POST /api/v1/system/poweroff`
Power off Raspberry Pi (sudo policy required).

## 3) Image Listing and Lifecycle

### `GET /api/v1/images`
Paginated image list.

Query:
- `page` (default `1`)
- `page_size` (default `24`)
- `filter` (filename substring)
- `labeled_only` (`1/true`)
- `only_labeled` (`1/true`) legacy semantic support

Each item includes:
- `filename`
- `upload_ts`, `upload_time`
- `file_size_bytes`
- `image_width`, `image_height`
- `metadata`
- `is_labeled`, `labels_count`

### `GET /api/v1/images/<filename>/status`
Single image status + metadata summary.

### `GET /api/v1/images/<filename>/thumbnail?w=320`
Thumbnail stream (cached).

### `POST /api/v1/images/delete`
Delete one image and related artifacts.

### `POST /api/v1/images/delete-all`
Delete all images and related artifacts.

### `POST /api/v1/images/upload`
External image upload endpoint.

## 4) Labels

### `GET /api/v1/labels?image=<filename>`
Load labels for one image.

### `POST /api/v1/labels`
Save labels (JSON + YOLO TXT sync).

Payload includes image name and label list.

## 5) Capture

### `POST /api/v1/capture/rpi/oneshot`
Trigger one-shot RPi capture.

### `GET /api/v1/capture/loop/status`
Loop status.

### `POST /api/v1/capture/loop/start`
Start RPi loop (`interval_seconds` in JSON body).

### `POST /api/v1/capture/loop/stop`
Stop RPi loop.

### ESP proxy endpoints (diagnostic/optional)
- `GET /api/v1/capture/esp/oneshot`
- `GET /api/v1/capture/esp/loop/status`
- `POST /api/v1/capture/esp/loop/start`
- `POST /api/v1/capture/esp/loop/stop`
- `GET /api/v1/esp/status`

## 6) Export / Download

### `GET /api/v1/download/dataset`
Full dataset ZIP.

### `POST /api/v1/download/dataset-selected`
Selected set ZIP via JSON body:
```json
{"filenames":["rpi_....jpg","esp_....jpg"]}
```

### `GET /api/v1/download/dataset-selected`
Selected set ZIP via query:
`?filename=a.jpg&filename=b.jpg`

### `GET /api/v1/images/download-with-labels?image=<filename>`
Single image bundle ZIP (image + labels/json/metadata).

### `GET /api/v1/images/download?image=<filename>`
Single image attachment.

### Timestamped export naming
- `agriapp_dataset_YYYYMMDD-HHMMSS.zip`
- `agriapp_dataset_visible_YYYYMMDD-HHMMSS.zip`

## 7) Studio Import

### `POST /api/v1/studio/import`
Import dataset ZIP into local storage.

Form fields:
- `dataset` (zip file)
- `overwrite` (`1` or `0`)

Response includes:
- imported counters
- skipped counters
- errors list
- `imported_total`

## 8) UI Route Context (non-API)

Dual UI namespaces are active:
- RPi UI: `/rpi/*`
- Studio UI: `/studio/*`

Legacy short routes (`/gallery`, `/system`, etc.) remain served directly for compatibility.
