#!/usr/bin/env bash
set -euo pipefail

# Deploy project to Raspberry Pi via rsync over SSH.
# Modes: --mount, --umount, --sync, --sync-dry, --ssh, --remote-cmd,
#        --git-pull, --git-push, --git-commit, --reload-server, --esp-build, --esp-flash,
#        --android-build, --android-install, --android-run, --android-cir.

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/"
MODE=""
COMMIT_MSG=""
SSH_CMD=""
REMOTE_CMD=""

REMOTE_USER="fra"
REMOTE_HOST="192.168.1.67"
REMOTE_PATH="~/agri-app"
REMOTE_MOUNT_PATH="/home/fra"
MOUNT_DIR="${HOME}/mnt/rpi5"
RSYNC_SSH_PORT="22"
ESP_FQBN="esp32:esp32:esp32cam"
ESP_PORT="/dev/ttyUSB0"
ESP_SKETCH_DIR="firmware/esp32-cam"
ANDROID_DIR="mobile/android"
ANDROID_APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_COMPONENT="it.unipg.agriapp/.MainActivity"

usage() {
  cat <<EOF
Usage:
  $0 <mode> [options]

Modes (choose one):
  --sync                 Sync local project to Raspberry Pi via rsync.
  --sync-dry             Show what would be synced (no changes applied).
  --mount                Mount remote path with sshfs.
  --umount               Unmount local sshfs mount.
  --ssh                  Open interactive SSH session to Raspberry Pi.
  --remote-cmd           Run one remote command (requires --cmd "...").
  --git-pull             Run git pull in local repo.
  --git-push             Run git push in local repo.
  --git-commit           Commit local changes (requires --msg "...").
  --reload-server        Restart agriapp server service on Raspberry Pi.
  --esp-build            Compile ESP32 firmware with arduino-cli.
  --esp-flash            Compile + flash ESP32 firmware.
  --android-build        Build Android debug APK.
  --android-install      Install Android debug APK with adb.
  --android-run          Launch Android app activity with adb.
  --android-cir          Build + Install + Run Android app.

Options:
  --msg, -m <message>            Commit message (for --git-commit).
  --cmd <command>                Remote command string (for --remote-cmd).
  --host <ip_or_host>            Remote host (default: ${REMOTE_HOST}).
  --user <user>                  Remote user (default: ${REMOTE_USER}).
  --path <remote_path>           Remote deploy path (default: ${REMOTE_PATH}).
  --mount-path <remote_path>     Remote mount path for sshfs (default: ${REMOTE_MOUNT_PATH}).
  --mount-dir <local_dir>        Local mount dir for sshfs (default: ${MOUNT_DIR}).
  --port <ssh_port>              SSH port (default: ${RSYNC_SSH_PORT}).

ESP options:
  --esp-port <tty>               Serial port (default: ${ESP_PORT}).
  --esp-fqbn <fqbn>              Board FQBN (default: ${ESP_FQBN}).
  --esp-sketch-dir <dir>         Sketch directory (default: ${ESP_SKETCH_DIR}).

Android options:
  --android-dir <dir>            Android project dir (default: ${ANDROID_DIR}).
  --android-apk <rel_path>       APK path from android dir (default: ${ANDROID_APK}).
  --android-component <pkg/.Act> Activity component (default: ${ANDROID_COMPONENT}).

Help:
  -h, --help                     Show this help message.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sync)
      MODE="sync"
      ;;
    --sync-dry|--synch-dry)
      MODE="sync-dry"
      ;;
    --mount)
      MODE="mount"
      ;;
    --umount)
      MODE="umount"
      ;;
    --ssh)
      MODE="ssh"
      ;;
    --remote-cmd)
      MODE="remote-cmd"
      ;;
    --git-pull|--pull)
      MODE="git-pull"
      ;;
    --git-push|--push)
      MODE="git-push"
      ;;
    --git-commit|--commit)
      MODE="git-commit"
      ;;
    --reload-server)
      MODE="reload-server"
      ;;
    --esp-build)
      MODE="esp-build"
      ;;
    --esp-flash)
      MODE="esp-flash"
      ;;
    --android-build)
      MODE="android-build"
      ;;
    --android-install)
      MODE="android-install"
      ;;
    --android-run)
      MODE="android-run"
      ;;
    --android-cir|--android-all)
      MODE="android-cir"
      ;;
    --msg|-m)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      COMMIT_MSG="$2"
      shift
      ;;
    --cmd)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      REMOTE_CMD="$2"
      shift
      ;;
    --host)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      REMOTE_HOST="$2"
      shift
      ;;
    --user)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      REMOTE_USER="$2"
      shift
      ;;
    --path)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      REMOTE_PATH="$2"
      shift
      ;;
    --mount-path)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      REMOTE_MOUNT_PATH="$2"
      shift
      ;;
    --mount-dir)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      MOUNT_DIR="$2"
      shift
      ;;
    --port)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      RSYNC_SSH_PORT="$2"
      shift
      ;;
    --esp-port)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ESP_PORT="$2"
      shift
      ;;
    --esp-fqbn)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ESP_FQBN="$2"
      shift
      ;;
    --esp-sketch-dir)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ESP_SKETCH_DIR="$2"
      shift
      ;;
    --android-dir)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ANDROID_DIR="$2"
      shift
      ;;
    --android-apk)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ANDROID_APK="$2"
      shift
      ;;
    --android-component)
      if [[ -z "${2:-}" ]]; then
        usage
        exit 1
      fi
      ANDROID_COMPONENT="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ -z "${MODE}" ]]; then
  usage
  exit 1
