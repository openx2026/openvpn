#!/usr/bin/env bash
# Download / refresh vpn-engine/libs native dependencies:
#   - libv2ray.aar        (AndroidLibXrayLite GitHub Release)
#   - libhev-socks5-tunnel.so per ABI (NDK compile or extract from v2rayNG APK)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT_DIR/.." && pwd)"
LIBS_DIR="$ROOT_DIR/vpn-engine/libs"
V2RAYNG_DIR="${V2RAYNG_DIR:-$REPO_ROOT/v2rayNG}"
UPSTREAM_MD="$ROOT_DIR/vendor/UPSTREAM.md"

LIBV2RAY_REPO="${LIBV2RAY_REPO:-2dust/AndroidLibXrayLite}"
V2RAYNG_REPO="${V2RAYNG_REPO:-2dust/v2rayNG}"

LIBV2RAY_TAG=""
LIBV2RAY_MODE="fetch"   # fetch | skip
HEV_MODE="auto"   # auto | compile | apk | skip
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: ./scripts/update-vpn-engine-libs.sh [options]

Refresh openvpn-android/vpn-engine/libs/ with latest native binaries.

Options:
  --tag <ver>       Pin libv2ray.aar to a specific AndroidLibXrayLite tag (e.g. v26.6.2)
  --libv2ray <mode> libv2ray.aar: fetch | skip (default: fetch)
  --hev <mode>      hev-socks5-tunnel source: auto | compile | apk | skip (default: auto)
  --dry-run         Print actions without downloading
  -h, --help        Show this help

Environment:
  NDK_HOME          If set (and --hev auto|compile), run v2rayNG/compile-hevtun.sh
  V2RAYNG_DIR       Path to v2rayNG git submodule (default: <repo>/v2rayNG)

Examples:
  ./scripts/update-vpn-engine-libs.sh
  ./scripts/update-vpn-engine-libs.sh --tag v26.6.2 --hev apk
  NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865 ./scripts/update-vpn-engine-libs.sh --hev compile
EOF
}

log() { printf '[update-libs] %s\n' "$*" >&2; }
die() { printf '[update-libs] ERROR: %s\n' "$*" >&2; exit 1; }

run() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "[dry-run] $*"
  else
    "$@"
  fi
}

release_tag_name() {
  python3 -c "import json,sys; print(json.load(sys.stdin)['tag_name'])"
}

gh_release_json() {
  local repo="$1" tag="${2:-}"
  if [[ -n "$tag" ]]; then
    curl -fsSL "https://api.github.com/repos/${repo}/releases/tags/${tag}"
  else
    curl -fsSL "https://api.github.com/repos/${repo}/releases/latest"
  fi
}

find_asset_url() {
  local json="$1" filename="$2"
  python3 -c "import json,sys
name=sys.argv[1]
for a in json.load(sys.stdin).get('assets', []):
    if a.get('name') == name:
        print(a['browser_download_url'])
        break" "$filename" <<<"$json"
}

CURL_RETRY="${CURL_RETRY:-5}"
CURL_RETRY_DELAY="${CURL_RETRY_DELAY:-3}"

verify_zip_archive() {
  local file="$1"
  [[ -s "$file" ]] || return 1
  python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1])" "$file" 2>/dev/null
}

download_file() {
  local url="$1" dest="$2"
  [[ -n "$url" ]] || die "empty download url for $dest"
  log "download -> $dest"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "[dry-run] curl -fsSL '$url' -o '$dest'"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  local tmp="${dest}.part"
  rm -f "$tmp"
  local attempt=1
  while [[ "$attempt" -le "$CURL_RETRY" ]]; do
    if curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 30 --max-time 600 \
        "$url" -o "$tmp"; then
      if verify_zip_archive "$tmp" || [[ "$dest" == *.apk ]]; then
        mv -f "$tmp" "$dest"
        log "download ok ($(wc -c < "$dest" | tr -d ' ') bytes)"
        return 0
      fi
      log "invalid archive (attempt $attempt/$CURL_RETRY), retrying..."
      rm -f "$tmp"
    else
      log "curl failed (attempt $attempt/$CURL_RETRY): $url"
      rm -f "$tmp"
    fi
    if [[ "$attempt" -lt "$CURL_RETRY" ]]; then
      sleep "$CURL_RETRY_DELAY"
    fi
    attempt=$((attempt + 1))
  done
  rm -f "$tmp" "$dest"
  die "download failed after $CURL_RETRY attempts: $url"
}

