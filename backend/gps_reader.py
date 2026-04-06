import os
import threading
import time
from typing import Any

_GPS_LOCK = threading.Lock()
_GPS_CACHE_KEY: tuple[tuple[str, ...], int] | None = None
_GPS_CACHE_TS: float = 0.0
_GPS_CACHE_FIX: dict[str, Any] | None = None


def _parse_latlon(parts: list[str]) -> tuple[float | None, float | None]:
    try:
        lat_raw = parts[2]
        lat_ref = parts[3]
        lon_raw = parts[4]
        lon_ref = parts[5]
    except Exception:
        return None, None

    if not lat_raw or not lon_raw or not lat_ref or not lon_ref:
        return None, None

    try:
        lat_deg = float(lat_raw[:2])
        lat_min = float(lat_raw[2:])
        lon_deg = float(lon_raw[:3])
        lon_min = float(lon_raw[3:])
    except Exception:
        return None, None

    lat = lat_deg + lat_min / 60.0
    lon = lon_deg + lon_min / 60.0
    if lat_ref == "S":
        lat = -lat
    if lon_ref == "W":
        lon = -lon
    return lat, lon


def _parse_gga(sentence: str) -> tuple[float | None, float | None]:
    if not sentence.startswith("$GPGGA") and not sentence.startswith("$GNGGA"):
        return None, None
    parts = sentence.split(",")
    if len(parts) < 7:
        return None, None
    if parts[6] == "0":
        return None, None
    return _parse_latlon(parts)


def _sample_port(port: str, baud: int, max_seconds: float) -> dict[str, Any] | None:
    try:
        import serial
    except Exception:
        return None

    deadline = time.time() + max_seconds
    try:
        with serial.Serial(port, baudrate=baud, timeout=1.0) as ser:
            while time.time() < deadline:
                raw = ser.readline()
                if not raw:
                    continue
                try:
                    sentence = raw.decode("ascii", errors="ignore").strip()
                except Exception:
                    continue
                lat, lon = _parse_gga(sentence)
                if lat is None or lon is None:
                    continue
                return {
                    "latitude": lat,
                    "longitude": lon,
                    "gps_port": port,
                    "gps_baud": baud,
                    "gps_sentence": sentence,
                }
    except Exception:
        return None

    return None


def get_gps_fix(
    ports: list[str] | None = None,
    baud: int | None = None,
    max_seconds: float | None = None,
) -> dict[str, Any] | None:
    """
    Best-effort GPS fix for capture geotagging.
    Uses env overrides:
      - AGRIAPP_GPS_PORTS (comma separated)
      - AGRIAPP_GPS_BAUD
      - AGRIAPP_GPS_TIMEOUT
      - AGRIAPP_GPS_CACHE_TTL
    """
    global _GPS_CACHE_KEY
    global _GPS_CACHE_TS
    global _GPS_CACHE_FIX

    env_ports = os.getenv("AGRIAPP_GPS_PORTS", "")
    if ports is None:
        if env_ports.strip():
            ports = [p.strip() for p in env_ports.split(",") if p.strip()]
        else:
            # Prefer GPIO UART first when available (Raspberry Pi wiring setup).
            ports = ["/dev/serial0", "/dev/ttyACM0", "/dev/ttyUSB0"]
    if baud is None:
        try:
            baud = int(os.getenv("AGRIAPP_GPS_BAUD", "9600"))
        except Exception:
            baud = 9600
    if max_seconds is None:
        try:
            max_seconds = float(os.getenv("AGRIAPP_GPS_TIMEOUT", "4"))
        except Exception:
            max_seconds = 4.0
    try:
        cache_ttl = float(os.getenv("AGRIAPP_GPS_CACHE_TTL", "8"))
    except Exception:
        cache_ttl = 8.0
    if cache_ttl < 0:
        cache_ttl = 0.0

    cache_key = (tuple(ports), int(baud))
    now = time.monotonic()

    with _GPS_LOCK:
        if (
            cache_ttl > 0
            and _GPS_CACHE_KEY == cache_key
            and (now - _GPS_CACHE_TS) <= cache_ttl
        ):
            if _GPS_CACHE_FIX is None:
                return None
            return dict(_GPS_CACHE_FIX)

        fix: dict[str, Any] | None = None
        for port in ports:
            if not os.path.exists(port):
                continue
            fix = _sample_port(port=port, baud=baud, max_seconds=max_seconds)
            if fix:
                break

        _GPS_CACHE_KEY = cache_key
        _GPS_CACHE_TS = time.monotonic()
        _GPS_CACHE_FIX = dict(fix) if fix else None

        if _GPS_CACHE_FIX is None:
            return None
        return dict(_GPS_CACHE_FIX)
