#!/bin/bash
set -euo pipefail

AOSP_ROOT="${AOSP_ROOT:-/home/edo/aosp-rpi-aaos16}"
cd "$AOSP_ROOT"

SRC=vendor/arda/docs/cpu_boost/arda-connectivity-supervisor.cpu-boost-v1.sh
DST=vendor/arda/ardaplatform/arda-connectivity-supervisor.sh

test -f "$SRC"
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
chmod 0755 "$DST"
/bin/bash -n "$DST"

echo "RESTORED_SUPERVISOR_SHA256=$(sha256sum "$DST" | awk '{print $1}')"
