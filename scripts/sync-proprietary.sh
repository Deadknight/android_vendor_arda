#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")" \
        && pwd
)"

ARDA_ROOT="$(
    cd "$SCRIPT_DIR/.." \
        && pwd
)"

PROP_REPO="${ARDA_PROPRIETARY_REPO:-Deadknight/arda-aaos-proprietary}"
PROP_TAG="${ARDA_PROPRIETARY_TAG:-gas-payload-2026-07-06}"
ASSET_NAME="arda-aaos-proprietary-payload.tar"

for CMD in gh tar sha256sum rsync; do
    if ! command -v "$CMD" >/dev/null 2>&1; then
        echo "ERROR: required command missing: $CMD"
        exit 1
    fi
done

TMP="$(mktemp -d /tmp/arda-proprietary.XXXXXX)"

cleanup() {
    rm -rf "$TMP"
}

trap cleanup EXIT

echo "PROP_REPO=$PROP_REPO"
echo "PROP_TAG=$PROP_TAG"

echo
echo "=== DOWNLOAD PRIVATE PAYLOAD ==="

gh release download "$PROP_TAG" \
    --repo "$PROP_REPO" \
    --pattern "$ASSET_NAME" \
    --dir "$TMP"

echo
echo "=== EXTRACT ==="

tar -C "$TMP" \
    -xf "$TMP/$ASSET_NAME"

echo
echo "=== VERIFY SHA256 ==="

(
    cd "$TMP"
    sha256sum -c SHA256SUMS
)

echo
echo "=== SYNC GAS PAYLOAD ==="

rm -rf "$ARDA_ROOT/gas_car/files"
mkdir -p "$ARDA_ROOT/gas_car/files"

rsync -a \
    "$TMP/payload/gas_car/files/" \
    "$ARDA_ROOT/gas_car/files/"

echo
echo "=== SYNC GOLDEN SPLITS ==="

mkdir -p "$ARDA_ROOT/golden_splits"

find "$ARDA_ROOT/golden_splits" \
    -type f \
    -name '*.apk' \
    -delete

rsync -a \
    "$TMP/payload/golden_splits/" \
    "$ARDA_ROOT/golden_splits/"

echo
echo "ARDA_PROPRIETARY_SYNC_OK=YES"
