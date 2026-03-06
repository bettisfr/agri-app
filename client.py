#!/usr/bin/env python3
import argparse
import logging
import os
import subprocess
import time
from datetime import datetime
from shutil import which


IMAGE_DIR = "static/uploads/images"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Camera capture client for Raspberry Pi (camera only)."
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=30,
        help="Seconds between captures in continuous mode (default: 30).",
    )
    parser.add_argument(
        "--warmup-ms",
        type=int,
        default=500,
        help="Camera warmup in milliseconds passed to libcamera-still (default: 500).",
    )
    parser.add_argument(
        "--timeout-ms",
        type=int,
        default=1000,
        help="Capture timeout in milliseconds passed to libcamera-still (default: 1000).",
    )
    parser.add_argument(
        "--oneshot",
        action="store_true",
        help="Capture one image and exit.",
    )
    return parser


def capture_photo(warmup_ms: int, timeout_ms: int) -> str | None:
    os.makedirs(IMAGE_DIR, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    file_path = os.path.join(IMAGE_DIR, f"img_{timestamp}.jpg")

    camera_cmd = which("rpicam-still") or which("libcamera-still")
    if not camera_cmd:
        logging.error(
            "No camera CLI found. Install rpicam-still or libcamera-still on Raspberry Pi."
        )
        return None

    cmd = [
        camera_cmd,
        "-n",
        "--autofocus-mode",
        "continuous",
        "-t",
        str(timeout_ms + warmup_ms),
        "-o",
        file_path,
    ]

    logging.info("Capturing image: %s", file_path)
    try:
        subprocess.run(cmd, check=True)
        logging.info("Saved image: %s", file_path)
        return file_path
    except subprocess.CalledProcessError as exc:
        logging.error("Capture failed: %s", exc)
        return None


def main() -> int:
    args = build_parser().parse_args()
    logging.basicConfig(
        format="%(asctime)s [%(levelname)s] %(message)s",
        level=logging.INFO,
    )

    if args.oneshot:
        return 0 if capture_photo(args.warmup_ms, args.timeout_ms) else 1

    if args.interval < 1:
        logging.error("--interval must be >= 1")
        return 1

    logging.info(
        "Starting continuous capture. interval=%ss warmup=%sms timeout=%sms",
        args.interval,
        args.warmup_ms,
        args.timeout_ms,
    )

    while True:
        capture_photo(args.warmup_ms, args.timeout_ms)
        time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())
