from flask import Flask, request, render_template, jsonify, send_file, Response
from flask_socketio import SocketIO
from werkzeug.utils import secure_filename
import os
import io
import glob
import zipfile
import time
import json
import datetime  # needed for timestamp parsing
import socket
import subprocess
import urllib.parse
import urllib.request
import ipaddress
import struct
import threading

from backend.bme_reader import get_bme280_reading
from backend.gps_reader import get_gps_fix
from backend.photo_metadata import (
    DEFAULT_METADATA,
    load_metadata_for_image_path,
    metadata_path_for_image_path,
    save_metadata_for_image_path,
)

app = Flask(__name__)
socketio = SocketIO(app, async_mode='threading')

# ----------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------
STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
UPLOAD_ROOT = os.path.join(STATIC_DIR, "uploads")

IMAGES_DIR = os.path.join(UPLOAD_ROOT, "images")   # image files
LABELS_DIR = os.path.join(UPLOAD_ROOT, "labels")   # YOLO txt files
JSONS_DIR = os.path.join(UPLOAD_ROOT, "jsons")     # per-image json files
METADATA_DIR = os.path.join(UPLOAD_ROOT, "metadata")  # per-image metadata files
THUMBS_DIR = os.path.join(UPLOAD_ROOT, "thumbs")  # cached thumbnails
NETWORK_MODE_SCRIPT = os.path.join(os.path.dirname(__file__), "scripts", "rpi_network_mode.sh")

os.makedirs(IMAGES_DIR, exist_ok=True)
os.makedirs(LABELS_DIR, exist_ok=True)
os.makedirs(JSONS_DIR, exist_ok=True)
os.makedirs(METADATA_DIR, exist_ok=True)
os.makedirs(THUMBS_DIR, exist_ok=True)

CAPTURE_LOOP_PROC = None
CAPTURE_LOOP_STARTED_AT = None
CAPTURE_LOOP_INTERVAL = None
CAPTURE_LOOP_LOCK = threading.Lock()
CAPTURE_LOOP_LOG = os.path.join("/tmp", "agriapp-capture-loop.log")
ESP_CAPTURE_LOOP_PROC = None
ESP_CAPTURE_LOOP_STARTED_AT = None
ESP_CAPTURE_LOOP_INTERVAL = None
ESP_CAPTURE_LOOP_BASE = None
ESP_CAPTURE_LOOP_LOCK = threading.Lock()
ESP_CAPTURE_LOOP_LOG = os.path.join("/tmp", "agriapp-esp-capture-loop.log")


def _capture_loop_script_path() -> str:
    return os.path.join(os.path.dirname(__file__), "scripts", "capture_rpi.sh")


def _capture_esp_helper_path() -> str:
    return os.path.join(os.path.dirname(__file__), "scripts", "capture_esp.py")


def _capture_loop_is_running() -> bool:
    global CAPTURE_LOOP_PROC
    if CAPTURE_LOOP_PROC is None:
        return False
    if CAPTURE_LOOP_PROC.poll() is None:
        return True
    CAPTURE_LOOP_PROC = None
    return False


def _capture_loop_status_payload():
    running = _capture_loop_is_running()
    pid = CAPTURE_LOOP_PROC.pid if running and CAPTURE_LOOP_PROC else None
    next_capture_in_seconds = None
    if running and CAPTURE_LOOP_INTERVAL and CAPTURE_LOOP_STARTED_AT:
        elapsed = max(0, int(time.time()) - int(CAPTURE_LOOP_STARTED_AT))
        next_capture_in_seconds = (int(CAPTURE_LOOP_INTERVAL) - (elapsed % int(CAPTURE_LOOP_INTERVAL))) % int(CAPTURE_LOOP_INTERVAL)
    return {
        "status": "success",
        "running": running,
        "pid": pid,
        "interval_seconds": CAPTURE_LOOP_INTERVAL if running else None,
        "started_at_ts": CAPTURE_LOOP_STARTED_AT if running else None,
        "next_capture_in_seconds": next_capture_in_seconds,
        "log_path": CAPTURE_LOOP_LOG,
    }


def _capture_esp_loop_is_running() -> bool:
    global ESP_CAPTURE_LOOP_PROC
    if ESP_CAPTURE_LOOP_PROC is None:
        return False
    if ESP_CAPTURE_LOOP_PROC.poll() is None:
        return True
    ESP_CAPTURE_LOOP_PROC = None
    return False


def _capture_esp_loop_status_payload():
    running = _capture_esp_loop_is_running()
    pid = ESP_CAPTURE_LOOP_PROC.pid if running and ESP_CAPTURE_LOOP_PROC else None
    next_capture_in_seconds = None
    if running and ESP_CAPTURE_LOOP_INTERVAL and ESP_CAPTURE_LOOP_STARTED_AT:
        elapsed = max(0, int(time.time()) - int(ESP_CAPTURE_LOOP_STARTED_AT))
        next_capture_in_seconds = (int(ESP_CAPTURE_LOOP_INTERVAL) - (elapsed % int(ESP_CAPTURE_LOOP_INTERVAL))) % int(ESP_CAPTURE_LOOP_INTERVAL)
    return {
        "status": "success",
        "running": running,
        "pid": pid,
        "interval_seconds": ESP_CAPTURE_LOOP_INTERVAL if running else None,
        "started_at_ts": ESP_CAPTURE_LOOP_STARTED_AT if running else None,
        "next_capture_in_seconds": next_capture_in_seconds,
        "esp_base": ESP_CAPTURE_LOOP_BASE if running else None,
        "log_path": ESP_CAPTURE_LOOP_LOG,
    }


# ----------------------------------------------------------------------
# JSON / helper paths
# ----------------------------------------------------------------------
def json_path_for_image(filename: str) -> str:
    """
    Path of the per-image JSON file for this image.
    E.g. azz2.jpg -> jsons/azz2.json
    """
    base, _ = os.path.splitext(filename)
    return os.path.join(JSONS_DIR, base + ".json")


def normalize_image_filename(raw_name: str) -> str:
    """
    Normalize and sanitize a client-provided image filename.
    Returns empty string if invalid.
    """
    if not isinstance(raw_name, str):
        return ""
    return secure_filename(os.path.basename(raw_name))


def is_image_labeled(filename: str) -> bool:
    """
    An image is considered 'labeled' only if jsons/<stem>.json contains
    at least one label entry.
    """
    jpath = json_path_for_image(filename)
    if not os.path.exists(jpath) or os.path.getsize(jpath) == 0:
        return False

    try:
        with open(jpath, "r") as f:
            data = json.load(f)
        return isinstance(data, list) and len(data) > 0
    except Exception:
        return False


def yolo_txt_path_for_image(filename: str) -> str:
    """
    Path of the YOLO txt file for this image.
    E.g. azz2.jpg -> labels/azz2.txt
    """
    base, _ = os.path.splitext(filename)
    return os.path.join(LABELS_DIR, base + ".txt")


def load_labels_from_json_for_image(filename: str):
    """
    Load per-image labels from jsons/<stem>.json.

    Returns:
        list of label dicts or None if file missing/invalid.
    """
    jpath = json_path_for_image(filename)
    if not os.path.exists(jpath) or os.path.getsize(jpath) == 0:
        return None

    try:
        with open(jpath, "r") as f:
            data = json.load(f)
        if isinstance(data, list):
            return data
        return None
    except Exception:
        return None


def save_labels_to_json_for_image(filename: str, labels_list):
    """
    Save per-image labels to jsons/<stem>.json.
    """
    jpath = json_path_for_image(filename)
    with open(jpath, "w") as f:
        json.dump(labels_list, f, indent=2)


def labels_count_for_image(filename: str) -> int:
    """
    Count non-empty YOLO label lines for one image.
    """
    base, _ = os.path.splitext(filename)
    labels_path = os.path.join(LABELS_DIR, base + ".txt")
    if not os.path.exists(labels_path):
        return 0

    count = 0
    with open(labels_path, "r") as lf:
        for line in lf:
            if line.strip():
                count += 1
    return count


def file_size_bytes_for_image(filename: str) -> int:
    path = os.path.join(IMAGES_DIR, filename)
    try:
        return int(os.path.getsize(path))
    except Exception:
        return 0


def thumbnail_path_for_image(filename: str, width: int) -> str:
    base, _ = os.path.splitext(filename)
    safe_width = max(64, min(1024, int(width)))
    return os.path.join(THUMBS_DIR, f"{base}_w{safe_width}.jpg")


