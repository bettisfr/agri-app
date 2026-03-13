import os
from typing import Any


def _parse_hex_or_int(raw: str, fallback: int) -> int:
    text = (raw or "").strip()
    if not text:
        return fallback
    try:
        if text.lower().startswith("0x"):
            return int(text, 16)
        return int(text)
    except Exception:
        return fallback


def _read_once(address: int) -> dict[str, Any] | None:
    try:
        import board
        import busio
        try:
            from adafruit_bme280 import basic as adafruit_bme280_basic
        except Exception:
            adafruit_bme280_basic = None
        import adafruit_bme280
    except Exception:
        return None

    try:
        i2c = busio.I2C(board.SCL, board.SDA)
        if adafruit_bme280_basic is not None:
            sensor = adafruit_bme280_basic.Adafruit_BME280_I2C(i2c, address=address)
        else:
            sensor = adafruit_bme280.Adafruit_BME280_I2C(i2c, address=address)
        return {
            "temperature": float(sensor.temperature),
            "humidity": float(sensor.humidity),
            "pressure": float(sensor.pressure),
            "bme_address": hex(address),
        }
    except Exception:
        return None


def get_bme280_reading(addresses: list[int] | None = None) -> dict[str, Any] | None:
    """
    Best-effort BME280 reading.
    Env overrides:
      - AGRIAPP_BME280_ENABLED (default: 1)
      - AGRIAPP_BME280_ADDR (default: 0x76)
      - AGRIAPP_BME280_ADDRS (comma-separated, e.g. 0x76,0x77)
    """
    enabled = (os.getenv("AGRIAPP_BME280_ENABLED", "1") or "1").strip().lower()
    if enabled in ("0", "false", "no", "off"):
        return None

    if addresses is None:
        env_addrs = (os.getenv("AGRIAPP_BME280_ADDRS", "") or "").strip()
        if env_addrs:
            parsed = []
            for token in env_addrs.split(","):
                token = token.strip()
                if not token:
                    continue
                parsed.append(_parse_hex_or_int(token, 0x76))
            addresses = parsed if parsed else [0x76, 0x77]
        else:
            default_addr = _parse_hex_or_int(os.getenv("AGRIAPP_BME280_ADDR", "0x76"), 0x76)
            addresses = [default_addr, 0x77 if default_addr != 0x77 else 0x76]

    for addr in addresses:
        data = _read_once(addr)
        if data:
            return data
    return None
