#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_SYSTEMD_DIR="${HOME}/.config/systemd/user"
UNIT_NAME="agriapp-local.service"
UNIT_PATH="${USER_SYSTEMD_DIR}/${UNIT_NAME}"

mkdir -p "${USER_SYSTEMD_DIR}"

cat >"${UNIT_PATH}" <<EOF
[Unit]
Description=AgriApp Studio Local Flask Server
After=network.target

[Service]
Type=simple
WorkingDirectory=${PROJECT_DIR}
ExecStart=/usr/bin/env python3 -B server.py
Restart=always
RestartSec=2
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now "${UNIT_NAME}"

echo
systemctl --user --no-pager --full status "${UNIT_NAME}" | sed -n '1,14p'
echo
echo "Local service installed: ${UNIT_NAME}"
echo "Logs: journalctl --user -u ${UNIT_NAME} -f"