def build_or_get_thumbnail(filename: str, width: int) -> str | None:
    """
    Return cached thumbnail path, building it when needed.
    On failure, return None.
    """
    image_path = os.path.join(IMAGES_DIR, filename)
    if not os.path.exists(image_path):
        return None

    width = max(64, min(1024, int(width)))
    thumb_path = thumbnail_path_for_image(filename, width)

    try:
        if os.path.exists(thumb_path):
            if os.path.getmtime(thumb_path) >= os.path.getmtime(image_path):
                return thumb_path
    except Exception:
        pass

    try:
        from PIL import Image, ImageOps
    except Exception:
        return None

    try:
        with Image.open(image_path) as img:
            img = ImageOps.exif_transpose(img)
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            src_w, src_h = img.size
            if src_w <= 0 or src_h <= 0:
                return None

            if src_w >= src_h:
                out_w = width
                out_h = max(1, int((src_h * width) / src_w))
            else:
                out_h = width
                out_w = max(1, int((src_w * width) / src_h))

            img = img.resize((out_w, out_h), Image.Resampling.LANCZOS)
            os.makedirs(THUMBS_DIR, exist_ok=True)
            img.save(thumb_path, format="JPEG", quality=72, optimize=True)
        return thumb_path
    except Exception:
        return None


def remove_thumbnails_for_image(filename: str) -> int:
    base, _ = os.path.splitext(filename)
    pattern = os.path.join(THUMBS_DIR, f"{base}_w*.jpg")
    removed = 0
    for path in glob.glob(pattern):
        try:
            os.remove(path)
            removed += 1
        except Exception:
            pass
    return removed


def image_dimensions_for_image(filename: str) -> tuple[int, int]:
    """
    Best-effort image dimension detection (JPEG/PNG).
    Returns (width, height) or (0, 0) on failure.
    """
    path = os.path.join(IMAGES_DIR, filename)
    try:
        with open(path, "rb") as f:
            header = f.read(32)
            if len(header) >= 24 and header.startswith(b"\x89PNG\r\n\x1a\n"):
                width, height = struct.unpack(">II", header[16:24])
                return int(width), int(height)

            if len(header) >= 2 and header[0:2] == b"\xff\xd8":
                f.seek(2)
                while True:
                    byte = f.read(1)
                    if not byte:
                        break
                    if byte != b"\xff":
                        continue
                    marker = f.read(1)
                    if not marker:
                        break
                    while marker == b"\xff":
                        marker = f.read(1)
                        if not marker:
                            break
                    if not marker:
                        break

                    marker_code = marker[0]
                    if marker_code in (0xD8, 0xD9):
                        continue

                    seg_len_bytes = f.read(2)
                    if len(seg_len_bytes) != 2:
                        break
                    seg_len = int.from_bytes(seg_len_bytes, "big")
                    if seg_len < 2:
                        break

                    if marker_code in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                        sof = f.read(5)
                        if len(sof) == 5:
                            height = int.from_bytes(sof[1:3], "big")
                            width = int.from_bytes(sof[3:5], "big")
                            return width, height
                        break

                    f.seek(seg_len - 2, os.SEEK_CUR)
    except Exception:
        pass

    return 0, 0


def run_network_mode_script(args_list, extra_env: dict | None = None):
    """
    Run network mode helper script and parse JSON output.
    """
    if not os.path.exists(NETWORK_MODE_SCRIPT):
        return {"status": "error", "message": "network mode script not found"}, 500

    cmd = ["bash", NETWORK_MODE_SCRIPT] + args_list
    env = os.environ.copy()
    if isinstance(extra_env, dict):
        for k, v in extra_env.items():
            if v is None:
                continue
            env[str(k)] = str(v)
    try:
        proc = subprocess.run(
            cmd,
            cwd=os.path.dirname(__file__),
            env=env,
            capture_output=True,
            text=True,
            timeout=40,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "network mode command timeout"}, 504
    except Exception as e:
        return {"status": "error", "message": f"network mode command failed: {e}"}, 500

    stdout = (proc.stdout or "").strip()
    stderr = (proc.stderr or "").strip()

    if proc.returncode != 0:
        return {
            "status": "error",
            "message": "network mode command failed",
            "returncode": proc.returncode,
            "stderr": stderr[-500:],
            "stdout": stdout[-500:],
        }, 500

    if not stdout:
        return {"status": "error", "message": "network mode command returned empty output"}, 500

    try:
        return json.loads(stdout), 200
    except json.JSONDecodeError:
        return {"status": "success", "raw": stdout}, 200