fi

require_arduino_cli() {
  if ! command -v arduino-cli >/dev/null 2>&1; then
    echo "arduino-cli not found in PATH." >&2
    exit 1
  fi
}

require_android_tools() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found in PATH." >&2
    exit 1
  fi
}

run_android_gradle() {
  local android_abs_dir="$1"
  (
    cd "${android_abs_dir}"
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-${android_abs_dir}/.gradle-local}" ./gradlew :app:assembleDebug
  )
}

if [[ "${MODE}" == "git-pull" ]]; then
  echo "[GIT-PULL] Running git pull in ${SRC_DIR}"
  cd "${SRC_DIR}"
  git pull
  exit 0
fi

if [[ "${MODE}" == "git-push" ]]; then
  echo "[GIT-PUSH] Running git push in ${SRC_DIR}"
  cd "${SRC_DIR}"
  git push
  exit 0
fi

if [[ "${MODE}" == "git-commit" ]]; then
  if [[ -z "${COMMIT_MSG}" ]]; then
    echo "Missing commit message. Use --msg \"your message\""
    exit 1
  fi
  echo "[GIT-COMMIT] Running git commit in ${SRC_DIR}"
  cd "${SRC_DIR}"
  git commit -m "${COMMIT_MSG}"
  exit 0
fi

if [[ "${MODE}" == "reload-server" ]]; then
  SSH_TARGET="${REMOTE_USER}@${REMOTE_HOST}"
  SSH_CMD=(ssh -p "${RSYNC_SSH_PORT}" "${SSH_TARGET}")
  REMOTE_SERVER_CMD="set -e; \
cd ${REMOTE_PATH}; \
if systemctl --user list-unit-files agriapp-server.service >/dev/null 2>&1; then \
  systemctl --user restart agriapp-server.service; \
  systemctl --user --no-pager --full status agriapp-server.service | sed -n '1,12p'; \
else \
  pkill -f 'python3 server.py' >/dev/null 2>&1 || true; \
  nohup bash scripts/run_server.sh >/tmp/agriapp-server.log 2>&1 < /dev/null & \
  sleep 1; \
  pgrep -af 'python3 server.py|run_server.sh' || true; \
fi"
  echo "[RELOAD-SERVER] ${SSH_TARGET} (${REMOTE_PATH})"
  "${SSH_CMD[@]}" "${REMOTE_SERVER_CMD}"
  exit 0
fi

if [[ "${MODE}" == "esp-build" ]]; then
  require_arduino_cli
  echo "[ESP-BUILD] fqbn=${ESP_FQBN} sketch=${ESP_SKETCH_DIR}"
  cd "${SRC_DIR}"
  arduino-cli compile --fqbn "${ESP_FQBN}" "${ESP_SKETCH_DIR}"
  exit 0
fi

if [[ "${MODE}" == "esp-flash" ]]; then
  require_arduino_cli
  echo "[ESP-FLASH] fqbn=${ESP_FQBN} port=${ESP_PORT} sketch=${ESP_SKETCH_DIR}"
  cd "${SRC_DIR}"
  arduino-cli compile --fqbn "${ESP_FQBN}" "${ESP_SKETCH_DIR}"
  arduino-cli upload -p "${ESP_PORT}" --fqbn "${ESP_FQBN}" "${ESP_SKETCH_DIR}"
  exit 0
fi

if [[ "${MODE}" == "android-build" ]]; then
  echo "[ANDROID-BUILD] dir=${ANDROID_DIR}"
  run_android_gradle "${SRC_DIR}/${ANDROID_DIR}"
  exit 0
fi

if [[ "${MODE}" == "android-install" ]]; then
  require_android_tools
  echo "[ANDROID-INSTALL] apk=${ANDROID_DIR}/${ANDROID_APK}"
  cd "${SRC_DIR}/${ANDROID_DIR}"
  adb install -r "${ANDROID_APK}"
  exit 0
fi

if [[ "${MODE}" == "android-run" ]]; then
  require_android_tools
  echo "[ANDROID-RUN] component=${ANDROID_COMPONENT}"
  adb shell am start -n "${ANDROID_COMPONENT}"
  exit 0
fi

