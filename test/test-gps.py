#!/usr/bin/env python3
"""
Simple GPS tester for Raspberry Pi.

Reads NMEA sentences from a serial GPS module and prints latitude/longitude
when a valid fix is found (GPGGA/GNGGA).
"""

import argparse
import sys
import time


def parse_coordinates(coord: str, direction: str):
    if not coord or not direction:
        return None
    try:
        degrees_length = 2 if direction in ("N", "S") else 3
        degrees = int(coord[:degrees_length])
        minutes = float(coord[degrees_length:])
        decimal = degrees + (minutes / 60.0)
        if direction in ("S", "W"):
            decimal = -decimal
        return decimal
    except Exception:
        return None


def parse_gga(sentence: str):
    # Example:
    # $GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47
    parts = sentence.split(",")
    if len(parts) < 7:
        return None, None
    # Fix quality: 0 = invalid
    if parts[6] == "0":
        return None, None
    lat = parse_coordinates(parts[2], parts[3])
    lon = parse_coordinates(parts[4], parts[5])
    return lat, lon


def build_parser():
    p = argparse.ArgumentParser(description="GPS serial test utility")
    p.add_argument("--port", default="/dev/serial0", help="Serial port (default: /dev/serial0)")
    p.add_argument("--baud", type=int, default=9600, help="Baud rate (default: 9600)")
    p.add_argument("--timeout", type=float, default=1.0, help="Serial timeout in seconds (default: 1.0)")
    p.add_argument("--max-seconds", type=int, default=30, help="Stop after N seconds if no fix (default: 30)")
    p.add_argument("--watch", action="store_true", help="Keep printing fixes continuously")
    p.add_argument("--raw", action="store_true", help="Print raw GGA sentences")
    return p


def main():
    args = build_parser().parse_args()
    try:
        import serial  # pyserial
    except Exception:
        print("pyserial not installed. Run: pip install pyserial", file=sys.stderr)
        return 1

    try:
        ser = serial.Serial(args.port, args.baud, timeout=args.timeout)
    except Exception as e:
        print(f"Failed to open GPS serial on {args.port}: {e}", file=sys.stderr)
        return 2

    print(f"GPS test started on {args.port} @ {args.baud} baud")
    start = time.time()
    got_fix = False

    try:
        while True:
            try:
                line = ser.readline().decode("ascii", errors="ignore").strip()
            except Exception as e:
                # Intermittent UART glitch: keep running and retry.
                print(f"[warn] serial glitch: {e}", file=sys.stderr)
                time.sleep(0.2)
                continue
            if not line:
                if not args.watch and (time.time() - start) > args.max_seconds:
                    print("No fix within timeout window.")
                    return 3
                continue

            if not (line.startswith("$GPGGA") or line.startswith("$GNGGA")):
                continue

            if args.raw:
                print(f"RAW: {line}")

            lat, lon = parse_gga(line)
            if lat is None or lon is None:
                continue

            got_fix = True
            print(f"GPS FIX -> latitude={lat:.7f}, longitude={lon:.7f}")

            if not args.watch:
                return 0
    except KeyboardInterrupt:
        print("\nInterrupted by user.")
        return 0 if got_fix else 4
    finally:
        try:
            ser.close()
        except Exception:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