def list_images_paginated(filter_str: str, only_labeled: bool, labeled_only: bool, page: int, page_size: int):
    """
    Build paginated gallery payload.
    """
    images = get_sorted_images(IMAGES_DIR)

    for img in images:
        fname = img.get("filename")
        img["is_labeled"] = is_image_labeled(fname)

    global_total = len(images)
    global_labeled = sum(1 for img in images if img.get("is_labeled"))

    if filter_str:
        images = [
            img for img in images
            if filter_str in img["filename"].lower()
        ]

    if only_labeled:
        images = [img for img in images if not img.get("is_labeled")]

    if labeled_only:
        images = [img for img in images if img.get("is_labeled")]

    total_items = len(images)
    total_pages = max(1, (total_items + page_size - 1) // page_size)
    if page > total_pages:
        page = total_pages

    start_idx = (page - 1) * page_size
    end_idx = min(start_idx + page_size, total_items)
    page_items = images[start_idx:end_idx]

    # Compute labels count only for the current page to keep large datasets responsive.
    for img in page_items:
        base_name, _ = os.path.splitext(img["filename"])
        labels_path = os.path.join(LABELS_DIR, base_name + ".txt")
        labels_count = 0
        if os.path.exists(labels_path):
            with open(labels_path, "r") as lf:
                for line in lf:
                    if line.strip():
                        labels_count += 1
        img["labels_count"] = labels_count
        width, height = image_dimensions_for_image(img["filename"])
        img["image_width"] = width
        img["image_height"] = height

    return {
        "items": page_items,
        "page": page,
        "page_size": page_size,
        "total_items": total_items,
        "total_pages": total_pages,
        "shown_start": (start_idx + 1) if total_items > 0 else 0,
        "shown_end": end_idx,
        "global_total": global_total,
        "global_labeled": global_labeled,
    }


# ----------------------------------------------------------------------
# EXIF helpers
# ----------------------------------------------------------------------
def to_gps_decimal(gps_data, ref):
    """Convert GPS EXIF format to decimal coordinates."""
    if not gps_data:
        return None

    degrees, minutes, seconds = gps_data
    decimal = (
        degrees[0] / degrees[1]
        + (minutes[0] / minutes[1]) / 60
        + (seconds[0] / seconds[1]) / 3600
    )
    return -decimal if ref in ["S", "W"] else decimal


def extract_metadata(image_path):
    """Load per-image sidecar metadata (GPS and sensor fields)."""
    try:
        return load_metadata_for_image_path(image_path)
    except Exception:
        return dict(DEFAULT_METADATA)


def enrich_image_with_live_metadata(image_path: str) -> dict:
    """
    Best-effort metadata enrichment for an image.
    Adds GPS and BME280 readings when available.
    Returns metadata dict actually stored/available after attempt.
    """
    metadata = extract_metadata(image_path)
    metadata_updated = False

    fix = get_gps_fix()
    if fix:
        metadata["latitude"] = fix.get("latitude")
        metadata["longitude"] = fix.get("longitude")
        metadata_updated = True

    bme = get_bme280_reading()
    if bme:
        metadata["temperature"] = bme.get("temperature")
        metadata["humidity"] = bme.get("humidity")
        metadata["pressure"] = bme.get("pressure")
        metadata_updated = True

    if metadata_updated:
        try:
            save_metadata_for_image_path(image_path, metadata)
        except Exception:
            pass
    return metadata


def metadata_has_any_sensor_value(metadata: dict | None) -> bool:
    if not isinstance(metadata, dict):
        return False
    return any(
        metadata.get(k) is not None
        for k in ("latitude", "longitude", "temperature", "humidity", "pressure")
    )


def latest_recent_metadata_fallback(max_age_seconds: int = 600) -> dict:
    """
    Return most recent non-empty metadata from uploads/metadata when available.
    This is used as fallback when live GPS/BME sampling fails at capture time.
    """
    try:
        now_ts = time.time()
        candidates = []
        for path in glob.glob(os.path.join(METADATA_DIR, "*.json")):
            try:
                mtime = os.path.getmtime(path)
            except Exception:
                continue
            if now_ts - mtime > max_age_seconds:
                continue
            candidates.append((mtime, path))
        candidates.sort(reverse=True, key=lambda x: x[0])
        for _, path in candidates:
            try:
                with open(path, "r") as f:
                    data = json.load(f)
                if metadata_has_any_sensor_value(data):
                    return data
            except Exception:
                continue
    except Exception:
        pass
    return dict(DEFAULT_METADATA)


def ensure_metadata_for_image(image_path: str, filename: str) -> dict:
    """
    Ensure an image has a metadata sidecar.
    For ESP images, if metadata is empty, try live enrichment then fallback.
    """
    metadata = extract_metadata(image_path)
    sidecar_path = metadata_path_for_image_path(image_path)
    sidecar_exists = os.path.exists(sidecar_path)

    if filename.lower().startswith("esp_") and not metadata_has_any_sensor_value(metadata):
        metadata = enrich_image_with_live_metadata(image_path)
        if not metadata_has_any_sensor_value(metadata):
            fallback = latest_recent_metadata_fallback(max_age_seconds=900)
            if metadata_has_any_sensor_value(fallback):
                metadata = {
                    "temperature": fallback.get("temperature"),
                    "humidity": fallback.get("humidity"),
                    "pressure": fallback.get("pressure"),
                    "latitude": fallback.get("latitude"),
                    "longitude": fallback.get("longitude"),
                    "user_comment": metadata.get("user_comment", ""),
                }

    if (not sidecar_exists) or (filename.lower().startswith("esp_") and metadata_has_any_sensor_value(metadata)):
        try:
            save_metadata_for_image_path(image_path, metadata)
        except Exception:
            pass

    return metadata


# ----------------------------------------------------------------------
# Image listing (gallery)
# ----------------------------------------------------------------------
def get_sorted_images(image_folder):
    """
    Retrieve images and sort them by the timestamp encoded in the filename
    (e.g., 2023-07-20T20-19-46+0200_...), newest first.
    If parsing fails, fall back to file modification time.
    Image files: *.jpg, *.jpeg in IMAGES_DIR
    Label files are processed later in paginated endpoint for performance.
    """

    def parse_timestamp_from_filename(filename: str, file_path: str) -> float:
        """
        Try to parse the leading timestamp in the filename:
        2023-07-20T20-19-46+0200_b8-27-eb-3b-8d-1c.jpeg

        Prefix:  %Y-%m-%dT%H-%M-%S%z
        If anything goes wrong, fall back to os.path.getmtime(file_path).
        """
        base_name, _ = os.path.splitext(filename)

        try:
            # Take the part before the first underscore
            prefix = base_name.split("_", 1)[0]  # "2023-07-20T20-19-46+0200"
            dt = datetime.datetime.strptime(prefix, "%Y-%m-%dT%H-%M-%S%z")
            return dt.timestamp()
        except Exception:
            # Fallback: filesystem mtime
            return os.path.getmtime(file_path)

    image_files = [
        f
        for f in os.listdir(image_folder)
        if f.lower().endswith((".jpg", ".jpeg"))
    ]

    image_files_with_metadata = []
    for image in image_files:
        file_path = os.path.join(image_folder, image)

        # sort key based on filename timestamp (or mtime as fallback)
        sort_ts = parse_timestamp_from_filename(image, file_path)

        metadata = ensure_metadata_for_image(file_path, image)

        image_files_with_metadata.append(
            {
                "filename": image,
                "upload_ts": sort_ts,   # numeric timestamp used for sorting
                "file_size_bytes": os.path.getsize(file_path),
                "metadata": metadata,
            }
        )

    # Sort by our parsed timestamp (newest first)
    sorted_images = sorted(
        image_files_with_metadata, key=lambda x: x["upload_ts"], reverse=True
    )

    # Add a human-readable upload_time string
    for image in sorted_images:
        image["upload_time"] = time.strftime(
            "%Y-%m-%d %H:%M:%S", time.localtime(image["upload_ts"])
        )

    return sorted_images


# ----------------------------------------------------------------------
# Routes
# ----------------------------------------------------------------------
@app.route("/")
def index():
    return render_template("index.html")


@app.route("/gallery")
def gallery():
    return render_template("gallery.html")


@app.route("/capture")
def capture_page():
    return render_template("capture.html")


@app.route("/system")
def system_page():
    return render_template("system.html")


@app.route("/log")
def log_page():
    return render_template("log.html")


@app.route("/api/v1/logs")
def api_logs():
    """
    Return recent runtime logs for web dashboard.
    Query params:
      - lines: max lines per log source (default 120, max 500)
    """
    try:
        lines = int((request.args.get("lines") or "120").strip())
    except Exception:
        lines = 120
    lines = max(10, min(lines, 500))

    def tail_lines(path: str, n: int):
        if not path or not os.path.exists(path):
            return []
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                data = f.readlines()
            return [ln.rstrip("\n") for ln in data[-n:]]
        except Exception:
            return []

    capture_tail = tail_lines(CAPTURE_LOOP_LOG, lines)
    esp_tail = tail_lines(ESP_CAPTURE_LOOP_LOG, lines)

    server_tail = []
    try:
        proc = subprocess.run(
            ["journalctl", "--user", "-u", "agriapp-server.service", "-n", str(lines), "--no-pager"],
            cwd=os.path.dirname(__file__),
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
        if proc.returncode == 0:
            server_tail = [ln for ln in (proc.stdout or "").splitlines() if ln.strip()]
    except Exception:
        server_tail = []

    return jsonify(
        {
            "status": "success",
            "lines": lines,
            "sources": {
                "server": {
                    "path": "journalctl --user -u agriapp-server.service",
                    "items": server_tail,
                },
                "capture_rpi_loop": {
                    "path": CAPTURE_LOOP_LOG,
                    "items": capture_tail,
                },
                "capture_esp_loop": {
                    "path": ESP_CAPTURE_LOOP_LOG,
                    "items": esp_tail,
                },
            },
        }
    )


def _tail_lines(path: str, n: int):
    if not path or not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            data = f.readlines()
        return [ln.rstrip("\n") for ln in data[-n:]]
    except Exception:
        return []


def _journal_tail(lines: int):
    try:
        proc = subprocess.run(
            ["journalctl", "--user", "-u", "agriapp-server.service", "-n", str(lines), "--no-pager"],
            cwd=os.path.dirname(__file__),
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
        if proc.returncode == 0:
            return [ln for ln in (proc.stdout or "").splitlines() if ln.strip()]
    except Exception:
        pass
    return []


def _build_unified_log_text(lines: int):
    server_tail = _journal_tail(lines)
    capture_tail = _tail_lines(CAPTURE_LOOP_LOG, lines)
    esp_tail = _tail_lines(ESP_CAPTURE_LOOP_LOG, lines)
    rpi_status = _capture_loop_status_payload()

    parts = []
    parts.append("===== SERVER (agriapp-server.service) =====")
    parts.extend(server_tail if server_tail else ["(no entries)"])
    parts.append("")
    rpi_interval = rpi_status.get("interval_seconds")
    rpi_interval_part = f", interval={rpi_interval}s" if rpi_interval else ""
    parts.append(
        f"===== RPI LOOP ({CAPTURE_LOOP_LOG}) ===== "
        f"[status: {'running' if rpi_status.get('running') else 'stopped'}"
        f"{rpi_interval_part}]"
    )
    if capture_tail:
        parts.extend(capture_tail)
    else:
        parts.append("(no entries; start auto capture RPi to populate this log)")

    if esp_tail:
        esp_status = _capture_esp_loop_status_payload()
        esp_interval = esp_status.get("interval_seconds")
        esp_base = esp_status.get("esp_base")
        esp_interval_part = f", interval={esp_interval}s" if esp_interval else ""
        esp_base_part = f", base={esp_base}" if esp_base else ""
        parts.append("")
        parts.append(
            f"===== ESP LOOP ({ESP_CAPTURE_LOOP_LOG}) ===== "
            f"[status: {'running' if esp_status.get('running') else 'stopped'}"
            f"{esp_interval_part}"
            f"{esp_base_part}]"
        )
        parts.extend(esp_tail)
    return "\n".join(parts)


@app.route("/api/v1/logs/stream")
def api_logs_stream():
    """
    Server-Sent Events stream for unified runtime logs.
    Query params:
      - lines: max lines per source (default 120, max 500)
      - interval: push interval seconds (default 2, max 10)
    """
    try:
        lines = int((request.args.get("lines") or "120").strip())
    except Exception:
        lines = 120
    lines = max(10, min(lines, 500))

    try:
        interval = float((request.args.get("interval") or "2").strip())
    except Exception:
        interval = 2.0
    interval = max(1.0, min(interval, 10.0))

    def generate():
        last_text = None
        while True:
            text = _build_unified_log_text(lines)
            if text != last_text:
                payload = {
                    "status": "success",
                    "ts": int(time.time()),
                    "lines": lines,
                    "text": text,
                }
                yield f"data: {json.dumps(payload)}\n\n"
                last_text = text
            time.sleep(interval)

    return Response(
        generate(),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.route("/label")
def label_page():
    """Render the labeler UI for a given image (?image=...)."""
    image_name = request.args.get("image")
    if not image_name:
        return "Missing 'image' parameter", 400
    return render_template("labeler.html", image_name=image_name)


@app.route("/api/v1/labels", methods=["GET"])
def get_labels():
    """
    Return all boxes for an image.

    Priority:
    1) If per-image jsons/<stem>.json exists -> use that (authoritative).
    2) Else, if YOLO txt exists -> load those as is_tp = True.
    """
    image_name = normalize_image_filename(request.args.get("image", ""))
    if not image_name:
        return jsonify({"status": "error", "message": "Missing 'image' parameter"}), 400

    labels_out = []

    entry = load_labels_from_json_for_image(image_name)
    if isinstance(entry, list):
        for l in entry:
            try:
                cls = int(l["cls"])
                xc = float(l["x_center"])
                yc = float(l["y_center"])
                w = float(l["width"])
                h = float(l["height"])
            except (KeyError, ValueError, TypeError):
                continue

            is_tp = bool(l.get("is_tp", True))
            labels_out.append(
                {
                    "cls": cls,
                    "x_center": xc,
                    "y_center": yc,
                    "width": w,
                    "height": h,
                    "is_tp": is_tp,
                }
            )
    else:
        txt_path = yolo_txt_path_for_image(image_name)
        if os.path.exists(txt_path):
            try:
                with open(txt_path, "r") as f:
                    for line in f:
                        parts = line.strip().split()
                        if len(parts) != 5:
                            continue
                        cls_str, xc_str, yc_str, w_str, h_str = parts
                        cls = int(float(cls_str))
                        xc = float(xc_str)
                        yc = float(yc_str)
                        w = float(w_str)
                        h = float(h_str)

                        labels_out.append(
                            {
                                "cls": cls,
                                "x_center": xc,
                                "y_center": yc,
                                "width": w,
                                "height": h,
                                "is_tp": True,
                            }
                        )
            except Exception as e:
                return jsonify({"status": "error", "message": f"Error reading YOLO txt: {e}"}), 500

    return jsonify({"status": "success", "image": image_name, "labels": labels_out})


@app.route("/api/v1/labels", methods=["POST"])
def save_labels():
    """
    Save labels for one image.

    - jsons/<stem>.json: full boxes with is_tp (True/False).
    - labels/<stem>.txt: only boxes with is_tp == True (YOLO format).
    """
    data = request.get_json(silent=True) or {}
    image_name = normalize_image_filename(data.get("image", ""))
    labels = data.get("labels", [])

    if not image_name:
        return jsonify({"status": "error", "message": "Missing 'image' field"}), 400
    if not isinstance(labels, list):
        return jsonify({"status": "error", "message": "'labels' must be a list"}), 400

    txt_path = yolo_txt_path_for_image(image_name)

    yolo_lines = []
    status_entry = []

    for l in labels:
        try:
            cls = int(l["cls"])
            xc = float(l["x_center"])
            yc = float(l["y_center"])
            w = float(l["width"])
            h = float(l["height"])
        except (KeyError, ValueError, TypeError):
            continue

        is_tp = l.get("is_tp")
        if is_tp is None:
            is_tp = True
        is_tp = bool(is_tp)

        status_entry.append(
            {
                "cls": cls,
                "x_center": xc,
                "y_center": yc,
                "width": w,
                "height": h,
                "is_tp": is_tp,
            }
        )

        if is_tp:
            yolo_lines.append(f"{cls} {xc:.6f} {yc:.6f} {w:.6f} {h:.6f}")

    try:
        with open(txt_path, "w") as f:
            if yolo_lines:
                f.write("\n".join(yolo_lines) + "\n")
            else:
                f.write("")
    except Exception as e:
        return jsonify({"status": "error", "message": f"Failed to write label txt: {e}"}), 500

    try:
        save_labels_to_json_for_image(image_name, status_entry)
    except Exception as e:
        return jsonify({"status": "error", "message": f"Failed to write JSON: {e}"}), 500

    kept = sum(1 for s in status_entry if s.get("is_tp", True))
    total = len(status_entry)

    return jsonify(
        {
            "status": "success",
            "message": f"Saved {kept} TP labels (out of {total} total boxes) for {image_name}.",
        }
    )


@app.route("/receive", methods=["POST"])
@app.route("/api/v1/images/upload", methods=["POST"])
def receive_image():
    """
    Handles image upload and metadata extraction.
    Expects form field "image".
    """
    if "image" not in request.files:
        return jsonify({"error": "No image part"}), 400

    file = request.files["image"]
    if file.filename == "":
        return jsonify({"error": "No selected file"}), 400

    ext = file.filename.rsplit(".", 1)[-1].lower()
    if ext not in {"jpg", "jpeg"}:
        return jsonify({"error": "Invalid file type"}), 400

    filename = secure_filename(file.filename)
    file_path = os.path.join(IMAGES_DIR, filename)
    file.save(file_path)

    metadata = extract_metadata(file_path)

    socketio.emit(
        "new_image",
        {
            "filename": filename,
            "metadata": metadata,
        },
    )

    return jsonify({"message": "Image received", "metadata": metadata}), 200


@app.route("/uploaded_images")
def uploaded_images():
    # Legacy endpoint kept for backward compatibility.
    return jsonify(get_sorted_images(IMAGES_DIR))


@app.route("/api/v1/health")
def api_health():
    return jsonify({"status": "ok", "service": "agriapp", "version": "v1"})


@app.route("/api/v1/system/status")
def api_system_status():
    try:
        stat = os.statvfs(IMAGES_DIR)
        total_bytes = stat.f_blocks * stat.f_frsize
        free_bytes = stat.f_bavail * stat.f_frsize
    except Exception:
        total_bytes = 0
        free_bytes = 0

    hostname = socket.gethostname()
    return jsonify(
        {
            "status": "ok",
            "hostname": hostname,
            "images_dir": IMAGES_DIR,
            "disk_total_bytes": total_bytes,
            "disk_free_bytes": free_bytes,
            "timestamp": int(time.time()),
        }
    )


@app.route("/api/v1/system/reboot", methods=["POST"])
def api_system_reboot():
    """
    Request a Raspberry Pi reboot.
    Requires passwordless sudo for user running the server.
    """
    try:
        precheck = subprocess.run(
            ["sudo", "-n", "true"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except Exception as e:
        return jsonify({"status": "error", "message": f"sudo precheck failed: {e}"}), 500

    if precheck.returncode != 0:
        return jsonify(
            {
                "status": "error",
                "message": "sudo not configured for reboot (passwordless required)",
                "stderr": (precheck.stderr or "").strip()[-300:],
            }
        ), 403

    # Run reboot with a short delay so this request can return a response first.
    reboot_cmd = (
        "nohup sh -c 'sleep 1; "
        "sudo -n systemctl reboot || sudo -n shutdown -r now || sudo -n reboot' "
        "> /tmp/agriapp_reboot.log 2>&1 &"
    )
    try:
        subprocess.Popen(
            ["bash", "-lc", reboot_cmd],
            cwd=os.path.dirname(__file__),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception as e:
        return jsonify({"status": "error", "message": f"failed to schedule reboot: {e}"}), 500

    return jsonify(
        {
            "status": "success",
            "message": "reboot scheduled",
            "timestamp": int(time.time()),
        }
    )


@app.route("/api/v1/system/poweroff", methods=["POST"])
def api_system_poweroff():
    """
    Request a Raspberry Pi poweroff.
    Requires passwordless sudo for user running the server.
    """
    try:
        precheck = subprocess.run(
            ["sudo", "-n", "true"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except Exception as e:
        return jsonify({"status": "error", "message": f"sudo precheck failed: {e}"}), 500

    if precheck.returncode != 0:
        return jsonify(
            {
                "status": "error",
                "message": "sudo not configured for poweroff (passwordless required)",
                "stderr": (precheck.stderr or "").strip()[-300:],
            }
        ), 403

    poweroff_cmd = (
        "nohup sh -c 'sleep 1; "
        "sudo -n systemctl poweroff || sudo -n shutdown -h now || sudo -n poweroff' "
        "> /tmp/agriapp_poweroff.log 2>&1 &"
    )
    try:
        subprocess.Popen(
            ["bash", "-lc", poweroff_cmd],
            cwd=os.path.dirname(__file__),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception as e:
        return jsonify({"status": "error", "message": f"failed to schedule poweroff: {e}"}), 500

    return jsonify(
        {
            "status": "success",
            "message": "poweroff scheduled",
            "timestamp": int(time.time()),
        }
    )


@app.route("/api/v1/system/server/restart", methods=["POST"])
def api_system_server_restart():
    """
    Restart the agriapp server user service.
    """
    check = subprocess.run(
        ["systemctl", "--user", "is-enabled", "agriapp-server.service"],
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )
    if check.returncode != 0:
        return jsonify(
            {
                "status": "error",
                "message": "agriapp-server.service is not enabled for user",
                "stderr": (check.stderr or "").strip()[-300:],
            }
        ), 404

    restart_cmd = (
        "nohup sh -c 'sleep 1; systemctl --user restart agriapp-server.service' "
        "> /tmp/agriapp_server_restart.log 2>&1 &"
    )
    try:
        subprocess.Popen(
            ["bash", "-lc", restart_cmd],
            cwd=os.path.dirname(__file__),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception as e:
        return jsonify({"status": "error", "message": f"failed to schedule restart: {e}"}), 500

    return jsonify(
        {
            "status": "success",
            "message": "server restart scheduled",
            "timestamp": int(time.time()),
        }
    )


@app.route("/api/v1/network/mode", methods=["GET", "POST"])
def api_network_mode():
    """
    GET  -> current network mode/status.
    POST -> set network mode. JSON body: { "mode": "wifi_only|ap_only|hybrid_debug", "wifi_connection": "<optional profile name>" }.
    """
    if request.method == "GET":
        payload, status_code = run_network_mode_script(["--status"])
        return jsonify(payload), status_code

    data = request.get_json(silent=True) or {}
    mode = str(data.get("mode", "")).strip().lower()
    wifi_connection = str(data.get("wifi_connection", "")).strip()
    if mode not in ("wifi_only", "ap_only", "hybrid_debug"):
        return jsonify({"status": "error", "message": "invalid mode"}), 400

    extra_env = {}
    if wifi_connection:
        extra_env["WIFI_CLIENT_CONN"] = wifi_connection
    payload, status_code = run_network_mode_script(["--set", mode], extra_env=extra_env)
    if status_code == 200 and isinstance(payload, dict):
        payload["applied_mode"] = mode
        if wifi_connection:
            payload["requested_wifi_connection"] = wifi_connection
    return jsonify(payload), status_code


@app.route("/api/v1/images")
def api_images():
    """
    Paginated images API for tablet/remote clients.
    """
    filter_str = request.args.get("filter", "").strip().lower()
    only_labeled_raw = request.args.get("only_labeled", "false").strip().lower()
    only_labeled = only_labeled_raw in ("1", "true", "yes", "on")
    labeled_only_raw = request.args.get("labeled_only", "false").strip().lower()
    labeled_only = labeled_only_raw in ("1", "true", "yes", "on")
    page_raw = request.args.get("page", "1").strip()
    page_size_raw = request.args.get("page_size", "24").strip()

    try:
        page = int(page_raw)
    except ValueError:
        page = 1

    try:
        page_size = int(page_size_raw)
    except ValueError:
        page_size = 24

    page = max(page, 1)
    page_size = max(1, min(page_size, 200))

    return jsonify(
        list_images_paginated(
            filter_str=filter_str,
            only_labeled=only_labeled,
            labeled_only=labeled_only,
            page=page,
            page_size=page_size,
        )
    )


@app.route("/api/v1/images/<path:filename>/status")
def api_image_status(filename):
    """
    Return labeling status for one image (API variant).
    """
    clean_name = normalize_image_filename(filename)
    if not clean_name:
        return jsonify({"status": "error", "message": "filename missing"}), 400

    image_path = os.path.join(IMAGES_DIR, clean_name)
    if not os.path.exists(image_path):
        return jsonify({"status": "error", "message": "image not found"}), 404

    metadata = extract_metadata(image_path)
    return jsonify(
        {
            "status": "success",
            "filename": clean_name,
            "is_labeled": is_image_labeled(clean_name),
            "labels_count": labels_count_for_image(clean_name),
            "file_size_bytes": file_size_bytes_for_image(clean_name),
            "metadata": metadata,
        }
    )


@app.route("/api/v1/images/<path:filename>/thumbnail")
def api_image_thumbnail(filename):
    """
    Serve cached thumbnail for one image.
    Query:
      - w: target max-side width (default 320, range 64..1024)
    """
    clean_name = normalize_image_filename(filename)
    if not clean_name:
        return jsonify({"status": "error", "message": "filename missing"}), 400

    image_path = os.path.join(IMAGES_DIR, clean_name)
    if not os.path.exists(image_path):
        return jsonify({"status": "error", "message": "image not found"}), 404

    try:
        width = int((request.args.get("w") or "320").strip())
    except Exception:
        width = 320
    width = max(64, min(width, 1024))

    thumb_path = build_or_get_thumbnail(clean_name, width)
    if thumb_path and os.path.exists(thumb_path):
        return send_file(thumb_path, mimetype="image/jpeg", max_age=86400)

    # Fallback if thumbnail generation is unavailable (e.g. Pillow missing).
    return send_file(image_path, mimetype="image/jpeg", max_age=0)


@app.route("/api/v1/capture/rpi/oneshot", methods=["POST"])
def api_capture_oneshot():
    """
    Trigger a single RPi capture via scripts/capture_rpi.sh --oneshot.
    """
    script_path = _capture_loop_script_path()
    if not os.path.exists(script_path):
        return jsonify({"status": "error", "message": "capture_rpi.sh not found"}), 500

    cmd = ["bash", script_path, "--oneshot"]
    try:
        proc = subprocess.run(
            cmd,
            cwd=os.path.dirname(__file__),
            capture_output=True,
            text=True,
            timeout=90,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return jsonify({"status": "error", "message": "capture timeout"}), 504
    except Exception as e:
        return jsonify({"status": "error", "message": f"capture failed: {e}"}), 500

    stdout = (proc.stdout or "").strip()
    stderr = (proc.stderr or "").strip()
    if proc.returncode != 0:
        return jsonify(
            {
                "status": "error",
                "returncode": proc.returncode,
                "stdout": stdout[-500:],
                "stderr": stderr[-500:],
            }
        ), 500

    newest = get_sorted_images(IMAGES_DIR)
    latest_filename = newest[0]["filename"] if newest else None
    latest_metadata = None
    if latest_filename:
        latest_path = os.path.join(IMAGES_DIR, latest_filename)
        latest_metadata = enrich_image_with_live_metadata(latest_path)

    return jsonify(
        {
            "status": "success",
            "message": "oneshot completed",
            "latest_filename": latest_filename,
            "metadata": latest_metadata,
            "stdout": stdout[-500:],
        }
    )


@app.route("/api/v1/capture/loop/status", methods=["GET"])
def api_capture_loop_status():
    with CAPTURE_LOOP_LOCK:
        payload = _capture_loop_status_payload()
    return jsonify(payload)


@app.route("/api/v1/capture/loop/start", methods=["POST"])
def api_capture_loop_start():
    global CAPTURE_LOOP_PROC, CAPTURE_LOOP_STARTED_AT, CAPTURE_LOOP_INTERVAL
    data = request.get_json(silent=True) or {}
    interval_raw = data.get("interval_seconds", 300)
    try:
        interval = int(interval_raw)
    except Exception:
        return jsonify({"status": "error", "message": "invalid interval_seconds"}), 400

    if interval < 5 or interval > 86400:
        return jsonify({"status": "error", "message": "interval_seconds must be in [5, 86400]"}), 400

    script_path = _capture_loop_script_path()
    if not os.path.exists(script_path):
        return jsonify({"status": "error", "message": "capture_rpi.sh not found"}), 500

    with CAPTURE_LOOP_LOCK:
        if _capture_loop_is_running():
            payload = _capture_loop_status_payload()
            payload["message"] = "capture loop already running"
            return jsonify(payload), 409

        try:
            log_f = open(CAPTURE_LOOP_LOG, "a", buffering=1)
            log_f.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] start interval={interval}\n")
            CAPTURE_LOOP_PROC = subprocess.Popen(
                ["bash", script_path, "--interval", str(interval)],
                cwd=os.path.dirname(__file__),
                stdout=log_f,
                stderr=log_f,
                text=True,
            )
            CAPTURE_LOOP_STARTED_AT = int(time.time())
            CAPTURE_LOOP_INTERVAL = interval
        except Exception as e:
            CAPTURE_LOOP_PROC = None
            CAPTURE_LOOP_STARTED_AT = None
            CAPTURE_LOOP_INTERVAL = None
            return jsonify({"status": "error", "message": f"failed to start capture loop: {e}"}), 500

        payload = _capture_loop_status_payload()
        payload["message"] = "capture loop started"
        return jsonify(payload)


@app.route("/api/v1/capture/loop/stop", methods=["POST"])
def api_capture_loop_stop():
    global CAPTURE_LOOP_PROC, CAPTURE_LOOP_STARTED_AT, CAPTURE_LOOP_INTERVAL

    with CAPTURE_LOOP_LOCK:
        if not _capture_loop_is_running():
            CAPTURE_LOOP_PROC = None
            CAPTURE_LOOP_STARTED_AT = None
            CAPTURE_LOOP_INTERVAL = None
            return jsonify({"status": "success", "running": False, "message": "capture loop already stopped"})

        proc = CAPTURE_LOOP_PROC
        try:
            proc.terminate()
            proc.wait(timeout=8)
        except Exception:
            try:
                proc.kill()
                proc.wait(timeout=3)
            except Exception:
                pass

        CAPTURE_LOOP_PROC = None
        CAPTURE_LOOP_STARTED_AT = None
        CAPTURE_LOOP_INTERVAL = None

    return jsonify({"status": "success", "running": False, "message": "capture loop stopped"})


@app.route("/api/v1/capture/esp/oneshot", methods=["GET"])
def api_esp_capture_proxy():
    """
    Proxy one JPEG capture from ESP-CAM through Raspberry Pi.
    Useful when tablet cannot reliably reach ESP directly.
    """
    esp_base = (request.args.get("esp_base") or "").strip()
    if not esp_base:
        return jsonify({"status": "error", "message": "missing esp_base"}), 400

    try:
        parsed = urllib.parse.urlsplit(esp_base)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            return jsonify({"status": "error", "message": "invalid esp_base"}), 400

        host = parsed.hostname or ""
        ip_obj = ipaddress.ip_address(host)
        if not ip_obj.is_private:
            return jsonify({"status": "error", "message": "esp_base must be private IP"}), 400
    except Exception:
        return jsonify({"status": "error", "message": "invalid esp_base"}), 400

    base = f"{parsed.scheme}://{parsed.netloc}"
    esp_capture_url = f"{base}/capture"
    helper = _capture_esp_helper_path()
    if not os.path.exists(helper):
        return jsonify({"status": "error", "message": "capture_esp.py helper not found"}), 500

    tmp_name = os.path.join("/tmp", f"esp_api_{int(time.time() * 1000)}.jpg")
    strict_cmd = [
        "python3",
        helper,
        "--url",
        esp_capture_url,
        "--framesize",
        "qxga",
        "--timeout",
        "20",
        "--out",
        tmp_name,
    ]
    fallback_cmd = [
        "python3",
        helper,
        "--url",
        esp_capture_url,
        "--timeout",
        "20",
        "--out",
        tmp_name,
    ]
    lowres_cmd = [
        "python3",
        helper,
        "--url",
        esp_capture_url,
        "--framesize",
        "vga",
        "--timeout",
        "12",
        "--out",
        tmp_name,
    ]

    try:
        proc = subprocess.run(
            strict_cmd,
            cwd=os.path.dirname(__file__),
            capture_output=True,
            text=True,
            timeout=45,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return jsonify({"status": "error", "message": "esp capture timeout"}), 504
    except Exception as e:
        return jsonify({"status": "error", "message": f"esp capture failed: {e}"}), 500

    used_fallback = False
    used_lowres = False
    strict_stdout = (proc.stdout or "")
    strict_stderr = (proc.stderr or "")
    if proc.returncode != 0:
        # Some firmware variants intermittently reject /control (framesize/quality).
        # Retry a plain /capture first to avoid hard-failing when controls fail.
        try:
            proc = subprocess.run(
                fallback_cmd,
                cwd=os.path.dirname(__file__),
                capture_output=True,
                text=True,
                timeout=45,
                check=False,
            )
            used_fallback = proc.returncode == 0
        except subprocess.TimeoutExpired:
            return jsonify({"status": "error", "message": "esp capture timeout"}), 504
        except Exception as e:
            return jsonify({"status": "error", "message": f"esp capture failed: {e}"}), 500

    if proc.returncode != 0:
        # Final safety net: force a low-res frame to keep auto-loop alive on weak links.
        try:
            proc = subprocess.run(
                lowres_cmd,
                cwd=os.path.dirname(__file__),
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            used_lowres = proc.returncode == 0
        except subprocess.TimeoutExpired:
            return jsonify({"status": "error", "message": "esp capture timeout"}), 504
        except Exception as e:
            return jsonify({"status": "error", "message": f"esp capture failed: {e}"}), 500

    if proc.returncode != 0:
        return jsonify(
            {
                "status": "error",
                "message": "esp capture command failed",
                "returncode": proc.returncode,
                "stdout": (proc.stdout or "")[-500:],
                "stderr": (proc.stderr or "")[-500:],
                "strict_stdout": strict_stdout[-500:],
                "strict_stderr": strict_stderr[-500:],
            }
        ), 502

    if not os.path.exists(tmp_name) or os.path.getsize(tmp_name) < 1024:
        return jsonify(
            {
                "status": "error",
                "message": "esp capture produced invalid output",
                "stdout": (proc.stdout or "")[-500:],
                "stderr": (proc.stderr or "")[-500:],
            }
        ), 502

    with open(tmp_name, "rb") as f:
        data = f.read()
    try:
        os.remove(tmp_name)
    except Exception:
        pass

    saved_name = f"esp_{time.strftime('%Y%m%d-%H%M%S')}.jpg"
    saved_path = os.path.join(IMAGES_DIR, saved_name)
    saved_metadata = dict(DEFAULT_METADATA)
    try:
        with open(saved_path, "wb") as out:
            out.write(data)
        saved_metadata = enrich_image_with_live_metadata(saved_path)
        if not metadata_has_any_sensor_value(saved_metadata):
            fallback = latest_recent_metadata_fallback(max_age_seconds=900)
            if metadata_has_any_sensor_value(fallback):
                saved_metadata = {
                    "temperature": fallback.get("temperature"),
                    "humidity": fallback.get("humidity"),
                    "pressure": fallback.get("pressure"),
                    "latitude": fallback.get("latitude"),
                    "longitude": fallback.get("longitude"),
                    "user_comment": "",
                }
                try:
                    save_metadata_for_image_path(saved_path, saved_metadata)
                except Exception:
                    pass
        else:
            try:
                save_metadata_for_image_path(saved_path, saved_metadata)
            except Exception:
                pass
        if not metadata_has_any_sensor_value(saved_metadata):
            # Ensure metadata sidecar exists even when all sensor values are unavailable.
            try:
                save_metadata_for_image_path(saved_path, saved_metadata)
            except Exception:
                pass
    except Exception:
        # Keep response usable even if local save fails.
        saved_name = ""

    resp = send_file(
        io.BytesIO(data),
        mimetype="image/jpeg",
        as_attachment=False,
        download_name="esp_capture.jpg",
    )
    if used_fallback:
        resp.headers["X-AgriApp-Esp-Fallback"] = "1"
    if used_lowres:
        resp.headers["X-AgriApp-Esp-LowRes"] = "1"
    if saved_name:
        resp.headers["X-AgriApp-Filename"] = saved_name
        if saved_metadata.get("latitude") is not None:
            resp.headers["X-AgriApp-Latitude"] = str(saved_metadata["latitude"])
        if saved_metadata.get("longitude") is not None:
            resp.headers["X-AgriApp-Longitude"] = str(saved_metadata["longitude"])
        if saved_metadata.get("temperature") is not None:
            resp.headers["X-AgriApp-Temperature"] = str(saved_metadata["temperature"])
        if saved_metadata.get("humidity") is not None:
            resp.headers["X-AgriApp-Humidity"] = str(saved_metadata["humidity"])
        if saved_metadata.get("pressure") is not None:
            resp.headers["X-AgriApp-Pressure"] = str(saved_metadata["pressure"])
    return resp


@app.route("/api/v1/esp/status", methods=["GET"])
def api_esp_status():
    """
    Probe ESP status endpoint through the server to avoid client-side CORS issues.
    Query params:
      - esp_base: e.g. http://192.168.4.239
    """
    esp_base = (request.args.get("esp_base") or "").strip()
    if not esp_base:
        return jsonify({"status": "error", "message": "missing esp_base"}), 400

    try:
        parsed = urllib.parse.urlsplit(esp_base)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            return jsonify({"status": "error", "message": "invalid esp_base"}), 400
        host = parsed.hostname or ""
        ip_obj = ipaddress.ip_address(host)
        if not ip_obj.is_private:
            return jsonify({"status": "error", "message": "esp_base must be private IP"}), 400
    except Exception:
        return jsonify({"status": "error", "message": "invalid esp_base"}), 400

    base = f"{parsed.scheme}://{parsed.netloc}"
    status_url = f"{base}/status"
    started = time.time()
    try:
        req = urllib.request.Request(status_url, method="GET")
        with urllib.request.urlopen(req, timeout=3) as resp:
            raw = resp.read()
            latency_ms = int((time.time() - started) * 1000)
            body_text = raw.decode("utf-8", errors="replace")
            parsed_json = None
            try:
                parsed_json = json.loads(body_text)
            except Exception:
                parsed_json = None
            return jsonify(
                {
                    "status": "success",
                    "reachable": True,
                    "esp_base": base,
                    "latency_ms": latency_ms,
                    "http_status": getattr(resp, "status", 200),
                    "esp_payload": parsed_json,
                }
            )
    except Exception as e:
        latency_ms = int((time.time() - started) * 1000)
        return jsonify(
            {
                "status": "success",
                "reachable": False,
                "esp_base": base,
                "latency_ms": latency_ms,
                "message": str(e),
            }
        )


@app.route("/api/v1/capture/esp/loop/status", methods=["GET"])
def api_esp_capture_loop_status():
    with ESP_CAPTURE_LOOP_LOCK:
        payload = _capture_esp_loop_status_payload()
    return jsonify(payload)


@app.route("/api/v1/capture/esp/loop/start", methods=["POST"])
def api_esp_capture_loop_start():
    global ESP_CAPTURE_LOOP_PROC, ESP_CAPTURE_LOOP_STARTED_AT, ESP_CAPTURE_LOOP_INTERVAL, ESP_CAPTURE_LOOP_BASE
    data = request.get_json(silent=True) or {}
    interval_raw = data.get("interval_seconds", 300)
    esp_base = (data.get("esp_base") or "").strip()

    try:
        interval = int(interval_raw)
    except Exception:
        return jsonify({"status": "error", "message": "invalid interval_seconds"}), 400
    if interval < 5 or interval > 86400:
        return jsonify({"status": "error", "message": "interval_seconds must be in [5, 86400]"}), 400
    if not esp_base:
        return jsonify({"status": "error", "message": "missing esp_base"}), 400

    try:
        parsed = urllib.parse.urlsplit(esp_base)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            return jsonify({"status": "error", "message": "invalid esp_base"}), 400
        host = parsed.hostname or ""
        ip_obj = ipaddress.ip_address(host)
        if not ip_obj.is_private:
            return jsonify({"status": "error", "message": "esp_base must be private IP"}), 400
    except Exception:
        return jsonify({"status": "error", "message": "invalid esp_base"}), 400

    base = f"{parsed.scheme}://{parsed.netloc}"
    esp_base_enc = urllib.parse.quote(base, safe="")
    loop_cmd = (
        f'while true; do '
        f'curl -fsS --max-time 50 '
        f'"http://127.0.0.1:5000/api/v1/capture/esp/oneshot?esp_base={esp_base_enc}" '
        f'-o /dev/null || echo "[WARN] esp loop capture failed"; '
        f'sleep {interval}; '
        f'done'
    )

    with ESP_CAPTURE_LOOP_LOCK:
        if _capture_esp_loop_is_running():
            payload = _capture_esp_loop_status_payload()
            payload["message"] = "esp capture loop already running"
            return jsonify(payload), 409

        try:
            log_f = open(ESP_CAPTURE_LOOP_LOG, "a", buffering=1)
            log_f.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] start interval={interval} base={base}\n")
            ESP_CAPTURE_LOOP_PROC = subprocess.Popen(
                ["bash", "-lc", loop_cmd],
                cwd=os.path.dirname(__file__),
                stdout=log_f,
                stderr=log_f,
                text=True,
            )
            ESP_CAPTURE_LOOP_STARTED_AT = int(time.time())
            ESP_CAPTURE_LOOP_INTERVAL = interval
            ESP_CAPTURE_LOOP_BASE = base
        except Exception as e:
            ESP_CAPTURE_LOOP_PROC = None
            ESP_CAPTURE_LOOP_STARTED_AT = None
            ESP_CAPTURE_LOOP_INTERVAL = None
            ESP_CAPTURE_LOOP_BASE = None
            return jsonify({"status": "error", "message": f"failed to start esp capture loop: {e}"}), 500

        payload = _capture_esp_loop_status_payload()
        payload["message"] = "esp capture loop started"
        return jsonify(payload)


@app.route("/api/v1/capture/esp/loop/stop", methods=["POST"])
def api_esp_capture_loop_stop():
    global ESP_CAPTURE_LOOP_PROC, ESP_CAPTURE_LOOP_STARTED_AT, ESP_CAPTURE_LOOP_INTERVAL, ESP_CAPTURE_LOOP_BASE

    with ESP_CAPTURE_LOOP_LOCK:
        if not _capture_esp_loop_is_running():
            ESP_CAPTURE_LOOP_PROC = None
            ESP_CAPTURE_LOOP_STARTED_AT = None
            ESP_CAPTURE_LOOP_INTERVAL = None
            ESP_CAPTURE_LOOP_BASE = None
            return jsonify({"status": "success", "running": False, "message": "esp capture loop already stopped"})

        proc = ESP_CAPTURE_LOOP_PROC
        try:
            proc.terminate()
            proc.wait(timeout=8)
        except Exception:
            try:
                proc.kill()
                proc.wait(timeout=3)
            except Exception:
                pass

        ESP_CAPTURE_LOOP_PROC = None
        ESP_CAPTURE_LOOP_STARTED_AT = None
        ESP_CAPTURE_LOOP_INTERVAL = None
        ESP_CAPTURE_LOOP_BASE = None

    return jsonify({"status": "success", "running": False, "message": "esp capture loop stopped"})


@app.route("/image-status")
def image_status():
    """
    Return labeling status for one image.

    Query parameters:
      - filename: image filename
    """
    filename_raw = request.args.get("filename", "")
    filename = secure_filename(os.path.basename(filename_raw))
    if not filename:
        return jsonify({"status": "error", "message": "filename missing"}), 400

    image_path = os.path.join(IMAGES_DIR, filename)
    if not os.path.exists(image_path):
        return jsonify({"status": "error", "message": "image not found"}), 404

    metadata = extract_metadata(image_path)
    return jsonify(
        {
            "status": "success",
            "filename": filename,
            "is_labeled": is_image_labeled(filename),
            "labels_count": labels_count_for_image(filename),
            "file_size_bytes": file_size_bytes_for_image(filename),
            "metadata": metadata,
        }
    )


@app.route("/api/v1/images/delete", methods=["POST"])
def delete_image():
    """
    Delete an image, its corresponding .txt labels/.json and metadata sidecar.
    """
    data = request.get_json(silent=True) or {}
    filename = normalize_image_filename(data.get("filename", ""))

    if not filename:
        return jsonify({"status": "error", "message": "filename missing"}), 400

    img_path = os.path.join(IMAGES_DIR, filename)
    base, _ = os.path.splitext(filename)
    labels_path = os.path.join(LABELS_DIR, base + ".txt")
    json_path = json_path_for_image(filename)
    metadata_path = metadata_path_for_image_path(img_path)

    removed = {"image": False, "labels": False, "json": False, "metadata": False, "thumbnails": False}

    try:
        if os.path.exists(img_path):
            os.remove(img_path)
            removed["image"] = True

        if os.path.exists(labels_path):
            os.remove(labels_path)
            removed["labels"] = True

        if os.path.exists(json_path):
            os.remove(json_path)
            removed["json"] = True
        if os.path.exists(metadata_path):
            os.remove(metadata_path)
            removed["metadata"] = True
        if remove_thumbnails_for_image(filename) > 0:
            removed["thumbnails"] = True

        status = "success"
        if not (removed["image"] or removed["labels"] or removed["json"] or removed["metadata"] or removed["thumbnails"]):
            status = "not_found"
        elif not removed["image"]:
            status = "partial"

        return jsonify({"status": status, "removed": removed})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route("/api/v1/images/delete-all", methods=["POST"])
def api_delete_all_images():
    """
    Delete all images and their related labels/json/metadata files.
    """
    removed_images = 0
    removed_labels = 0
    removed_jsons = 0
    removed_metadata = 0
    removed_thumbnails = 0
    errors = []

    for name in os.listdir(IMAGES_DIR):
        if not name.lower().endswith((".jpg", ".jpeg", ".png")):
            continue

        filename = normalize_image_filename(name)
        if not filename:
            continue

        img_path = os.path.join(IMAGES_DIR, filename)
        base, _ = os.path.splitext(filename)
        label_path = os.path.join(LABELS_DIR, base + ".txt")
        json_path = json_path_for_image(filename)
        metadata_path = metadata_path_for_image_path(img_path)

        try:
            if os.path.exists(img_path):
                os.remove(img_path)
                removed_images += 1
        except Exception as e:
            errors.append(f"image:{filename}:{e}")

        try:
            if os.path.exists(label_path):
                os.remove(label_path)
                removed_labels += 1
        except Exception as e:
            errors.append(f"label:{filename}:{e}")

        try:
            if os.path.exists(json_path):
                os.remove(json_path)
                removed_jsons += 1
        except Exception as e:
            errors.append(f"json:{filename}:{e}")

        try:
            if os.path.exists(metadata_path):
                os.remove(metadata_path)
                removed_metadata += 1
        except Exception as e:
            errors.append(f"metadata:{filename}:{e}")

        try:
            removed_thumbnails += remove_thumbnails_for_image(filename)
        except Exception as e:
            errors.append(f"thumbnail:{filename}:{e}")

    status = "success" if not errors else "partial"
    return jsonify(
        {
            "status": status,
            "removed_images": removed_images,
            "removed_labels": removed_labels,
            "removed_jsons": removed_jsons,
            "removed_metadata": removed_metadata,
            "removed_thumbnails": removed_thumbnails,
            "errors_count": len(errors),
        }
    )


@app.route("/download-dataset")
@app.route("/api/v1/download/dataset")
def download_dataset():
    """
    Create a zip on the fly containing:
      - image files from IMAGES_DIR -> images/...
      - txt label files from LABELS_DIR -> labels/...
      - json files from JSONS_DIR -> jsons/...
      - metadata files from METADATA_DIR -> metadata/...
    """
    memory_file = io.BytesIO()

    with zipfile.ZipFile(
        memory_file, mode="w", compression=zipfile.ZIP_DEFLATED
    ) as zf:
        # Add images to /images
        for root, dirs, files in os.walk(IMAGES_DIR):
            for fname in files:
                ext = os.path.splitext(fname)[1].lower()
                if ext not in [".jpg", ".jpeg", ".png"]:
                    continue
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, IMAGES_DIR)
                arcname = os.path.join("images", rel_path)
                zf.write(full_path, arcname)

        # Add labels to /labels (only .txt)
        for root, dirs, files in os.walk(LABELS_DIR):
            for fname in files:
                ext = os.path.splitext(fname)[1].lower()
                if ext != ".txt":
                    continue
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, LABELS_DIR)
                arcname = os.path.join("labels", rel_path)
                zf.write(full_path, arcname)

        # Add jsons to /jsons (only .json)
        for root, dirs, files in os.walk(JSONS_DIR):
            for fname in files:
                ext = os.path.splitext(fname)[1].lower()
                if ext != ".json":
                    continue
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, JSONS_DIR)
                arcname = os.path.join("jsons", rel_path)
                zf.write(full_path, arcname)

        # Add metadata to /metadata (only .json)
        for root, dirs, files in os.walk(METADATA_DIR):
            for fname in files:
                ext = os.path.splitext(fname)[1].lower()
                if ext != ".json":
                    continue
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, METADATA_DIR)
                arcname = os.path.join("metadata", rel_path)
                zf.write(full_path, arcname)

    memory_file.seek(0)

    return send_file(
        memory_file,
        mimetype="application/zip",
        as_attachment=True,
        download_name="agriapp_dataset.zip",
    )


@app.route("/download-dataset-selected", methods=["POST"])
@app.route("/api/v1/download/dataset-selected", methods=["POST"])
def download_dataset_selected():
    """
    Create a zip on the fly containing only selected images and their related
    labels/json files.
    Expects JSON body:
      { "filenames": ["rpi_20260313-101500.jpg", "esp_20260313-101530.jpg", ...] }
    """
    data = request.get_json(silent=True) or {}
    filenames = data.get("filenames", [])
    if not isinstance(filenames, list):
        return jsonify({"error": "filenames must be a list"}), 400

    clean_names = []
    for raw in filenames:
        if not isinstance(raw, str):
            continue
        fname = secure_filename(os.path.basename(raw))
        if not fname:
            continue
        clean_names.append(fname)

    # Preserve order and remove duplicates
    clean_names = list(dict.fromkeys(clean_names))
    if not clean_names:
        return jsonify({"error": "No valid filenames provided"}), 400

    memory_file = io.BytesIO()
    written = {"images": 0, "labels": 0, "jsons": 0, "metadata": 0}

    with zipfile.ZipFile(
        memory_file, mode="w", compression=zipfile.ZIP_DEFLATED
    ) as zf:
        for fname in clean_names:
            image_path = os.path.join(IMAGES_DIR, fname)
            if not os.path.exists(image_path):
                continue

            zf.write(image_path, os.path.join("images", fname))
            written["images"] += 1

            stem, _ = os.path.splitext(fname)

            label_path = os.path.join(LABELS_DIR, stem + ".txt")
            if os.path.exists(label_path):
                zf.write(label_path, os.path.join("labels", stem + ".txt"))
                written["labels"] += 1

            json_path = os.path.join(JSONS_DIR, stem + ".json")
            if os.path.exists(json_path):
                zf.write(json_path, os.path.join("jsons", stem + ".json"))
                written["jsons"] += 1

            metadata_path = os.path.join(METADATA_DIR, stem + ".json")
            if os.path.exists(metadata_path):
                zf.write(metadata_path, os.path.join("metadata", stem + ".json"))
                written["metadata"] += 1

    if written["images"] == 0:
        return jsonify({"error": "No matching images found"}), 404

    memory_file.seek(0)
    return send_file(
        memory_file,
        mimetype="application/zip",
        as_attachment=True,
        download_name="agriapp_dataset_visible.zip",
    )


@app.route("/download-image-with-labels")
@app.route("/api/v1/images/download-with-labels")
def download_image_with_labels():
    """
    Download a zip containing one image and its related label/json/metadata files.

    Query parameters:
      - image: image filename
    """
    image_raw = request.args.get("image", "")
    filename = secure_filename(os.path.basename(image_raw))
    if not filename:
        return jsonify({"error": "image parameter missing"}), 400

    image_path = os.path.join(IMAGES_DIR, filename)
    if not os.path.exists(image_path):
        return jsonify({"error": "image not found"}), 404

    stem, _ = os.path.splitext(filename)
    label_path = os.path.join(LABELS_DIR, stem + ".txt")
    json_path = os.path.join(JSONS_DIR, stem + ".json")
    metadata_path = os.path.join(METADATA_DIR, stem + ".json")

    memory_file = io.BytesIO()
    with zipfile.ZipFile(
        memory_file, mode="w", compression=zipfile.ZIP_DEFLATED
    ) as zf:
        zf.write(image_path, os.path.join("images", filename))
        if os.path.exists(label_path):
            zf.write(label_path, os.path.join("labels", stem + ".txt"))
        if os.path.exists(json_path):
            zf.write(json_path, os.path.join("jsons", stem + ".json"))
        if os.path.exists(metadata_path):
            zf.write(metadata_path, os.path.join("metadata", stem + ".json"))

    memory_file.seek(0)
    return send_file(
        memory_file,
        mimetype="application/zip",
        as_attachment=True,
        download_name=f"{stem}_labels_bundle.zip",
    )


if __name__ == "__main__":
    socketio.run(
        app,
        host="0.0.0.0",
        port=5000,
        debug=False,
        use_reloader=False,
        allow_unsafe_werkzeug=True,
    )
