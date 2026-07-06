#!/usr/bin/env bash
set -euo pipefail

AOSP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$AOSP"

./device/brcm/rpi4/mkimg.sh

IMG="$(ls -t out/target/product/rpi4/RaspberryVanillaAOSP16-*.img | head -1)"

echo "=== patch cmdline inside final image ==="
vendor/arda/tools/arda_patch_final_img_cmdline.sh "$IMG"

echo "=== final patched image ==="
ls -lh "$IMG"
