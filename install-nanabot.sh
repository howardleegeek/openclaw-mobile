#!/usr/bin/env bash
#
# NanaBot (nanabot) - Oyster Edge Agent
# One-shot installer for Android (Termux) / Linux / macOS.
#
# Goals (borrowed from BotDrop patterns):
# - Single source of truth installer
# - Idempotent, safe re-runs
# - Structured output lines for GUI parsing (NANABOT_STEP / NANABOT_ERROR / NANABOT_COMPLETE)
# - Minimal dependencies (Python venv + websockets)
#
# Usage:
#   bash install-nanabot.sh            # install/update in ~/nanabot
#   bash install-nanabot.sh uninstall  # uninstall ~/nanabot
#
# Optional env (non-interactive preinstall):
#   NANABOT_GATEWAY_URL=wss://...      # default: wss://gateway.oyster.ai/ws
#   NANABOT_AUTH_TOKEN=...             # default: null (must set before start)
#   NANABOT_ENABLED=1                  # default: 0
#   NANABOT_DEVICE_NAME=...            # default: nanabot-<hostname>
#

set -euo pipefail
umask 077

SCRIPT_NAME="install-nanabot.sh"

# -----------------------
# Output helpers (safe for GUI parsing)
# -----------------------
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()      { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }

# Structured lines (stable)
step_start()  { echo "NANABOT_STEP:$1:START:$2"; }
step_done()   { echo "NANABOT_STEP:$1:DONE"; }
emit_error()  { echo "NANABOT_ERROR:$*"; }
emit_done()   { echo "NANABOT_COMPLETE"; }

die() {
  emit_error "$*"
  log_error "$*"
  exit 1
}

on_err() {
  local exit_code=$?
  local line_no=${1:-}
  emit_error "failed at line ${line_no} (exit ${exit_code})"
  exit "${exit_code}"
}
trap 'on_err $LINENO' ERR

# -----------------------
# Paths / defaults
# -----------------------
INSTALL_DIR="${HOME}/nanabot"
VENV_DIR="${INSTALL_DIR}/venv"
BIN_DIR="${INSTALL_DIR}/bin"
CONFIG_DIR="${INSTALL_DIR}/config"
LOGS_DIR="${INSTALL_DIR}/logs"
RUN_DIR="${INSTALL_DIR}/run"
MARKER_FILE="${INSTALL_DIR}/.nanabot_installed"

DEFAULT_GATEWAY_URL="wss://gateway.oyster.ai/ws"

NANABOT_GATEWAY_URL="${NANABOT_GATEWAY_URL:-$DEFAULT_GATEWAY_URL}"
NANABOT_AUTH_TOKEN="${NANABOT_AUTH_TOKEN:-}"
NANABOT_ENABLED="${NANABOT_ENABLED:-0}"
NANABOT_DEVICE_NAME="${NANABOT_DEVICE_NAME:-nanabot-$(hostname 2>/dev/null | cut -d. -f1 || echo unknown)}"

# Installer log (best-effort). Avoid failing if tee/process-substitution isn't available.
INSTALL_LOGFILE="${HOME}/nanabot-install.log"
if command -v tee >/dev/null 2>&1; then
  # shellcheck disable=SC2094
  exec > >(tee -a "${INSTALL_LOGFILE}") 2>&1 || true
fi

# -----------------------
# Environment detection
# -----------------------
is_termux() {
  # TERMUX_VERSION is not always exported; this path check is stable.
  [ -d "/data/data/com.termux" ] || [ -n "${TERMUX_VERSION:-}" ]
}

python_bin() {
  if command -v python3 >/dev/null 2>&1; then
    echo "python3"
  elif command -v python >/dev/null 2>&1; then
    echo "python"
  else
    echo ""
  fi
}

