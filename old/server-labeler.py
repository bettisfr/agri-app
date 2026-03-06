from flask import Flask, request, render_template, jsonify
import os
import json

app = Flask(__name__)

# ----------------------------------------------------------------------
# PATHS
# ----------------------------------------------------------------------
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
UPLOAD_DIR = os.path.join(STATIC_DIR, "uploads")

IMAGES_DIR = os.path.join(UPLOAD_DIR, "images")   # image files
LABELS_DIR = os.path.join(UPLOAD_DIR, "labels")   # YOLO txt
JSONS_DIR = os.path.join(UPLOAD_DIR, "jsons")     # per-image json

os.makedirs(IMAGES_DIR, exist_ok=True)
os.makedirs(LABELS_DIR, exist_ok=True)
os.makedirs(JSONS_DIR, exist_ok=True)


# ----------------------------------------------------------------------
# HELPERS
# ----------------------------------------------------------------------
def json_path(image_name: str) -> str:
    """
    Path of the per-image JSON file for this image.
    E.g. azz2.jpg -> jsons/azz2.json
    """
    base, _ = os.path.splitext(image_name)
    return os.path.join(JSONS_DIR, base + ".json")


def yolo_txt_path(image_name: str) -> str:
    """
    Path of the YOLO txt file for this image.
    E.g. azz2.jpg -> labels/azz2.txt
    """
    base, _ = os.path.splitext(image_name)
    return os.path.join(LABELS_DIR, base + ".txt")


def load_labels_from_json(image_name: str):
    """
    Load per-image labels from jsons/<stem>.json.

    Returns:
        list of label dicts or None if file missing/invalid.
    """
    jpath = json_path(image_name)
    if not os.path.exists(jpath) or os.path.getsize(jpath) == 0:
        return None

    try:
        with open(jpath, "r") as f:
            data = json.load(f)
        # We expect a list of dicts
        if isinstance(data, list):
            return data
        return None
    except Exception:
        return None


def save_labels_to_json(image_name: str, labels_list):
    """
    Save per-image labels to jsons/<stem>.json.
    """
    jpath = json_path(image_name)
    with open(jpath, "w") as f:
        json.dump(labels_list, f, indent=2)


# ----------------------------------------------------------------------
# ROUTES
# ----------------------------------------------------------------------
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
    1) If per-image jsons/<stem>.json exists → use that (authoritative).
    2) Else, if YOLO txt exists → load those as is_tp = True.
    """
    image_name = request.args.get("image")
    if not image_name:
        return jsonify({"status": "error", "message": "Missing 'image' parameter"}), 400

    labels_out = []

    # --- 1) From per-image JSON (authoritative) ---
    entry = load_labels_from_json(image_name)
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

            # default: if is_tp missing, assume True
            is_tp = bool(l.get("is_tp", True))

            labels_out.append({
                "cls": cls,
                "x_center": xc,
                "y_center": yc,
                "width": w,
                "height": h,
                "is_tp": is_tp,
            })

    # --- 2) Fallback: from YOLO txt (TP-only) ---
    else:
        txt_path = yolo_txt_path(image_name)
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

                        labels_out.append({
                            "cls": cls,
                            "x_center": xc,
                            "y_center": yc,
                            "width": w,
                            "height": h,
                            "is_tp": True,  # everything from txt is TP
                        })
            except Exception as e:
                return jsonify({
                    "status": "error",
                    "message": f"Error reading YOLO txt: {e}"
                }), 500

    return jsonify({
        "status": "success",
        "image": image_name,
        "labels": labels_out
    })


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

    txt_path = yolo_txt_path(image_name)

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

        # read is_tp from client (default True if missing)
        is_tp = l.get("is_tp")
        if is_tp is None:
            is_tp = True
        is_tp = bool(is_tp)

        status_entry.append({
            "cls": cls,
            "x_center": xc,
            "y_center": yc,
            "width": w,
            "height": h,
            "is_tp": is_tp,
        })

        if is_tp:
            yolo_lines.append(f"{cls} {xc:.6f} {yc:.6f} {w:.6f} {h:.6f}")

    # --- write YOLO txt (TP-only) ---
    try:
        with open(txt_path, "w") as f:
            if yolo_lines:
                f.write("\n".join(yolo_lines) + "\n")
            else:
                f.write("")  # empty file if no TP
    except Exception as e:
        return jsonify({
            "status": "error",
            "message": f"Failed to write label txt: {e}"
        }), 500

    # --- write per-image JSON ---
    try:
        save_labels_to_json(image_name, status_entry)
    except Exception as e:
        return jsonify({
            "status": "error",
            "message": f"Failed to write JSON: {e}"
        }), 500

    kept = sum(1 for s in status_entry if s.get("is_tp", True))
    total = len(status_entry)

    return jsonify({
        "status": "success",
        "message": f"Saved {kept} TP labels (out of {total} total boxes) for {image_name}."
    })


if __name__ == "__main__":
    # separate from main gallery server
    app.run(host="0.0.0.0", port=5001, debug=True)
