#!/usr/bin/env bash
set -euo pipefail

# Capture one ESP32 image + one IMX708 image and save both into one local folder.
# Defaults are aligned with the current setup but can be overridden via flags.

OUT_ROOT="${OUT_ROOT:-/home/fra/Arduino/TestESP32}"
PAIR_NAME=""
ESP_CAPTURE_URL="${ESP_CAPTURE_URL:-http://192.168.1.50/capture}"
RPI_USER="${RPI_USER:-fra}"
RPI_HOST="${RPI_HOST:-192.168.1.67}"
RPI_APP_DIR="${RPI_APP_DIR:-/home/fra/agri-app}"

usage() {
  cat <<USAGE
Usage: $0 [options]

Options:
  --out-root <dir>      Root output directory (default: ${OUT_ROOT})
  --name <name>         Pair folder name (default: compare_<timestamp>)
  --esp-url <url>       ESP32 capture URL (default: ${ESP_CAPTURE_URL})
  --rpi-user <user>     RPi SSH user (default: ${RPI_USER})
  --rpi-host <host>     RPi SSH host (default: ${RPI_HOST})
  --rpi-app-dir <dir>   RPi app dir (default: ${RPI_APP_DIR})
  -h, --help            Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out-root)
      OUT_ROOT="$2"
      shift 2
      ;;
    --name)
      PAIR_NAME="$2"
      shift 2
      ;;
    --esp-url)
      ESP_CAPTURE_URL="$2"
      shift 2
      ;;
    --rpi-user)
      RPI_USER="$2"
      shift 2
      ;;
    --rpi-host)
      RPI_HOST="$2"
      shift 2
      ;;
    --rpi-app-dir)
      RPI_APP_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${PAIR_NAME}" ]]; then
  PAIR_NAME="compare_$(date +%Y%m%d-%H%M%S)"
fi

PAIR_DIR="${OUT_ROOT%/}/${PAIR_NAME}"
mkdir -p "${PAIR_DIR}"

ESP_FILE="${PAIR_DIR}/esp32.jpg"
IMX_FILE="${PAIR_DIR}/imx708.jpg"
SSH_TARGET="${RPI_USER}@${RPI_HOST}"

echo "[1/3] Capturing ESP32 image from ${ESP_CAPTURE_URL}"
curl --fail --silent --show-error "${ESP_CAPTURE_URL}" -o "${ESP_FILE}"

if [[ ! -s "${ESP_FILE}" ]]; then
  echo "ESP32 capture failed: empty file (${ESP_FILE})" >&2
  exit 1
fi

echo "[2/3] Capturing IMX708 image on ${SSH_TARGET}"
REMOTE_IMAGE_PATH="$(ssh "${SSH_TARGET}" "cd '${RPI_APP_DIR}' && bash scripts/capture_rpi.sh --oneshot >/dev/null 2>&1 && IMG=\$(ls -t static/uploads/images/*.jpg | head -n1) && readlink -f \"\$IMG\"" 2>/dev/null || true)"

if [[ -z "${REMOTE_IMAGE_PATH}" ]]; then
  echo "IMX708 capture failed: could not get remote image path" >&2
  exit 1
fi

echo "[3/3] Downloading IMX708 image: ${REMOTE_IMAGE_PATH}"
scp -q "${SSH_TARGET}:${REMOTE_IMAGE_PATH}" "${IMX_FILE}"

if [[ ! -s "${IMX_FILE}" ]]; then
  echo "IMX708 download failed: empty file (${IMX_FILE})" >&2
  exit 1
fi

echo
echo "Done. Pair folder: ${PAIR_DIR}"
ls -lh "${PAIR_DIR}/esp32.jpg" "${PAIR_DIR}/imx708.jpg"
