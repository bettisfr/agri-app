#!/usr/bin/env python3
"""
Simple BME280 tester for Raspberry Pi (I2C).
"""

import argparse
import sys
import time


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="BME280 I2C test utility")
    p.add_argument(
        "--address",
        default="0x76",
        help="I2C address in hex (default: 0x76)",
    )
    p.add_argument(
        "--interval",
        type=float,
        default=2.0,
        help="Seconds between reads in watch mode (default: 2.0)",
    )
    p.add_argument(
        "--watch",
        action="store_true",
        help="Continuously print measurements",
    )
    return p


def parse_address(raw: str) -> int:
    try:
        return int(raw, 16) if raw.lower().startswith("0x") else int(raw)
    except Exception as e:
        raise ValueError(f"Invalid address '{raw}': {e}") from e


def read_once(sensor):
    temp = float(sensor.temperature)
    hum = float(sensor.humidity)
    press = float(sensor.pressure)
    print(f"T: {temp:.2f} C | H: {hum:.2f} % | P: {press:.2f} hPa")


def main() -> int:
    args = build_parser().parse_args()
    try:
        address = parse_address(args.address)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 2

    try:
        import board
        import busio
        try:
            from adafruit_bme280 import basic as adafruit_bme280_basic
        except Exception:
            adafruit_bme280_basic = None
        import adafruit_bme280
    except Exception:
        print(
            "Missing dependency. Install with:\n"
            "  pip install adafruit-circuitpython-bme280",
            file=sys.stderr,
        )
        return 1

    try:
        i2c = busio.I2C(board.SCL, board.SDA)
        if adafruit_bme280_basic is not None:
            sensor = adafruit_bme280_basic.Adafruit_BME280_I2C(i2c, address=address)
        else:
            sensor = adafruit_bme280.Adafruit_BME280_I2C(i2c, address=address)
    except Exception as e:
        print(f"Failed to initialize BME280 at {hex(address)}: {e}", file=sys.stderr)
        return 3

    print(f"BME280 test started on I2C address {hex(address)}")
    try:
        if not args.watch:
            read_once(sensor)
            return 0

        while True:
            read_once(sensor)
            time.sleep(max(0.2, args.interval))
    except KeyboardInterrupt:
        print("\nInterrupted by user.")
        return 0
    except Exception as e:
        print(f"Read failed: {e}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    raise SystemExit(main())
