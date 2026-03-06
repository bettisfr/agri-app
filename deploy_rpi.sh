#!/usr/bin/env bash
set -euo pipefail

# Deploy project to Raspberry Pi via rsync over SSH.
# Modes: --mount, --umount, --sync, --sync-dry, --ssh, --remote-cmd, --git-pull, --git-push, --git-commit.

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/"
MODE=""
COMMIT_MSG=""
SSH_CMD=""
REMOTE_CMD=""

REMOTE_USER="fra"
REMOTE_HOST="192.168.1.67"
REMOTE_PATH="~/insect-cloud"
REMOTE_MOUNT_PATH="/home/fra"
MOUNT_DIR="${HOME}/mnt/rpi5"
RSYNC_SSH_PORT="22"

usage() {
  echo "Usage: $0 [--mount|--umount|--sync|--sync-dry|--ssh|--remote-cmd|--git-pull|--git-push|--git-commit] [--msg <message>] [--cmd <command>] [--host <ip_or_host>] [--user <user>] [--path <remote_path>] [--mount-path <remote_mount_path>] [--mount-dir <local_mount_dir>] [--port <ssh_port>]"
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
  --exclude='static/uploads/images/'
  --exclude='static/uploads/labels/'
  --exclude='static/uploads/jsons/'
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