update_upstream_md() {
  local libv2ray_tag="$1" v2rayng_tag="$2" sync_date="$3"
  [[ -f "$UPSTREAM_MD" ]] || return 0
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "[dry-run] update $UPSTREAM_MD (libv2ray=$libv2ray_tag, v2rayng=$v2rayng_tag)"
    return 0
  fi
  python3 - "$UPSTREAM_MD" "$libv2ray_tag" "$v2rayng_tag" "$sync_date" <<'PY'
import re, sys
from pathlib import Path
path, lib_tag, ng_tag, sync_date = sys.argv[1:5]
text = Path(path).read_text()
row = f"| libs-refresh | {ng_tag} | {lib_tag} | apk-extract | {sync_date} |"
if "| OpenVPN Android |" in text:
    text = re.sub(r"^\| 0\.1\.0-init \|.*$", row, text, count=1, flags=re.M)
    if row not in text:
        text = re.sub(r"^\| libs-refresh \|.*$", row, text, count=1, flags=re.M)
Path(path).write_text(text)
PY
}

fetch_libv2ray_aar() {
  local json tag url dest="$LIBS_DIR/libv2ray.aar"
  if [[ -n "$LIBV2RAY_TAG" ]]; then
    json="$(gh_release_json "$LIBV2RAY_REPO" "$LIBV2RAY_TAG")"
    tag="$LIBV2RAY_TAG"
  else
    json="$(gh_release_json "$LIBV2RAY_REPO")"
    tag="$(printf '%s' "$json" | release_tag_name)"
  fi
  url="$(find_asset_url "$json" "libv2ray.aar")"
  [[ -n "$url" ]] || die "libv2ray.aar not found in release $tag"
  log "AndroidLibXrayLite tag: $tag"
  download_file "$url" "$dest"
  printf '%s' "$tag"
}