# -----------------------
# Install helpers
# -----------------------
ensure_deps() {
  step_start 0 "Checking dependencies"

  local py
  py="$(python_bin)"
  if [ -z "$py" ]; then
    if is_termux; then
      log_info "Termux detected; installing python + openssl + ca-certificates..."
      pkg update -y
      pkg install -y python openssl ca-certificates coreutils
      py="$(python_bin)"
    fi
  fi
  [ -n "$py" ] || die "python not found (install python3)"

  # Ensure venv module exists (some distros split it out)
  if ! "$py" -c 'import venv' >/dev/null 2>&1; then
    if is_termux; then
      die "python venv module missing in Termux python (unexpected)."
    else
      die "python venv module missing. Install your distro package: python3-venv"
    fi
  fi

  # Git is optional (only for future update workflows); keep soft dependency.
  if ! command -v git >/dev/null 2>&1; then
    if is_termux; then
      log_info "Installing git (Termux)..."
      pkg install -y git
    else
      log_warn "git not found (optional)."
    fi
  fi

  step_done 0
}

setup_dirs() {
  step_start 1 "Creating directories"
  mkdir -p "${BIN_DIR}" "${CONFIG_DIR}" "${LOGS_DIR}" "${RUN_DIR}"
  step_done 1
}

setup_venv() {
  step_start 2 "Setting up Python venv"

  if [ ! -d "${VENV_DIR}" ]; then
    local py
    py="$(python_bin)"
    "$py" -m venv "${VENV_DIR}"
  fi

  # Ensure minimal deps exist (fast no-op if already installed).
  "${VENV_DIR}/bin/python" -m pip install --upgrade pip >/dev/null
  if ! "${VENV_DIR}/bin/python" -c 'import websockets' >/dev/null 2>&1; then
    "${VENV_DIR}/bin/python" -m pip install --no-cache-dir websockets >/dev/null
  fi

  step_done 2
}

write_nanabot_py() {
  step_start 3 "Writing nanabot.py"

  cat > "${BIN_DIR}/nanabot.py" <<'PY'
#!/usr/bin/env python3
"""
nanabot - Oyster Edge Agent (minimal, installer-embedded)

Features:
- Termux/Android + Linux support
- WebSocket connects to gateway
- Periodic metrics (battery/cpu/memory) + on-demand commands
- Start/stop managed by nanabot manager script (pidfile)
"""

import asyncio
import json
import logging
import os
import signal
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

import websockets


INSTALL_DIR = Path.home() / "nanabot"
CONFIG_FILE = INSTALL_DIR / "config" / "nanabot.json"
LOGS_DIR = INSTALL_DIR / "logs"


def _now_iso() -> str:
    return datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def _read_text(path: Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8").strip()
    except Exception:
        return None


def _write_json(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=False), encoding="utf-8")


def _load_config() -> Dict[str, Any]:
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass

    # Default config if missing/corrupt.
    cfg = {
        "device_id": uuid.uuid4().hex[:16],
        "device_name": f"nanabot-{os.uname().nodename if hasattr(os, 'uname') else 'device'}",
        "gateway_url": "wss://gateway.oyster.ai/ws",
        "enabled": False,
        "metrics_interval": 30,
        "auth_token": None,
        "capabilities": {
            "metrics": True,
            "execute": True,
        },
    }
    _write_json(CONFIG_FILE, cfg)
    return cfg


def _setup_logging(level: str = "INFO") -> logging.Logger:
    LOGS_DIR.mkdir(parents=True, exist_ok=True)

    logger = logging.getLogger("nanabot")
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))

    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    fh = logging.FileHandler(LOGS_DIR / "nanabot.log")
    fh.setFormatter(fmt)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)

    logger.handlers.clear()
    logger.addHandler(fh)
    logger.addHandler(sh)

    return logger


