#!/usr/bin/env python3
"""
Experimental segmentation helper for AgriApp Studio.

Backends:
- seggpt (preferred when torch+transformers are available)
- threshold (always available fallback)

Outputs:
- binary mask image at --mask-out
- coarse bounding boxes (normalized YOLO format fields)
- one JSON line to stdout
"""

from __future__ import annotations

import argparse
import json
import os
import traceback
from collections import deque

import numpy as np
from PIL import Image


def _otsu_threshold(gray: np.ndarray) -> int:
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = gray.size
    sum_total = np.dot(np.arange(256), hist)

    sum_bg = 0.0
    w_bg = 0.0
    best_var = -1.0
    best_t = 127

    for t in range(256):
        w_bg += hist[t]
        if w_bg <= 0:
            continue
        w_fg = total - w_bg
        if w_fg <= 0:
            break
        sum_bg += t * hist[t]
        m_bg = sum_bg / w_bg
        m_fg = (sum_total - sum_bg) / w_fg
        var_between = w_bg * w_fg * (m_bg - m_fg) ** 2
        if var_between > best_var:
            best_var = var_between
            best_t = t
    return int(best_t)


def _connected_boxes(mask: np.ndarray, min_area: int = 900, max_boxes: int = 6) -> list[tuple[int, int, int, int]]:
    h, w = mask.shape
    visited = np.zeros((h, w), dtype=np.uint8)
    boxes: list[tuple[int, int, int, int, int]] = []
    # 4-neighborhood
    neigh = ((1, 0), (-1, 0), (0, 1), (0, -1))

    for y in range(h):
        for x in range(w):
            if mask[y, x] == 0 or visited[y, x]:
                continue
            q = deque([(x, y)])
            visited[y, x] = 1
            x0 = x1 = x
            y0 = y1 = y
            area = 0

            while q:
                cx, cy = q.popleft()
                area += 1
                if cx < x0:
                    x0 = cx
                if cx > x1:
                    x1 = cx
                if cy < y0:
                    y0 = cy
                if cy > y1:
                    y1 = cy
                for dx, dy in neigh:
                    nx, ny = cx + dx, cy + dy
                    if nx < 0 or ny < 0 or nx >= w or ny >= h:
                        continue
                    if visited[ny, nx] or mask[ny, nx] == 0:
                        continue
                    visited[ny, nx] = 1
                    q.append((nx, ny))

            if area >= min_area:
                boxes.append((x0, y0, x1, y1, area))

    boxes.sort(key=lambda b: b[4], reverse=True)
    return [(b[0], b[1], b[2], b[3]) for b in boxes[:max_boxes]]


def _to_yolo_boxes(boxes_px: list[tuple[int, int, int, int]], width: int, height: int) -> list[dict]:
    out = []
    for x0, y0, x1, y1 in boxes_px:
        bw = max(1, (x1 - x0 + 1))
        bh = max(1, (y1 - y0 + 1))
        xc = x0 + bw / 2.0
        yc = y0 + bh / 2.0
        out.append(
            {
                "cls": 0,
                "x_center": float(np.clip(xc / width, 0.0, 1.0)),
                "y_center": float(np.clip(yc / height, 0.0, 1.0)),
                "width": float(np.clip(bw / width, 0.0, 1.0)),
                "height": float(np.clip(bh / height, 0.0, 1.0)),
            }
        )
    return out


