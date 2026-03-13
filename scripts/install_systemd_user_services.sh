#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_SYSTEMD_DIR="${HOME}/.config/systemd/user"

mkdir -p "${USER_SYSTEMD_DIR}"

cp "${PROJECT_DIR}/systemd/agriapp-server.service" "${USER_SYSTEMD_DIR}/"

systemctl --user daemon-reload
systemctl --user enable --now agriapp-server.service

# Keep user services running after reboot even without interactive login.
if command -v loginctl >/dev/null 2>&1; then
  if loginctl enable-linger "${USER}" >/dev/null 2>&1; then
    echo "Enabled lingering for user ${USER}."
  else
    echo "Could not enable lingering automatically. Run manually as root:"
    echo "  sudo loginctl enable-linger ${USER}"
  fi
fi

echo
systemctl --user --no-pager --full status agriapp-server.service | sed -n '1,12p'

echo
echo "Done. Logs:"
echo "  journalctl --user -u agriapp-server.service -f"