def _collect_metrics(device_id: str) -> Dict[str, Any]:
    metrics: Dict[str, Any] = {
        "type": "metrics",
        "device_id": device_id,
        "timestamp": _now_iso(),
        "battery": None,
        "cpu_load_1m": None,
        "memory_kb": {},
    }

    # Battery (best-effort): sysfs; falls back to null.
    try:
        cap = Path("/sys/class/power_supply/battery/capacity")
        if cap.exists():
            metrics["battery"] = int(_read_text(cap) or "0")
    except Exception:
        pass

    # CPU load avg
    try:
        with open("/proc/loadavg", "r", encoding="utf-8") as f:
            metrics["cpu_load_1m"] = float(f.read().strip().split()[0])
    except Exception:
        pass

    # Memory (kB)
    try:
        mem_total = None
        mem_avail = None
        with open("/proc/meminfo", "r", encoding="utf-8") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    mem_total = int(line.split()[1])
                elif line.startswith("MemAvailable:"):
                    mem_avail = int(line.split()[1])
        if mem_total is not None:
            metrics["memory_kb"]["total"] = mem_total
        if mem_avail is not None:
            metrics["memory_kb"]["available"] = mem_avail
    except Exception:
        pass

    return metrics


@dataclass
class _Conn:
    ws: Any
    cfg: Dict[str, Any]
    logger: logging.Logger


async def _handle_message(conn: _Conn, raw: str) -> Optional[Dict[str, Any]]:
    try:
        msg = json.loads(raw)
    except Exception:
        return {"type": "error", "message": "invalid_json"}

    t = msg.get("type")
    if t == "ping":
        return {"type": "pong", "timestamp": _now_iso()}

    if t == "get_metrics":
        return _collect_metrics(conn.cfg["device_id"])

    if t == "execute":
        if not conn.cfg.get("capabilities", {}).get("execute", True):
            return {"type": "error", "message": "execute_disabled"}

        command = msg.get("command", "")
        if not isinstance(command, str) or not command.strip():
            return {"type": "error", "message": "empty_command"}

        try:
            r = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
            return {
                "type": "execute_result",
                "command": command,
                "stdout": r.stdout,
                "stderr": r.stderr,
                "returncode": r.returncode,
            }
        except subprocess.TimeoutExpired:
            return {"type": "error", "message": "command_timeout"}

    if t == "update_config":
        new_cfg = msg.get("config", {})
        if isinstance(new_cfg, dict):
            conn.cfg.update(new_cfg)
            _write_json(CONFIG_FILE, conn.cfg)
            return {"type": "config_updated", "timestamp": _now_iso()}
        return {"type": "error", "message": "invalid_config"}

    return {"type": "error", "message": f"unknown_type:{t}"}


async def _metrics_loop(conn: _Conn) -> None:
    interval = int(conn.cfg.get("metrics_interval") or 30)
    interval = max(10, min(interval, 3600))

    if not conn.cfg.get("capabilities", {}).get("metrics", True):
        return

    while True:
        await asyncio.sleep(interval)
        try:
            await conn.ws.send(json.dumps(_collect_metrics(conn.cfg["device_id"])))
        except Exception as e:
            conn.logger.warning(f"metrics_send_failed: {e}")
            raise


