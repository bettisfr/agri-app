#!/usr/bin/env python3
"""
SegGPT cross-device benchmark: Studio (local) vs Raspberry Pi (remote).

For each image:
- run tools/seggpt_infer.py locally
- run tools/seggpt_infer.py remotely via SSH
- optionally download remote mask
- compare masks and boxes
- append one CSV row

Outputs:
- summary CSV
- per-image JSON payloads (local + remote)
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shlex
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Compare SegGPT outputs between local Studio and remote RPi.")
    p.add_argument(
        "--images",
        nargs="+",
        required=True,
        help="Image filenames (basename) to test, e.g. rpi_20260408-152834.jpg",
    )
    p.add_argument(
        "--prompt-mask",
        default="static/uploads/masks/_last_prompt.png",
        help="Local prompt mask path (default: static/uploads/masks/_last_prompt.png)",
    )
    p.add_argument(
        "--local-images-dir",
        default="static/uploads/images",
        help="Local images directory (default: static/uploads/images)",
    )
    p.add_argument(
        "--remote-images-dir",
        default="/home/fra/agri-app/static/uploads/images",
        help="Remote images directory (default: /home/fra/agri-app/static/uploads/images)",
    )
    p.add_argument(
        "--remote-prompt-mask",
        default="/home/fra/agri-app/static/uploads/masks/_last_prompt.png",
        help="Remote prompt mask path (default: /home/fra/agri-app/static/uploads/masks/_last_prompt.png)",
    )
    p.add_argument("--host", default="192.168.1.67", help="RPi host (default: 192.168.1.67)")
    p.add_argument("--user", default="fra", help="RPi user (default: fra)")
    p.add_argument(
        "--remote-python",
        default="/home/fra/pyenv/bin/python",
        help="Remote python executable (default: /home/fra/pyenv/bin/python)",
    )
    p.add_argument(
        "--local-python",
        default="/home/fra/pyvenv/bin/python3",
        help="Local python executable (default: /home/fra/pyvenv/bin/python3)",
    )
    p.add_argument(
        "--out-dir",
        default="benchmarks/seggpt_compare",
        help="Base output directory (default: benchmarks/seggpt_compare)",
    )
    p.add_argument(
        "--device",
        default="cpu",
        choices=("cpu", "cuda", "auto"),
        help="Device hint passed to seggpt_infer.py (default: cpu)",
    )
    p.add_argument(
        "--backend",
        default="seggpt",
        choices=("seggpt", "auto", "threshold"),
        help="Backend passed to seggpt_infer.py (default: seggpt)",
    )
    p.add_argument(
        "--timeout-local",
        type=int,
        default=300,
        help="Timeout seconds for local run (default: 300)",
    )
    p.add_argument(
        "--timeout-remote",
        type=int,
        default=420,
        help="Timeout seconds for remote run (default: 420)",
    )
    p.add_argument(
        "--download-remote-mask",
        action="store_true",
        help="Download remote mask and compute exact mask MD5 comparison.",
    )
    return p.parse_args()


def _md5(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _parse_last_json_line(text: str) -> dict[str, Any]:
    for line in reversed(text.splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            if isinstance(obj, dict):
                return obj
        except Exception:
            continue
    return {}


def _boxes_signature(payload: dict[str, Any]) -> list[tuple[float, float, float, float]]:
    out: list[tuple[float, float, float, float]] = []
    for b in payload.get("boxes", []) or []:
        try:
            out.append(
                (
                    round(float(b["x_center"]), 6),
                    round(float(b["y_center"]), 6),
                    round(float(b["width"]), 6),
                    round(float(b["height"]), 6),
                )
            )
        except Exception:
            continue
    return out


def _run_local(
    local_python: str,
    image_path: Path,
    prompt_mask: Path,
    out_mask: Path,
    backend: str,
    device: str,
    timeout_s: int,
) -> tuple[int, str, str, float]:
    cmd = [
        local_python,
        "tools/seggpt_infer.py",
        "--image",
        str(image_path),
        "--mask-out",
        str(out_mask),
        "--backend",
        backend,
        "--device",
        device,
        "--prompt-mask",
        str(prompt_mask),
    ]
    t0 = time.perf_counter()
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_s, check=False)
    elapsed = time.perf_counter() - t0
    return proc.returncode, proc.stdout or "", proc.stderr or "", elapsed


def _run_remote(
    user: str,
    host: str,
    remote_python: str,
    remote_image: str,
    remote_prompt: str,
    remote_mask_out: str,
    backend: str,
    device: str,
    timeout_s: int,
) -> tuple[int, str, str, float]:
    remote_cmd = (
        f"{shlex.quote(remote_python)} ~/agri-app/tools/seggpt_infer.py "
        f"--image {shlex.quote(remote_image)} "
        f"--mask-out {shlex.quote(remote_mask_out)} "
        f"--backend {shlex.quote(backend)} "
        f"--device {shlex.quote(device)} "
        f"--prompt-mask {shlex.quote(remote_prompt)}"
    )
    ssh_cmd = ["ssh", f"{user}@{host}", remote_cmd]
    t0 = time.perf_counter()
    proc = subprocess.run(ssh_cmd, capture_output=True, text=True, timeout=timeout_s, check=False)
    elapsed = time.perf_counter() - t0
    return proc.returncode, proc.stdout or "", proc.stderr or "", elapsed


def main() -> int:
    args = _parse_args()

    prompt_mask = Path(args.prompt_mask)
    if not prompt_mask.exists():
        print(f"[error] prompt mask not found: {prompt_mask}", file=sys.stderr)
        return 2

    out_root = Path(args.out_dir) / f"run_{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    local_masks_dir = out_root / "local_masks"
    remote_masks_dir = out_root / "remote_masks"
    json_dir = out_root / "json"
    out_root.mkdir(parents=True, exist_ok=True)
    local_masks_dir.mkdir(parents=True, exist_ok=True)
    remote_masks_dir.mkdir(parents=True, exist_ok=True)
    json_dir.mkdir(parents=True, exist_ok=True)

    csv_path = out_root / "summary.csv"
    fields = [
        "image",
        "local_status",
        "remote_status",
        "local_time_s",
        "remote_time_s",
        "local_backend",
        "remote_backend",
        "local_boxes_n",
        "remote_boxes_n",
        "boxes_same",
        "local_mask_md5",
        "remote_mask_md5",
        "mask_same",
        "local_error",
        "remote_error",
    ]

    rows: list[dict[str, Any]] = []

    for image_name in args.images:
        local_image = Path(args.local_images_dir) / image_name
        if not local_image.exists():
            rows.append(
                {
                    "image": image_name,
                    "local_status": "missing_image",
                    "remote_status": "skipped",
                    "local_time_s": 0,
                    "remote_time_s": 0,
                    "local_backend": "",
                    "remote_backend": "",
                    "local_boxes_n": 0,
                    "remote_boxes_n": 0,
                    "boxes_same": False,
                    "local_mask_md5": "",
                    "remote_mask_md5": "",
                    "mask_same": False,
                    "local_error": f"not found: {local_image}",
                    "remote_error": "",
                }
            )
            continue

        local_mask = local_masks_dir / f"{Path(image_name).stem}.png"
        remote_mask_tmp = f"/tmp/seggpt_cmp_{Path(image_name).stem}.png"

        l_rc, l_out, l_err, l_t = _run_local(
            local_python=args.local_python,
            image_path=local_image,
            prompt_mask=prompt_mask,
            out_mask=local_mask,
            backend=args.backend,
            device=args.device,
            timeout_s=args.timeout_local,
        )
        l_payload = _parse_last_json_line(l_out)
        (json_dir / f"local_{Path(image_name).stem}.json").write_text(
            json.dumps(l_payload or {"stdout": l_out, "stderr": l_err}, indent=2),
            encoding="utf-8",
        )

        remote_image = f"{args.remote_images_dir.rstrip('/')}/{image_name}"
        r_rc, r_out, r_err, r_t = _run_remote(
            user=args.user,
            host=args.host,
            remote_python=args.remote_python,
            remote_image=remote_image,
            remote_prompt=args.remote_prompt_mask,
            remote_mask_out=remote_mask_tmp,
            backend=args.backend,
            device=args.device,
            timeout_s=args.timeout_remote,
        )
        r_payload = _parse_last_json_line(r_out)
        (json_dir / f"remote_{Path(image_name).stem}.json").write_text(
            json.dumps(r_payload or {"stdout": r_out, "stderr": r_err}, indent=2),
            encoding="utf-8",
        )

        local_ok = (l_rc == 0 and l_payload.get("status") == "success")
        remote_ok = (r_rc == 0 and r_payload.get("status") == "success")

        local_boxes = _boxes_signature(l_payload) if local_ok else []
        remote_boxes = _boxes_signature(r_payload) if remote_ok else []
        boxes_same = bool(local_ok and remote_ok and local_boxes == remote_boxes)

        local_md5 = _md5(local_mask) if local_ok and local_mask.exists() else ""
        remote_md5 = ""
        mask_same = False

        if remote_ok and args.download_remote_mask:
            remote_mask_local = remote_masks_dir / f"{Path(image_name).stem}.png"
            scp = subprocess.run(
                ["scp", f"{args.user}@{args.host}:{remote_mask_tmp}", str(remote_mask_local)],
                capture_output=True,
                text=True,
                check=False,
            )
            if scp.returncode == 0 and remote_mask_local.exists():
                remote_md5 = _md5(remote_mask_local)
                mask_same = bool(local_md5 and remote_md5 and local_md5 == remote_md5)

        row = {
            "image": image_name,
            "local_status": l_payload.get("status", f"rc={l_rc}") if l_payload else f"rc={l_rc}",
            "remote_status": r_payload.get("status", f"rc={r_rc}") if r_payload else f"rc={r_rc}",
            "local_time_s": round(l_t, 3),
            "remote_time_s": round(r_t, 3),
            "local_backend": l_payload.get("backend", ""),
            "remote_backend": r_payload.get("backend", ""),
            "local_boxes_n": len(local_boxes),
            "remote_boxes_n": len(remote_boxes),
            "boxes_same": boxes_same,
            "local_mask_md5": local_md5,
            "remote_mask_md5": remote_md5,
            "mask_same": mask_same,
            "local_error": "" if local_ok else (l_payload.get("message") if l_payload else l_err.strip()),
            "remote_error": "" if remote_ok else (r_payload.get("message") if r_payload else r_err.strip()),
        }
        rows.append(row)
        print(
            f"[{image_name}] local={row['local_time_s']}s remote={row['remote_time_s']}s "
            f"boxes_same={boxes_same} mask_same={mask_same}"
        )

    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\n[done] CSV: {csv_path}")
    print(f"[done] JSON: {json_dir}")
    print(f"[done] Local masks: {local_masks_dir}")
    if args.download_remote_mask:
        print(f"[done] Remote masks: {remote_masks_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
