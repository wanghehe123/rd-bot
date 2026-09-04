#!/usr/bin/env bash
# Keep CPA reachable at http://127.0.0.1:8317/v1 via SSH tunnel to Azure VM.
set -euo pipefail
KEY="${CPA_SSH_KEY:-$HOME/wish_key.pem}"
REMOTE="${CPA_SSH_REMOTE:-azureuser@172.198.152.135}"
LOCAL_PORT="${CPA_LOCAL_PORT:-8317}"
REMOTE_PORT="${CPA_REMOTE_PORT:-8317}"
LOG="${CPA_TUNNEL_LOG:-/tmp/cpa-ssh-tunnel.log}"

if [[ ! -f "$KEY" ]]; then
  echo "missing SSH key: $KEY" >&2
  exit 1
fi
chmod 600 "$KEY"

if ss -tln | grep -q ":${LOCAL_PORT} "; then
  echo "tunnel already listening on :${LOCAL_PORT}"
  exit 0
fi

nohup ssh -N -T \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=60 \
  -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=no \
  -i "$KEY" \
  -L "${LOCAL_PORT}:127.0.0.1:${REMOTE_PORT}" \
  "$REMOTE" >> "$LOG" 2>&1 &

sleep 2
if curl -sf --connect-timeout 3 "http://127.0.0.1:${LOCAL_PORT}/v1/models" -H "Authorization: Bearer ${CPA_API_KEY:-missing}" >/dev/null 2>&1 \
   || curl -sf --connect-timeout 3 "http://127.0.0.1:${LOCAL_PORT}/v1/models" 2>&1 | grep -q Missing; then
  echo "CPA tunnel up on 127.0.0.1:${LOCAL_PORT}"
else
  echo "tunnel process started but endpoint not reachable; see $LOG" >&2
  tail -5 "$LOG" >&2 || true
  exit 1
fi
