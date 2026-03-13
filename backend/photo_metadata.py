import json
import os
from typing import Any


DEFAULT_METADATA = {
    "temperature": None,
    "pressure": None,
    "humidity": None,
    "latitude": None,
    "longitude": None,
    "user_comment": "",
}


def metadata_dir_for_image_path(image_path: str) -> str:
    images_dir = os.path.dirname(image_path)
    uploads_dir = os.path.dirname(images_dir)
    return os.path.join(uploads_dir, "metadata")


def metadata_path_for_image_path(image_path: str) -> str:
    metadata_dir = metadata_dir_for_image_path(image_path)
    base, _ = os.path.splitext(os.path.basename(image_path))
    return os.path.join(metadata_dir, f"{base}.json")


def ensure_metadata_dir_for_image(image_path: str) -> None:
    os.makedirs(metadata_dir_for_image_path(image_path), exist_ok=True)


def normalize_metadata(raw: Any) -> dict[str, Any]:
    data = dict(DEFAULT_METADATA)
    if not isinstance(raw, dict):
        return data

    for key in DEFAULT_METADATA:
        if key in raw:
            data[key] = raw[key]

    if data["latitude"] is not None:
        try:
            data["latitude"] = float(data["latitude"])
        except Exception:
            data["latitude"] = None
    if data["longitude"] is not None:
        try:
            data["longitude"] = float(data["longitude"])
        except Exception:
            data["longitude"] = None
    if data["user_comment"] is None:
        data["user_comment"] = ""
    return data


def load_metadata_for_image_path(image_path: str) -> dict[str, Any]:
    path = metadata_path_for_image_path(image_path)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return dict(DEFAULT_METADATA)
    try:
        with open(path, "r") as f:
            raw = json.load(f)
        return normalize_metadata(raw)
    except Exception:
        return dict(DEFAULT_METADATA)


def save_metadata_for_image_path(image_path: str, metadata: dict[str, Any]) -> None:
    ensure_metadata_dir_for_image(image_path)
    path = metadata_path_for_image_path(image_path)
    data = normalize_metadata(metadata)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
