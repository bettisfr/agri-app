#!/usr/bin/env bash
set -euo pipefail

# Manage Raspberry Pi network mode using NetworkManager.
# Modes: wifi_only | ap_only | hybrid_debug

WIFI_IFACE="${WIFI_IFACE:-wlan0}"
WIFI_CLIENT_CONN="${WIFI_CLIENT_CONN:-}"      # Optional: preferred client connection name
AP_CONN="${AP_CONN:-agriapp-rescue}"           # AP profile name
AP_SSID="${AP_SSID:-AgriApp-Rescue}"
AP_PSK="${AP_PSK:-AgriAppRescue123}"
AP_IPV4_ADDR="${AP_IPV4_ADDR:-192.168.4.1/24}"

usage() {
  echo "Usage: $0 --status | --set <wifi_only|ap_only|hybrid_debug>"
}

require_nmcli() {
  if ! command -v nmcli >/dev/null 2>&1; then
    echo "nmcli not found" >&2
    exit 1
  fi
}

require_wifi_iface() {
  if ! nmcli -t -f DEVICE,TYPE device status | grep -E "^${WIFI_IFACE}:wifi$" >/dev/null 2>&1; then
    echo "Wi-Fi interface '${WIFI_IFACE}' not found (or not Wi-Fi)." >&2
    exit 2
  fi
}

json_escape() {
  sed 's/\\/\\\\/g; s/"/\\"/g'
}

active_wifi_connections() {
  nmcli -t -f NAME,TYPE connection show --active \
    | awk -F: '$2 == "802-11-wireless" {print $1}'
}

is_connection_active() {
  local name="$1"
  active_wifi_connections | grep -Fxq "$name"
}

connection_exists() {
  local name="$1"
  nmcli -t -f NAME connection show | grep -Fxq "$name"
}

select_client_connection() {
  if [[ -n "$WIFI_CLIENT_CONN" ]] && connection_exists "$WIFI_CLIENT_CONN"; then
    printf '%s\n' "$WIFI_CLIENT_CONN"
    return 0
  fi

  # Pick first saved Wi-Fi profile that is not the AP rescue profile.
  nmcli -t -f NAME,TYPE connection show \
    | awk -F: -v ap="$AP_CONN" '$2 == "802-11-wireless" && $1 != ap {print $1; exit}'
}

ensure_ap_profile() {
  if ! connection_exists "$AP_CONN"; then
    nmcli connection add type wifi ifname "$WIFI_IFACE" con-name "$AP_CONN" ssid "$AP_SSID"
  fi

  nmcli connection modify "$AP_CONN" \
    802-11-wireless.mode ap \
    802-11-wireless.band bg \
    802-11-wireless.ap-isolation 0 \
    wifi-sec.key-mgmt wpa-psk \
    wifi-sec.psk "$AP_PSK" \
    ipv4.method shared \
    ipv4.addresses "$AP_IPV4_ADDR" \
    ipv6.method ignore \
    connection.autoconnect yes
}

set_wifi_only() {
  local client_conn
  client_conn="$(select_client_connection || true)"
  if [[ -z "$client_conn" ]]; then
    echo "No saved Wi-Fi client connection available." >&2
    return 3
  fi

  # In wifi_only, avoid AP auto-reappearing immediately.
  nmcli connection modify "$AP_CONN" connection.autoconnect no >/dev/null 2>&1 || true
  nmcli connection down "$AP_CONN" >/dev/null 2>&1 || true

  if ! nmcli connection up "$client_conn"; then
    # Restore AP if client activation fails.
    nmcli connection modify "$AP_CONN" connection.autoconnect yes >/dev/null 2>&1 || true
    nmcli connection up "$AP_CONN" >/dev/null 2>&1 || true
    return 4
  fi
}

set_ap_only() {
  ensure_ap_profile
  nmcli connection modify "$AP_CONN" connection.autoconnect yes >/dev/null 2>&1 || true

  while IFS= read -r conn; do
    [[ -z "$conn" ]] && continue
    [[ "$conn" == "$AP_CONN" ]] && continue
    nmcli connection down "$conn" >/dev/null 2>&1 || true
  done < <(active_wifi_connections)

  if [[ -n "$WIFI_CLIENT_CONN" ]]; then
    nmcli connection down "$WIFI_CLIENT_CONN" >/dev/null 2>&1 || true
  fi

  nmcli connection up "$AP_CONN"
}

set_hybrid_debug() {
  ensure_ap_profile
  nmcli connection modify "$AP_CONN" connection.autoconnect yes >/dev/null 2>&1 || true

  if [[ -n "$WIFI_CLIENT_CONN" ]]; then
    nmcli connection up "$WIFI_CLIENT_CONN" >/dev/null 2>&1 || true
  fi

  nmcli connection up "$AP_CONN"
}

emit_status_json() {
  local ap_active="false"
  local client_active="false"
  local mode="unknown"

  if is_connection_active "$AP_CONN"; then
    ap_active="true"
  fi

  if [[ -n "$WIFI_CLIENT_CONN" ]]; then
    if is_connection_active "$WIFI_CLIENT_CONN"; then
      client_active="true"
    fi
  else
    while IFS= read -r conn; do
      [[ -z "$conn" ]] && continue
      if [[ "$conn" != "$AP_CONN" ]]; then
        client_active="true"
      fi
    done < <(active_wifi_connections)
  fi

  if [[ "$ap_active" == "true" && "$client_active" == "true" ]]; then
    mode="hybrid_debug"
  elif [[ "$ap_active" == "true" ]]; then
    mode="ap_only"
  elif [[ "$client_active" == "true" ]]; then
    mode="wifi_only"
  fi

  local active_json=""
  while IFS= read -r conn; do
    [[ -z "$conn" ]] && continue
    local esc
    esc="$(printf '%s' "$conn" | json_escape)"
    if [[ -n "$active_json" ]]; then
      active_json+=" , "
    fi
    active_json+="\"$esc\""
  done < <(active_wifi_connections)

  if [[ -z "$active_json" ]]; then
    active_json=""
  fi

  printf '{"status":"success","mode":"%s","ap_active":%s,"client_active":%s,"ap_connection":"%s","wifi_connection":"%s","active_wifi_connections":[%s]}' \
    "$mode" "$ap_active" "$client_active" "$AP_CONN" "$WIFI_CLIENT_CONN" "$active_json"
}

main() {
  require_nmcli
  require_wifi_iface

  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi

  case "$1" in
    --status)
      emit_status_json
      ;;
    --set)
      if [[ $# -lt 2 ]]; then
        usage
        exit 1
      fi
      case "$2" in
        wifi_only)
          set_wifi_only
          ;;
        ap_only)
          set_ap_only
          ;;
        hybrid_debug)
          set_hybrid_debug
          ;;
        *)
          echo "Invalid mode: $2" >&2
          exit 1
          ;;
      esac
      emit_status_json
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
