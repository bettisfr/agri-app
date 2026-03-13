#!/usr/bin/env python3
import argparse
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

JPEG_SOI = b"\xff\xd8"
JPEG_EOI = b"\xff\xd9"

FRAMESIZE_VALUES = {
    "qqvga": 1,   # 160x120
    "qcif": 3,    # 176x144
    "hqvga": 4,   # 240x176
    "qvga": 6,    # 320x240
    "cif": 8,     # 400x296
    "hvga": 9,    # 480x320
    "vga": 10,    # 640x480
    "svga": 11,   # 800x600
    "xga": 12,    # 1024x768
    "hd": 13,     # 1280x720
    "sxga": 14,   # 1280x1024
    "uxga": 15,   # 1600x1200
    "fhd": 16,    # 1920x1080
    "p_hd": 17,   # 720x1280
    "p_3mp": 18,  # 864x1536
    "qxga": 19,   # 2048x1536
    "qhd": 20,    # 2560x1440
    "wqxga": 21,  # 2560x1600
    "p_fhd": 22,  # 1080x1920
    "qsxga": 23,  # 2560x1920
    "5mp": 24,    # 2592x1944
}


def indexed_name(path, idx):
    if idx == 1:
        return path
    base, ext = os.path.splitext(path)
    if not ext:
        ext = ".jpg"
    return f"{base}_{idx:04d}{ext}"


def next_available_name(path):
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    if not ext:
        ext = ".jpg"
    i = 2
    while True:
        candidate = f"{base}_{i:04d}{ext}"
        if not os.path.exists(candidate):
            return candidate
        i += 1


def save_bytes(path, data):
    with open(path, "wb") as f:
        f.write(data)


def capture_from_serial(port, out, baud, timeout, no_reset):
    import serial

    ser = serial.Serial(port, baud, timeout=timeout)
    try:
        if not no_reset:
            # Trigger board reset on open for typical ESP32 USB serial behavior.
            ser.dtr = False
            time.sleep(0.1)
            ser.reset_input_buffer()
            ser.dtr = True

        size = None
        while True:
            line = ser.readline()
            if not line:
                print("Timeout waiting for header", file=sys.stderr)
                return 1

            txt = line.decode(errors="ignore").strip()
            print(txt)

            m = re.match(r"^BEGIN\s+(\d+)$", txt)
            if m:
                size = int(m.group(1))
                break

            if txt.startswith("CAM_INIT_FAIL") or txt.startswith("CAPTURE_FAIL"):
                return 2

        data = ser.read(size)
        if len(data) != size:
            print(f"Incomplete read: expected {size}, got {len(data)}", file=sys.stderr)
            return 3

        out_path = next_available_name(out)
        save_bytes(out_path, data)
        print(f"Saved {len(data)} bytes to {out_path}")
        return 0
    finally:
        ser.close()


def capture_one_http(url, timeout):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
    if not data.startswith(JPEG_SOI) or not data.endswith(JPEG_EOI):
        raise ValueError("Response is not a valid JPEG")
    return data


def set_framesize_http(capture_url, framesize, timeout):
    key = framesize.lower()
    if key not in FRAMESIZE_VALUES:
        valid = ", ".join(sorted(FRAMESIZE_VALUES.keys()))
        raise ValueError(f"Unknown framesize '{framesize}'. Valid: {valid}")

    parsed = urllib.parse.urlsplit(capture_url)
    control_path = "/control"
    query = urllib.parse.urlencode({"var": "framesize", "val": str(FRAMESIZE_VALUES[key])})
    control_url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, control_path, query, ""))
    req = urllib.request.Request(control_url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        _ = resp.read()
    print(f"Framesize set to {key} via {control_url}")


def set_control_http(capture_url, var_name, var_val, timeout):
    parsed = urllib.parse.urlsplit(capture_url)
    control_path = "/control"
    query = urllib.parse.urlencode({"var": var_name, "val": str(var_val)})
    control_url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, control_path, query, ""))
    req = urllib.request.Request(control_url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        _ = resp.read()
    print(f"Set {var_name}={var_val} via {control_url}")


def capture_from_http(url, out, timeout, count, interval, framesize, control_params):
    for item in control_params:
        if "=" not in item:
            print(f"Invalid --set value '{item}', expected var=val", file=sys.stderr)
            return 8
        var_name, var_val = item.split("=", 1)
        var_name = var_name.strip()
        var_val = var_val.strip()
        if not var_name:
            print(f"Invalid --set value '{item}', empty var name", file=sys.stderr)
            return 8
        try:
            set_control_http(url, var_name, var_val, timeout)
            time.sleep(0.1)
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            print(f"Failed to set {var_name}: {exc}", file=sys.stderr)
            return 7

    if framesize:
        try:
            set_framesize_http(url, framesize, timeout)
            time.sleep(0.2)
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            print(f"Failed to set framesize: {exc}", file=sys.stderr)
            return 7

    for idx in range(1, count + 1):
        try:
            data = capture_one_http(url, timeout)
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            print(f"HTTP capture failed: {exc}", file=sys.stderr)
            return 4

        out_path = next_available_name(indexed_name(out, idx))
        save_bytes(out_path, data)
        print(f"Saved {len(data)} bytes to {out_path}")

        if idx < count:
            time.sleep(interval)

    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Save one or more JPEG photos from ESP32 via serial protocol or HTTP /capture"
    )
    parser.add_argument("port", nargs="?", help="Serial port, e.g. /dev/ttyACM0")
    parser.add_argument("out", nargs="?", default="photo.jpg", help="Output JPEG path (serial mode)")
    parser.add_argument("--out", dest="out_file", help="Output JPEG path (HTTP mode)")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--no-reset", action="store_true", help="Do not toggle DTR on open")

    parser.add_argument("--url", help="HTTP snapshot URL, e.g. http://192.168.1.38/capture")
    parser.add_argument("--count", type=int, default=1, help="Number of photos to save (HTTP mode)")
    parser.add_argument("--interval", type=float, default=1.0, help="Seconds between photos (HTTP mode)")
    parser.add_argument("--framesize", help="HTTP framesize: qqvga,qvga,vga,svga,xga,sxga,uxga,...")
    parser.add_argument(
        "--set",
        action="append",
        default=[],
        metavar="VAR=VAL",
        help="Set camera control before capture (repeatable), e.g. --set quality=12",
    )

    args = parser.parse_args()

    if args.url:
        out_path = args.out_file or "photo.jpg"
        if not args.out_file and args.out != "photo.jpg":
            out_path = args.out
        # If only one positional argument is provided in HTTP mode,
        # argparse stores it into "port". Treat it as output path.
        if (
            not args.out_file
            and args.out == "photo.jpg"
            and args.port
            and not args.port.startswith("/dev/tty")
        ):
            out_path = args.port

        if args.count < 1:
            print("--count must be >= 1", file=sys.stderr)
            return 5
        return capture_from_http(
            args.url,
            out_path,
            args.timeout,
            args.count,
            args.interval,
            args.framesize,
            args.set,
        )

    if not args.port:
        print("Serial mode requires PORT, or pass --url for HTTP mode", file=sys.stderr)
        return 6

    return capture_from_serial(args.port, args.out, args.baud, args.timeout, args.no_reset)


if __name__ == "__main__":
    raise SystemExit(main())