async def _run_once(cfg: Dict[str, Any], logger: logging.Logger) -> None:
    if not cfg.get("enabled", False):
        logger.warning("nanabot disabled (set enabled=true in config)")
        raise SystemExit(2)

    url = cfg.get("gateway_url") or "wss://gateway.oyster.ai/ws"
    token = cfg.get("auth_token")

    headers = {
        "X-Device-ID": cfg["device_id"],
        "X-Device-Name": cfg.get("device_name") or f"nanabot-{cfg['device_id']}",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    logger.info(f"connecting: {url}")

    async with websockets.connect(
        url,
        extra_headers=headers,
        ping_interval=20,
        ping_timeout=10,
        close_timeout=5,
    ) as ws:
        conn = _Conn(ws=ws, cfg=cfg, logger=logger)

        # Register (best-effort; gateway may ignore)
        try:
            await ws.send(
                json.dumps(
                    {
                        "type": "register",
                        "device_id": cfg["device_id"],
                        "device_name": cfg.get("device_name"),
                        "capabilities": cfg.get("capabilities", {}),
                        "timestamp": _now_iso(),
                    }
                )
            )
        except Exception:
            pass

        metrics_task = asyncio.create_task(_metrics_loop(conn))
        try:
            async for raw in ws:
                resp = await _handle_message(conn, raw)
                if resp is not None:
                    await ws.send(json.dumps(resp))
            # If the server closes the connection cleanly, avoid a tight reconnect loop.
            raise RuntimeError("connection_closed")
        finally:
            metrics_task.cancel()


async def main_async() -> None:
    cfg = _load_config()
    logger = _setup_logging(cfg.get("log_level", "INFO"))

    # Ensure required keys exist.
    cfg.setdefault("device_id", uuid.uuid4().hex[:16])
    cfg.setdefault("device_name", f"nanabot-{cfg['device_id']}")
    _write_json(CONFIG_FILE, cfg)

    delay = 5
    max_delay = 300

    while True:
        try:
            await _run_once(cfg, logger)
        except SystemExit:
            raise
        except Exception as e:
            logger.error(f"run_error: {e}")
            await asyncio.sleep(delay)
            delay = min(delay * 2, max_delay)


def main() -> None:
    def _signal_handler(_sig, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
PY

  chmod +x "${BIN_DIR}/nanabot.py"
  step_done 3
}

write_config() {
  step_start 4 "Writing config"

  mkdir -p "${CONFIG_DIR}"

  # Create config only if missing (do not clobber user edits).
  if [ ! -f "${CONFIG_DIR}/nanabot.json" ]; then
    NANABOT_ENABLED="${NANABOT_ENABLED}" \
    NANABOT_AUTH_TOKEN="${NANABOT_AUTH_TOKEN}" \
    NANABOT_DEVICE_NAME="${NANABOT_DEVICE_NAME}" \
    NANABOT_GATEWAY_URL="${NANABOT_GATEWAY_URL}" \
    "${VENV_DIR}/bin/python" - "${CONFIG_DIR}/nanabot.json" <<'PY'
import json, os, sys, uuid

path = sys.argv[1]

enabled_raw = (os.getenv("NANABOT_ENABLED") or "").strip().lower()
enabled = enabled_raw in ("1", "true", "yes", "on")

token = os.getenv("NANABOT_AUTH_TOKEN") or None
if token == "":
    token = None

cfg = {
    "device_id": uuid.uuid4().hex[:16],
    "device_name": os.getenv("NANABOT_DEVICE_NAME") or "nanabot-device",
    "gateway_url": os.getenv("NANABOT_GATEWAY_URL") or "wss://gateway.oyster.ai/ws",
    "enabled": enabled,
    "metrics_interval": 30,
    "auth_token": token,
    "capabilities": {
        "metrics": True,
        "execute": True,
    },
}

with open(path, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
  fi

  # Always keep an example template around.
  if [ ! -f "${CONFIG_DIR}/nanabot.json.example" ]; then
    cat > "${CONFIG_DIR}/nanabot.json.example" <<'EOF'
{
  "device_id": "auto",
  "device_name": "nanabot-my-phone",
  "gateway_url": "wss://gateway.oyster.ai/ws",
  "enabled": false,
  "metrics_interval": 30,
  "auth_token": "PASTE_TOKEN_HERE",
  "capabilities": {
    "metrics": true,
    "execute": true
  }
}
EOF
  fi

  step_done 4
}

write_manager() {
  step_start 5 "Writing manager command"

  cat > "${BIN_DIR}/nanabot" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

NANABOT_DIR="${HOME}/nanabot"
VENV_PY="${NANABOT_DIR}/venv/bin/python"
BOT_PY="${NANABOT_DIR}/bin/nanabot.py"
CFG="${NANABOT_DIR}/config/nanabot.json"
PID_FILE="${NANABOT_DIR}/run/nanabot.pid"
LOG_OUT="${NANABOT_DIR}/logs/nanabot.out"
LOG_MAIN="${NANABOT_DIR}/logs/nanabot.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; }

usage() {
  cat <<USAGE
nanabot manager

Usage:
  nanabot start
  nanabot stop
  nanabot restart
  nanabot status
  nanabot logs
  nanabot config
  nanabot uninstall
USAGE
}

is_running() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

require_install() {
  [ -x "$VENV_PY" ] || { err "missing venv: $VENV_PY"; exit 1; }
  [ -f "$BOT_PY" ] || { err "missing bot: $BOT_PY"; exit 1; }
  [ -f "$CFG" ] || { err "missing config: $CFG (copy .example)"; exit 1; }
}

cfg_enabled() {
  # Avoid jq dependency; simple grep is fine for our expected JSON.
  grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$CFG" 2>/dev/null
}

start() {
  require_install
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_OUT")"

  if is_running; then
    warn "already running (PID: $(cat "$PID_FILE"))"
    exit 0
  fi

  if ! cfg_enabled; then
    err "disabled: set enabled=true in $CFG"
    exit 2
  fi

  info "starting..."
  nohup "$VENV_PY" "$BOT_PY" >>"$LOG_OUT" 2>&1 &
  echo $! >"$PID_FILE"

  sleep 1
  if is_running; then
    ok "started (PID: $(cat "$PID_FILE"))"
    info "logs: tail -f $LOG_MAIN"
  else
    err "start failed (see $LOG_OUT)"
    rm -f "$PID_FILE" || true
    exit 1
  fi
}

stop() {
  if ! is_running; then
    warn "not running"
    exit 0
  fi

  local pid
  pid="$(cat "$PID_FILE")"
  info "stopping (PID: $pid)..."
  kill "$pid" 2>/dev/null || true

  i=0
  while [ "$i" -lt 10 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      ok "stopped"
      rm -f "$PID_FILE" || true
      exit 0
    fi
    sleep 1
    i=$((i + 1))
  done

  warn "force kill..."
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE" || true
  ok "stopped"
}

restart() {
  stop || true
  sleep 1
  start
}

status() {
  if is_running; then
    ok "running (PID: $(cat "$PID_FILE"))"
  else
    warn "stopped"
  fi

  if [ -f "$CFG" ]; then
    echo ""
    echo "Config: $CFG"
    # Safe summary (never print auth_token).
    grep -E '"device_id"|"device_name"|"gateway_url"|"enabled"|"metrics_interval"' "$CFG" 2>/dev/null | sed -e 's/^[[:space:]]*//'
    if grep -Eq '"auth_token"[[:space:]]*:[[:space:]]*null' "$CFG" 2>/dev/null; then
      echo "auth_token: (null)"
    elif grep -Eq '"auth_token"[[:space:]]*:[[:space:]]*\"\"' "$CFG" 2>/dev/null; then
      echo "auth_token: (empty)"
    elif grep -Eq '"auth_token"[[:space:]]*:' "$CFG" 2>/dev/null; then
      echo "auth_token: (set)"
    else
      echo "auth_token: (missing)"
    fi
  fi

  if [ -f "$LOG_MAIN" ]; then
    echo ""
    echo "Recent logs:"
    tail -n 10 "$LOG_MAIN" || true
  fi
}

logs() {
  if [ -f "$LOG_MAIN" ]; then
    tail -f "$LOG_MAIN"
  elif [ -f "$LOG_OUT" ]; then
    tail -f "$LOG_OUT"
  else
    err "no logs yet"
    exit 1
  fi
}

config() {
  ${EDITOR:-nano} "$CFG"
}

uninstall() {
  stop || true
  # Remove global symlinks if they point to this install.
  if [ -L "$HOME/.local/bin/nanabot" ]; then
    target="$(readlink "$HOME/.local/bin/nanabot" 2>/dev/null || true)"
    if [ "$target" = "$NANABOT_DIR/bin/nanabot" ]; then
      rm -f "$HOME/.local/bin/nanabot" || true
    fi
  fi
  if [ -L "/data/data/com.termux/files/usr/bin/nanabot" ]; then
    target="$(readlink "/data/data/com.termux/files/usr/bin/nanabot" 2>/dev/null || true)"
    if [ "$target" = "$NANABOT_DIR/bin/nanabot" ]; then
      rm -f "/data/data/com.termux/files/usr/bin/nanabot" || true
    fi
  fi
  rm -rf "$NANABOT_DIR"
  ok "removed $NANABOT_DIR"
}

cmd="${1:-}"
case "$cmd" in
  start) start ;;
  stop) stop ;;
  restart) restart ;;
  status) status ;;
  logs) logs ;;
  config) config ;;
  uninstall) uninstall ;;
  help|--help|-h|"") usage ;;
  *) err "unknown command: $cmd"; usage; exit 1 ;;
