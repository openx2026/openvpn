#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT_DIR/.." && pwd)"
V2RAYNG_ROOT="${V2RAYNG_ROOT:-$REPO_ROOT/v2rayNG}"
V2RAYNG_APP="${V2RAYNG_APP:-$V2RAYNG_ROOT/V2rayNG/app/src/main}"
ENGINE_SRC="$ROOT_DIR/vpn-engine/src/main"
REF="${1:-}"

if [[ ! -d "$V2RAYNG_APP/java/com/v2ray/ang" ]]; then
  echo "v2rayNG source not found: $V2RAYNG_APP" >&2
  echo "Initialize submodule from repo root:" >&2
  echo "  git submodule update --init --recursive v2rayNG" >&2
  exit 1
fi

if [[ -n "$REF" ]]; then
  if [[ "$REF" == --ref ]]; then REF="${2:-}"; fi
  if [[ -n "$REF" ]]; then
    git -C "$V2RAYNG_ROOT" checkout "$REF" --quiet 2>/dev/null || git -C "$V2RAYNG_ROOT" fetch origin && git -C "$V2RAYNG_ROOT" checkout "$REF" --quiet
    echo "Checked out v2rayNG at $REF"
  fi
fi

ENGINE_DIRS=(core service handler fmt dto enums util contracts extension receiver)
JAVA_BASE="$ENGINE_SRC/java/com/v2ray/ang"

mkdir -p "$JAVA_BASE"

for d in "${ENGINE_DIRS[@]}"; do
  rm -rf "$JAVA_BASE/$d"
  rsync -a "$V2RAYNG_APP/java/com/v2ray/ang/$d/" "$JAVA_BASE/$d/"
done
cp "$V2RAYNG_APP/java/com/v2ray/ang/AppConfig.kt" "$JAVA_BASE/"

rsync -a --delete "$V2RAYNG_APP/res/" "$ENGINE_SRC/res/"
rsync -a --delete "$V2RAYNG_APP/assets/" "$ENGINE_SRC/assets/"

apply_patches() {
  local nm="$JAVA_BASE/handler/NotificationManager.kt"
  if [[ -f "$nm" ]]; then
    perl -0777 -i -pe 's/import com\.v2ray\.ang\.ui\.MainActivity\n//g' "$nm"
    perl -i -pe 's/Intent\(service, MainActivity::class\.java\)/service.packageManager.getLaunchIntentForPackage(service.packageName) ?: Intent(service, service.javaClass)/g' "$nm"
  fi

  local ac="$JAVA_BASE/AppConfig.kt"
  if [[ -f "$ac" ]]; then
    perl -0777 -i -pe 's/\/\*\* The application\x27s package name\. \*\/\n    const val ANG_PACKAGE = BuildConfig\.APPLICATION_ID\n    const val TAG = BuildConfig\.APPLICATION_ID/\@Volatile\n    var hostPackageName: String = BuildConfig.APPLICATION_ID\n\n    val ANG_PACKAGE: String get() = hostPackageName\n    val TAG: String get() = hostPackageName/s' "$ac"
    perl -i -pe 's/const val (BROADCAST_ACTION_\w+) = "\$ANG_PACKAGE/val \1 get() = "\$\{ANG_PACKAGE\}/g' "$ac"
    perl -i -pe 's/val (BROADCAST_ACTION_\w+) get\(\) = "\$\{ANG_PACKAGE\}\.([^"]+)"/val \1: String get() = "\$\{ANG_PACKAGE\}.\2"/g' "$ac"
    if ! grep -q "import kotlin.jvm.Volatile" "$ac"; then
      perl -i -pe 's/^(package com\.v2ray\.ang\n)/\1\nimport kotlin.jvm.Volatile\n/' "$ac"
    fi
  fi
}

apply_local_overlay() {
  local overlay="$ROOT_DIR/vpn-engine/local-overlay/java"
  if [[ ! -d "$overlay" ]]; then
    echo "local overlay missing: $overlay" >&2
    exit 1
  fi
  rsync -a "$overlay/" "$ENGINE_SRC/java/"

  local csm="$JAVA_BASE/core/CoreServiceManager.kt"
  if [[ -f "$csm" ]] && ! grep -q 'fun measureCurrentDelay' "$csm"; then
    perl -0777 -i -pe 's/(fun getRunningServerName\(\) = currentConfig\?\.remarks\.orEmpty\(\)\n)/$1\n    \/**\n     * Measures current VPN connection delay in milliseconds.\n     * @return Delay in ms, or -1 if VPN is not running or test failed.\n     *\/\n    fun measureCurrentDelay(): Long {\n        if (coreController.isRunning == false) {\n            return -1L\n        }\n        return try {\n            var time = coreController.measureDelay(SettingsManager.getDelayTestUrl())\n            if (time < 0L) {\n                time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))\n            }\n            if (time >= 0L) {\n                MmkvManager.getSelectServer()?.let { guid ->\n                    MmkvManager.encodeServerTestDelayMillis(guid, time)\n                }\n            }\n            time\n        } catch (e: Exception) {\n            LogUtil.e(AppConfig.TAG, "measureCurrentDelay failed", e)\n            -1L\n        }\n    }\n\n/s' "$csm"
  fi
}

apply_patches
apply_local_overlay

sync_engine_version_name() {
  local ng_build="$V2RAYNG_ROOT/V2rayNG/app/build.gradle.kts"
  local engine_build="$ROOT_DIR/vpn-engine/build.gradle.kts"
  if [[ ! -f "$ng_build" ]]; then
    echo "v2rayNG build file not found: $ng_build" >&2
    exit 1
  fi
  if [[ ! -f "$engine_build" ]]; then
    echo "vpn-engine build file not found: $engine_build" >&2
    exit 1
  fi
  local version_name
  version_name="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$ng_build" | head -1 | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')"
  if [[ -z "$version_name" ]]; then
    echo "failed to parse versionName from $ng_build" >&2
    exit 1
  fi
  perl -i -pe 's/buildConfigField\("String", "VERSION_NAME", "\\"[^"]*\\""\)/buildConfigField("String", "VERSION_NAME", "\\"'"$version_name"'\\"")/' "$engine_build"
  echo "vpn-engine VERSION_NAME -> $version_name"
}

sync_engine_version_name

V2RAY_COMMIT="$(git -C "$V2RAYNG_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
V2RAY_VERSION="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$V2RAYNG_ROOT/V2rayNG/app/build.gradle.kts" | head -1 | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')"
SYNC_DATE="$(date +%Y-%m-%d)"
perl -i -pe "s/待填写/$V2RAY_COMMIT/ if $.==5" "$ROOT_DIR/vendor/UPSTREAM.md" 2>/dev/null || true
if [[ -n "$V2RAY_VERSION" ]] && [[ -f "$ROOT_DIR/vendor/UPSTREAM.md" ]]; then
  perl -i -pe 's/^\| libs-refresh \|.*$/| libs-refresh | '"$V2RAY_VERSION"' | v26.6.2 | apk-extract | '"$SYNC_DATE"' |/ if $.==5' "$ROOT_DIR/vendor/UPSTREAM.md" 2>/dev/null || true
fi

echo "Synced vpn-engine from v2rayNG @ $V2RAY_COMMIT ($V2RAY_VERSION, $SYNC_DATE)"
