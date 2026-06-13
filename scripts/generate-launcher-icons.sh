#!/usr/bin/env bash
# Generate Android launcher icons from app/logo.png (adaptive + legacy mipmaps).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${SOURCE:-$ROOT_DIR/app/logo.png}"
RES_DIR="${RES_DIR:-$ROOT_DIR/app/src/main/res}"

usage() {
  cat <<'EOF'
Usage: ./scripts/generate-launcher-icons.sh [options]

Generate ic_launcher_* mipmaps from app/logo.png with Android adaptive-icon safe zone.

Options:
  --source <path>           Source PNG (default: app/logo.png)
  --foreground-scale <f>    Foreground scale 0-1 (default: 0.62)
  --legacy-scale <f>        Legacy icon scale 0-1 (default: 0.88)
  -h, --help                Show this help

Examples:
  ./scripts/generate-launcher-icons.sh
  ./scripts/generate-launcher-icons.sh --foreground-scale 0.58
EOF
}

FOREGROUND_SCALE=""
LEGACY_SCALE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      SOURCE="$2"
      shift 2
      ;;
    --foreground-scale)
      FOREGROUND_SCALE="$2"
      shift 2
      ;;
    --legacy-scale)
      LEGACY_SCALE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "$SOURCE" ]]; then
  echo "error: source not found: $SOURCE" >&2
  exit 1
fi

ARGS=(--source "$SOURCE" --res-dir "$RES_DIR")
[[ -n "$FOREGROUND_SCALE" ]] && ARGS+=(--foreground-scale "$FOREGROUND_SCALE")
[[ -n "$LEGACY_SCALE" ]] && ARGS+=(--legacy-scale "$LEGACY_SCALE")

python3 "$ROOT_DIR/scripts/generate_launcher_icons.py" "${ARGS[@]}"