esac
EOF

  chmod +x "${BIN_DIR}/nanabot"
  step_done 5
}

link_global_cmds() {
  step_start 6 "Linking commands"

  local target="${BIN_DIR}/nanabot"

  if is_termux; then
    if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX}/bin" ]; then
      ln -sf "${target}" "${PREFIX}/bin/nanabot" 2>/dev/null || true
    elif [ -d "/data/data/com.termux/files/usr/bin" ]; then
      ln -sf "${target}" "/data/data/com.termux/files/usr/bin/nanabot" 2>/dev/null || true
    fi
  else
    mkdir -p "${HOME}/.local/bin" 2>/dev/null || true
    if [ -d "${HOME}/.local/bin" ]; then
      ln -sf "${target}" "${HOME}/.local/bin/nanabot" 2>/dev/null || true
    fi
  fi

  step_done 6
}

setup_termux_boot() {
  if ! is_termux; then
    return
  fi

  step_start 7 "Setting up Termux boot (optional)"

  mkdir -p "${HOME}/.termux/boot" 2>/dev/null || true

  cat > "${HOME}/.termux/boot/nanabot" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
# Auto-start NanaBot on boot (requires Termux:Boot app).
#
# If termux-wake-lock exists (Termux:API), take a wakelock to keep the process alive.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

command -v nanabot >/dev/null 2>&1 && nanabot start || true
EOF

  chmod +x "${HOME}/.termux/boot/nanabot" || true

  step_done 7
}

write_marker() {
  touch "${MARKER_FILE}"
}

show_post_install() {
  cat <<EOF

${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${GREEN}  NanaBot installed${NC}
${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

Install dir: ${INSTALL_DIR}
Config:       ${CONFIG_DIR}/nanabot.json
Logs:         ${LOGS_DIR}/nanabot.log

Next:
1) Edit config (set enabled=true and auth_token):
   nano ${CONFIG_DIR}/nanabot.json

2) Start:
   nanabot start

3) Status/logs:
   nanabot status
   nanabot logs

EOF
}

uninstall() {
  if [ -x "${BIN_DIR}/nanabot" ]; then
    "${BIN_DIR}/nanabot" uninstall || true
  fi
  rm -rf "${INSTALL_DIR}"
  log_ok "Uninstalled ${INSTALL_DIR}"
}

install() {
  ensure_deps
  setup_dirs
  setup_venv
  write_nanabot_py
  write_config
  write_manager
  link_global_cmds
  setup_termux_boot
  write_marker
  emit_done
  show_post_install
}

main() {
  local cmd="${1:-install}"
  case "${cmd}" in
    install) install ;;
    uninstall) uninstall ;;
    *) install ;; # default behavior for curl|bash usage
  esac
}

main "${1:-}"