def run_threshold_backend(image_path: str, mask_out: str) -> dict:
    img = Image.open(image_path).convert("RGB")
    width, height = img.size
    gray = np.array(img.convert("L"), dtype=np.uint8)
    threshold = _otsu_threshold(gray)

    # Foreground candidate: darker-than-threshold regions
    mask = (gray < threshold).astype(np.uint8)
    boxes_px = _connected_boxes(mask, min_area=max(300, (width * height) // 2500), max_boxes=6)

    os.makedirs(os.path.dirname(mask_out), exist_ok=True)
    mask_img = (mask * 255).astype(np.uint8)
    Image.fromarray(mask_img).save(mask_out)

    return {
        "status": "success",
        "backend": "threshold",
        "image_width": width,
        "image_height": height,
        "boxes": _to_yolo_boxes(boxes_px, width, height),
        "mask_out": mask_out,
        "threshold": threshold,
    }


def _import_seggpt():
    # Lazy import so threshold backend still works without heavy deps.
    import torch  # type: ignore
    from transformers import SegGptForImageSegmentation, SegGptImageProcessor  # type: ignore

    return torch, SegGptForImageSegmentation, SegGptImageProcessor


def run_seggpt_backend(
    image_path: str,
    mask_out: str,
    checkpoint: str = "BAAI/seggpt-vit-large",
    device_hint: str = "auto",
    allow_download: bool = False,
    prompt_mask_path: str | None = None,
) -> dict:
    torch, SegGptForImageSegmentation, SegGptImageProcessor = _import_seggpt()

    # Prompt mask:
    # - use user-provided mask when available
    # - fallback to threshold-generated mask
    if prompt_mask_path and os.path.exists(prompt_mask_path):
        local_prompt_mask_path = prompt_mask_path
    else:
        base = run_threshold_backend(image_path=image_path, mask_out=mask_out + ".prompt.png")
        local_prompt_mask_path = base["mask_out"]

    image_input = Image.open(image_path).convert("RGB")
    mask_prompt = Image.open(local_prompt_mask_path).convert("L")
    if mask_prompt.size != image_input.size:
        mask_prompt = mask_prompt.resize(image_input.size)
    mask_prompt_np = (np.array(mask_prompt, dtype=np.uint8) > 20).astype(np.uint8)
    if mask_prompt_np.sum() < 12:
        raise RuntimeError("Prompt mask is empty; paint a larger area before segmentation.")

    # Use support-image style prompt from the same frame (official SegGPT pattern).
    image_np = np.array(image_input, dtype=np.uint8)
    prompt_image_np = image_np.copy()
    prompt_image_np[mask_prompt_np == 0] = 0
    image_prompt = Image.fromarray(prompt_image_np)
    width, height = image_input.size

    if device_hint == "cpu":
        device = torch.device("cpu")
    elif device_hint == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA requested but not available")
        device = torch.device("cuda")
    else:
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    if not allow_download:
        # Offline-first: use only local cache; fail fast otherwise.
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        image_processor = SegGptImageProcessor.from_pretrained(checkpoint, local_files_only=True)
        model = SegGptForImageSegmentation.from_pretrained(checkpoint, local_files_only=True).to(device)
    else:
        image_processor = SegGptImageProcessor.from_pretrained(checkpoint)
        model = SegGptForImageSegmentation.from_pretrained(checkpoint).to(device)
    model.eval()

    # Official HF SegGPT flow for this transformers version.
    inputs = image_processor(
        images=[image_input],
        prompt_images=[image_prompt],
        prompt_masks=[mask_prompt],
        return_tensors="pt",
    )
    inputs = {k: v.to(device) for k, v in inputs.items()}

    with torch.no_grad():
        outputs = model(**inputs)

    result = image_processor.post_process_semantic_segmentation(
        outputs,
        target_sizes=[(height, width)],
    )[0]
    # Keep any non-zero label as foreground.
    fg_mask = (result.detach().cpu().numpy() > 0).astype(np.uint8)

    os.makedirs(os.path.dirname(mask_out), exist_ok=True)
    Image.fromarray((fg_mask * 255).astype(np.uint8)).save(mask_out)

    # Be permissive to keep multiple matched instances.
    min_area = max(40, (width * height) // 80000)
    boxes_px = _connected_boxes(fg_mask, min_area=min_area, max_boxes=60)

    return {
        "status": "success",
        "backend": "seggpt",
        "checkpoint": checkpoint,
        "device": str(device),
        "image_width": width,
        "image_height": height,
        "boxes": _to_yolo_boxes(boxes_px, width, height),
        "mask_out": mask_out,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AgriApp experimental SegGPT helper")
    parser.add_argument("--image", required=True, help="Input image path")
    parser.add_argument("--mask-out", required=True, help="Output mask PNG path")
    parser.add_argument("--backend", default="auto", choices=("auto", "seggpt", "threshold"), help="Segmentation backend")
    parser.add_argument("--checkpoint", default=os.environ.get("SEGGPT_CHECKPOINT", "BAAI/seggpt-vit-large"), help="HF checkpoint")
    parser.add_argument("--device", default=os.environ.get("SEGGPT_DEVICE", "auto"), choices=("auto", "cuda", "cpu"), help="Torch device")
    parser.add_argument("--allow-download", action="store_true", help="Allow downloading model/checkpoint if not cached")
    parser.add_argument("--prompt-mask", default="", help="Optional prompt mask PNG path")
    args = parser.parse_args()

    image_path = os.path.abspath(args.image)
    mask_out = os.path.abspath(args.mask_out)

    if not os.path.exists(image_path):
        print(json.dumps({"status": "error", "message": f"image not found: {image_path}"}))
        return 2

    try:
        payload = None
        seggpt_error = None

        if args.backend in ("auto", "seggpt"):
            try:
                payload = run_seggpt_backend(
                    image_path=image_path,
                    mask_out=mask_out,
                    checkpoint=args.checkpoint,
                    device_hint=args.device,
                    allow_download=args.allow_download,
                    prompt_mask_path=(args.prompt_mask.strip() or None),
                )
            except Exception as e:
                seggpt_error = f"{type(e).__name__}: {e}"
                if args.backend == "seggpt":
                    raise

        if payload is None:
            # Threshold fallback can still use the user mask if provided.
            if args.prompt_mask and os.path.exists(args.prompt_mask):
                img = Image.open(image_path).convert("RGB")
                width, height = img.size
                pm = Image.open(args.prompt_mask).convert("L")
                if pm.size != (width, height):
                    pm = pm.resize((width, height))
                arr = (np.array(pm, dtype=np.uint8) > 20).astype(np.uint8)
                os.makedirs(os.path.dirname(mask_out), exist_ok=True)
                Image.fromarray((arr * 255).astype(np.uint8)).save(mask_out)
                boxes_px = _connected_boxes(arr, min_area=max(300, (width * height) // 2500), max_boxes=8)
                payload = {
                    "status": "success",
                    "backend": "threshold-prompt",
                    "image_width": width,
                    "image_height": height,
                    "boxes": _to_yolo_boxes(boxes_px, width, height),
                    "mask_out": mask_out,
                }
            else:
                payload = run_threshold_backend(image_path=image_path, mask_out=mask_out)
            if seggpt_error:
                payload["fallback_reason"] = seggpt_error

        print(json.dumps(payload))
        return 0
    except Exception as e:
        print(
            json.dumps(
                {
                    "status": "error",
                    "message": str(e),
                    "trace": traceback.format_exc(limit=2),
                }
            )
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
