#!/usr/bin/env bash
set -euo pipefail

IMG="${1:?Usage: arda_patch_final_img_cmdline.sh <image.img>}"

EXTRA_ARGS=(
  "video=HDMI-A-1:400x1280@60e"
  "cma=384M"
  "coherent_pool=8M"
  "swiotlb=131072"
  "androidboot.serialno=1000000028c0b59c"
)

TMP="$(mktemp -d)"
LOOP=""

cleanup() {
  set +e
  sudo umount "$TMP/boot" 2>/dev/null || true
  if [ -n "$LOOP" ]; then
    sudo losetup -d "$LOOP" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/boot"

echo "=== attach image ==="
LOOP="$(sudo losetup --find --partscan --show "$IMG")"
BOOT_DEV="${LOOP}p1"

sleep 1

if [ ! -b "$BOOT_DEV" ]; then
  echo "ERROR: boot partition not found: $BOOT_DEV"
  lsblk "$LOOP" || true
  exit 1
fi

echo "=== mount boot partition: $BOOT_DEV ==="
sudo mount -o rw "$BOOT_DEV" "$TMP/boot"

if [ ! -f "$TMP/boot/cmdline.txt" ]; then
  echo "ERROR: cmdline.txt not found in image boot partition"
  sudo ls -la "$TMP/boot"
  exit 1
fi

sudo cp -av "$TMP/boot/cmdline.txt" "$TMP/boot/cmdline.txt.bak-arda-$(date +%Y%m%d_%H%M%S)"

sudo python3 - "$TMP/boot/cmdline.txt" "${EXTRA_ARGS[@]}" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
extra = sys.argv[2:]

remove_prefixes = (
    "video=HDMI-A-1:",
    "cma=",
    "coherent_pool=",
    "swiotlb=",
    "androidboot.serialno=",
)

old = p.read_text().strip().replace("\n", " ")
tokens = old.split()

tokens = [
    t for t in tokens
    if not any(t.startswith(prefix) for prefix in remove_prefixes)
]

tokens.extend(extra)

p.write_text(" ".join(tokens) + "\n")

print("=== final cmdline.txt ===")
print(p.read_text())
PY

sync
sudo umount "$TMP/boot"
sync

echo "=== cmdline patch OK ==="