compile_hev_from_ndk() {
  local compile_sh="$V2RAYNG_DIR/compile-hevtun.sh"
  [[ -d "$V2RAYNG_DIR/hev-socks5-tunnel" ]] || die "hev-socks5-tunnel submodule missing in $V2RAYNG_DIR (run: git submodule update --init --recursive)"
  [[ -n "${NDK_HOME:-}" ]] || die "NDK_HOME is not set"
  [[ -d "$NDK_HOME" ]] || die "NDK_HOME does not exist: $NDK_HOME"
  [[ -f "$compile_sh" ]] || die "missing $compile_sh"
  log "compile hev-socks5-tunnel via NDK ($NDK_HOME)"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "[dry-run] (cd $V2RAYNG_DIR && bash compile-hevtun.sh)"
    log "[dry-run] cp -r $V2RAYNG_DIR/libs/* $LIBS_DIR/"
    return 0
  fi
  (cd "$V2RAYNG_DIR" && bash compile-hevtun.sh)
  [[ -d "$V2RAYNG_DIR/libs" ]] || die "compile-hevtun.sh did not produce $V2RAYNG_DIR/libs"
  shopt -s nullglob
  local copied=0
  for abi_dir in "$V2RAYNG_DIR/libs"/*; do
    [[ -d "$abi_dir" ]] || continue
    local so="$abi_dir/libhev-socks5-tunnel.so"
    [[ -f "$so" ]] || continue
    mkdir -p "$LIBS_DIR/$(basename "$abi_dir")"
    cp -f "$so" "$LIBS_DIR/$(basename "$abi_dir")/"
    copied=1
  done
  [[ "$copied" -eq 1 ]] || die "no libhev-socks5-tunnel.so found under $V2RAYNG_DIR/libs"
  log "copied hev .so from NDK build"
}

extract_hev_from_apk() {
  local json tag apk_name url tmp apk
  json="$(gh_release_json "$V2RAYNG_REPO")"
  tag="$(printf '%s' "$json" | release_tag_name)"
  apk_name="v2rayNG_${tag}_universal.apk"
  url="$(find_asset_url "$json" "$apk_name")"
  if [[ -z "$url" ]]; then
    apk_name="v2rayNG_${tag}-fdroid_universal.apk"
    url="$(find_asset_url "$json" "$apk_name")"
  fi
  [[ -n "$url" ]] || die "no universal v2rayNG APK in release $tag"
  log "extract hev from v2rayNG $tag ($apk_name)"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "[dry-run] unzip $apk_name lib/*/libhev-socks5-tunnel.so -> $LIBS_DIR/"
    printf '%s' "$tag"
    return 0
  fi
  tmp="$(mktemp -d)"
  apk="$tmp/$apk_name"
  download_file "$url" "$apk"
  unzip -q -o "$apk" 'lib/*/libhev-socks5-tunnel.so' -d "$tmp/extract"
  local copied=0
  shopt -s nullglob
  for so in "$tmp/extract/lib"/*/libhev-socks5-tunnel.so; do
    local abi
    abi="$(basename "$(dirname "$so")")"
    mkdir -p "$LIBS_DIR/$abi"
    cp -f "$so" "$LIBS_DIR/$abi/"
    copied=1
    log "  -> $LIBS_DIR/$abi/libhev-socks5-tunnel.so"
  done
  rm -rf "$tmp"
  [[ "$copied" -eq 1 ]] || die "libhev-socks5-tunnel.so not found in $apk_name"
  printf '%s' "$tag"
}

verify_hev_libs() {
  [[ "$HEV_MODE" == "skip" ]] && return 0
  local missing=0 abi
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [[ ! -s "$LIBS_DIR/$abi/libhev-socks5-tunnel.so" ]]; then
      log "missing: $LIBS_DIR/$abi/libhev-socks5-tunnel.so"
      missing=1
    fi
  done
  [[ "$missing" -eq 0 ]] || die "incomplete hev-socks5-tunnel libs under $LIBS_DIR"
}

fetch_hev_libs() {
  case "$HEV_MODE" in
    skip)
      log "skip hev-socks5-tunnel (.so)"
      printf '%s' "-"
      ;;
    compile)
      compile_hev_from_ndk
      printf '%s' "ndk-build"
      ;;
    apk)
      extract_hev_from_apk
      ;;
    auto)
      if [[ -n "${NDK_HOME:-}" && -d "$NDK_HOME" && -f "$V2RAYNG_DIR/compile-hevtun.sh" ]]; then
        compile_hev_from_ndk
        printf '%s' "ndk-build"
      else
        log "NDK_HOME not set; fallback to extract from latest v2rayNG APK"
        extract_hev_from_apk
      fi
      ;;
    *)
      die "unknown --hev mode: $HEV_MODE"
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) LIBV2RAY_TAG="${2:?missing tag}"; shift 2 ;;
    --libv2ray) LIBV2RAY_MODE="${2:?missing libv2ray mode}"; shift 2 ;;
    --hev) HEV_MODE="${2:?missing hev mode}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (use --help)" ;;
  esac
done

command -v curl >/dev/null || die "curl is required"
command -v python3 >/dev/null || die "python3 is required"
command -v unzip >/dev/null || die "unzip is required"

mkdir -p "$LIBS_DIR"
log "target: $LIBS_DIR"

libv2ray_tag=""
v2rayng_tag=""
sync_date="$(date +%Y-%m-%d)"

if [[ "$LIBV2RAY_MODE" == "skip" ]]; then
  log "skip libv2ray.aar download"
  libv2ray_tag="existing"
  [[ -s "$LIBS_DIR/libv2ray.aar" ]] || die "missing $LIBS_DIR/libv2ray.aar (remove --libv2ray skip or download first)"
else
  libv2ray_tag="$(fetch_libv2ray_aar)" || die "failed to fetch libv2ray.aar"
  [[ -f "$LIBS_DIR/libv2ray.aar" ]] || die "missing $LIBS_DIR/libv2ray.aar after download"
  [[ -s "$LIBS_DIR/libv2ray.aar" ]] || die "$LIBS_DIR/libv2ray.aar is empty"
fi

v2rayng_tag="$(fetch_hev_libs)" || die "failed to fetch hev native libs"
verify_hev_libs

update_upstream_md "$libv2ray_tag" "$v2rayng_tag" "$sync_date"

log "done"
log "  libv2ray.aar     : $LIBS_DIR/libv2ray.aar ($libv2ray_tag)"
if [[ "$HEV_MODE" != "skip" ]]; then
  log "  hev-socks5-tunnel: $LIBS_DIR/<abi>/libhev-socks5-tunnel.so"
fi
log "next: cd $ROOT_DIR && ./gradlew :app:assembleDebug"
