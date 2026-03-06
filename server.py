from flask import Flask, request, render_template, jsonify, send_file
from flask_socketio import SocketIO
from werkzeug.utils import secure_filename
import os
import io
import zipfile
import time
import json
import piexif
import datetime  # needed for timestamp parsing

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

os.makedirs(IMAGES_DIR, exist_ok=True)
os.makedirs(LABELS_DIR, exist_ok=True)
os.makedirs(JSONS_DIR, exist_ok=True)


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
    """
    TEMP: disable EXIF parsing for performance testing.
    """
    return {
        "temperature": None,
        "pressure": None,
        "humidity": None,
        "latitude": None,
        "longitude": None,
        "user_comment": "",
    }


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

        metadata = extract_metadata(file_path)

        image_files_with_metadata.append(
            {
                "filename": image,
                "upload_ts": sort_ts,   # numeric timestamp used for sorting
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


@app.route("/label")
def label_page():
    """Render the labeler UI for a given image (?image=...)."""
    image_name = request.args.get("image")
    if not image_name:
        return "Missing 'image' parameter", 400
    return render_template("labeler.html", image_name=image_name)


@app.route("/get_labels")
def get_labels():
    """
    Return all boxes for an image.

    Priority:
    1) If per-image jsons/<stem>.json exists -> use that (authoritative).
    2) Else, if YOLO txt exists -> load those as is_tp = True.
    """
    image_name = request.args.get("image")
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


@app.route("/save_labels", methods=["POST"])
def save_labels():
    """
    Save labels for one image.

    - jsons/<stem>.json: full boxes with is_tp (True/False).
    - labels/<stem>.txt: only boxes with is_tp == True (YOLO format).
    """
    data = request.get_json(silent=True) or {}
    image_name = data.get("image")
    labels = data.get("labels", [])

    if not image_name:
        return jsonify({"status": "error", "message": "Missing 'image' field"}), 400

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
    # kept for backward compatibility (same as /get-images)
    return jsonify(get_sorted_images(IMAGES_DIR))


@app.route("/get-images")
def get_images():
    """
    Return the list of images with optional filtering.

    Query parameters:
      - filter: substring (case-insensitive) to match in filename
      - only_labeled: if true/1/yes/on -> keep only NON-labeled images
                      (as per your latest semantics)
      - labeled_only: if true/1/yes/on -> keep only labeled images
      - page: 1-based page number (default 1)
      - page_size: page size (default 24, max 200)
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

    return jsonify(
        {
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
    )


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

    return jsonify(
        {
            "status": "success",
            "filename": filename,
            "is_labeled": is_image_labeled(filename),
            "labels_count": labels_count_for_image(filename),
        }
    )


@app.route("/delete-image", methods=["POST"])
def delete_image():
    """
    Delete an image, its corresponding .txt labels, and its .json (if present).
    """
    data = request.get_json(silent=True) or {}
    filename = data.get("filename")

    if not filename:
        return jsonify({"status": "error", "message": "filename missing"}), 400

    img_path = os.path.join(IMAGES_DIR, filename)
    base, _ = os.path.splitext(filename)
    labels_path = os.path.join(LABELS_DIR, base + ".txt")
    json_path = json_path_for_image(filename)

    removed = {"image": False, "labels": False, "json": False}

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

        status = "success"
        if not any(removed.values()):
            status = "not_found"
        elif not all(removed.values()):
            status = "partial"

        return jsonify({"status": status, "removed": removed})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500


@app.route("/download-dataset")
def download_dataset():
    """
    Create a zip on the fly containing:
      - image files from IMAGES_DIR -> images/...
      - txt label files from LABELS_DIR -> labels/...
      - json files from JSONS_DIR -> jsons/...
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

    memory_file.seek(0)

    return send_file(
        memory_file,
        mimetype="application/zip",
        as_attachment=True,
        download_name="agriapp_dataset.zip",
    )


@app.route("/download-dataset-selected", methods=["POST"])
def download_dataset_selected():
    """
    Create a zip on the fly containing only selected images and their related
    labels/json files.
    Expects JSON body:
      { "filenames": ["img_001.jpg", ...] }
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
    written = {"images": 0, "labels": 0, "jsons": 0}

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
def download_image_with_labels():
    """
    Download a zip containing one image and its related label/json files.

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

    memory_file = io.BytesIO()
    with zipfile.ZipFile(
        memory_file, mode="w", compression=zipfile.ZIP_DEFLATED
    ) as zf:
        zf.write(image_path, os.path.join("images", filename))
        if os.path.exists(label_path):
            zf.write(label_path, os.path.join("labels", stem + ".txt"))
        if os.path.exists(json_path):
            zf.write(json_path, os.path.join("jsons", stem + ".json"))

    memory_file.seek(0)
    return send_file(
        memory_file,
        mimetype="application/zip",
        as_attachment=True,
        download_name=f"{stem}_labels_bundle.zip",
    )


if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=False, use_reloader=False)
