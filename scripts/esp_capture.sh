#!/usr/bin/env bash
set -euo pipefail

# Thin wrapper around scripts/capture_esp.py
# Defaults target the current ESP endpoint and save into static/uploads/images.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ESP_URL_DEFAULT="${ESP_URL:-http://192.168.1.50/capture}"
OUT_DEFAULT="${OUT_FILE:-${PROJECT_DIR}/static/uploads/images/esp_$(date +%Y%m%d-%H%M%S).jpg}"

if [[ $# -eq 0 ]]; then
  exec python3 "${PROJECT_DIR}/scripts/capture_esp.py" \
    --url "${ESP_URL_DEFAULT}" \
    --timeout 20 \
    --framesize qxga \
    --set quality=10 \
    --out "${OUT_DEFAULT}"
fi

exec python3 "${PROJECT_DIR}/scripts/capture_esp.py" "$@"