if [[ "${MODE}" == "android-cir" ]]; then
  require_android_tools
  echo "[ANDROID-CIR] build + install + run"
  run_android_gradle "${SRC_DIR}/${ANDROID_DIR}"
  cd "${SRC_DIR}/${ANDROID_DIR}"
  adb install -r "${ANDROID_APK}"
  adb shell am start -n "${ANDROID_COMPONENT}"
  exit 0
fi

SSH_TARGET="${REMOTE_USER}@${REMOTE_HOST}"
SSH_CMD=(ssh -p "${RSYNC_SSH_PORT}" "${SSH_TARGET}")

if [[ "${MODE}" == "mount" ]]; then
  mkdir -p "${MOUNT_DIR}"
  if mountpoint -q "${MOUNT_DIR}"; then
    echo "Already mounted: ${MOUNT_DIR}"
    exit 0
  fi
  echo "[MOUNT] ${SSH_TARGET}:${REMOTE_MOUNT_PATH} -> ${MOUNT_DIR} (port ${RSYNC_SSH_PORT})"
  sshfs -p "${RSYNC_SSH_PORT}" "${SSH_TARGET}:${REMOTE_MOUNT_PATH}" "${MOUNT_DIR}"
  echo "Mounted."
  exit 0
fi

if [[ "${MODE}" == "umount" ]]; then
  if ! mountpoint -q "${MOUNT_DIR}"; then
    echo "Not mounted: ${MOUNT_DIR}"
    exit 0
  fi
  echo "[UMOUNT] ${MOUNT_DIR}"
  fusermount -u "${MOUNT_DIR}"
  echo "Unmounted."
  exit 0
fi

if [[ "${MODE}" == "ssh" ]]; then
  echo "[SSH] Connecting to ${SSH_TARGET} (port ${RSYNC_SSH_PORT})"
  exec "${SSH_CMD[@]}"
fi

if [[ "${MODE}" == "remote-cmd" ]]; then
  if [[ -z "${REMOTE_CMD}" ]]; then
    echo "Missing remote command. Use --cmd \"...\""
    exit 1
  fi
  echo "[REMOTE-CMD] ${SSH_TARGET}: ${REMOTE_CMD}"
  "${SSH_CMD[@]}" "${REMOTE_CMD}"
  exit 0
fi

COMMON_ARGS=(
  -rltDzv
  --delete
  --no-owner
  --no-group
  --no-perms
  --chmod=Du=rwx,Dgo=rx,Fu=rw,Fgo=r
  --exclude='.git/'
  --exclude='.idea/'
  --exclude='.codex-project-id'
  --exclude='__pycache__/'
  --exclude='.venv/'
  --exclude='venv/'
  --exclude='*.pyc'
  --exclude='mobile/android/.gradle/'
  --exclude='mobile/android/.gradle-local/'
  --exclude='mobile/android/.kotlin/'
  --exclude='mobile/android/build/'
  --exclude='mobile/android/app/build/'
  --exclude='mobile/android/local.properties'
  --exclude='firmware/esp32-cam/secrets.h'
  --exclude='static/uploads/images/'
  --exclude='static/uploads/thumbs/'
  --exclude='static/uploads/labels/'
  --exclude='static/uploads/jsons/'
  --exclude='static/uploads/metadata/'
  --exclude='docs/software-doc-latex/*.aux'
  --exclude='docs/software-doc-latex/*.log'
  --exclude='docs/software-doc-latex/*.out'
  --exclude='docs/software-doc-latex/*.toc'
  --exclude='docs/software-doc-latex/*.bbl'
  --exclude='docs/software-doc-latex/*.blg'
  --exclude='docs/software-doc-latex/*.fls'
  --exclude='docs/software-doc-latex/*.fdb_latexmk'
  --exclude='docs/software-doc-latex/main.pdf'
  --exclude='docs/software-doc-latex/sections/*.aux'
)

REMOTE_DEST="${SSH_TARGET}:${REMOTE_PATH%/}/"

if [[ "${MODE}" != "sync" && "${MODE}" != "sync-dry" ]]; then
  usage
  exit 1
fi

if [[ "${MODE}" == "sync-dry" ]]; then
  echo "[SYNC-DRY] Preview from ${SRC_DIR} to ${REMOTE_DEST} (port ${RSYNC_SSH_PORT})"
  rsync "${COMMON_ARGS[@]}" --dry-run -e "ssh -p ${RSYNC_SSH_PORT}" "${SRC_DIR}" "${REMOTE_DEST}"
  echo "No files were changed."
else
  echo "[SYNC] Syncing from ${SRC_DIR} to ${REMOTE_DEST} (port ${RSYNC_SSH_PORT})"
  rsync "${COMMON_ARGS[@]}" -e "ssh -p ${RSYNC_SSH_PORT}" "${SRC_DIR}" "${REMOTE_DEST}"
  echo "Sync completed."
fi
