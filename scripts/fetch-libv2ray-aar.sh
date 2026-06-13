#!/usr/bin/env bash
set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/update-vpn-engine-libs.sh" --libv2ray fetch --hev skip "$@"
