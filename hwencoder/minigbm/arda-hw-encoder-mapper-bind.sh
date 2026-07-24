#!/system/bin/sh
set -u

SOURCE="/vendor/lib64/egl/arda/libminigbm_gralloc_gbm_mesa.so"
TARGET="/apex/com.android.hardware.graphics.allocator.minigbm_gbm_mesa/lib64/libminigbm_gralloc_gbm_mesa.so"
LOG="/data/local/tmp/arda-hw-encoder-mapper-bind.log"
EXPECTED_LABEL="u:object_r:same_process_hal_file:s0"

mkdir -p /data/local/tmp
: > "$LOG"
chmod 0600 "$LOG"

log_marker() {
    marker="$1"
    echo "$marker" >> "$LOG"
    /system/bin/log -t ARDA_HW_ENCODER "$marker" 2>/dev/null || true
}

label_of() {
    ls -lZ "$1" 2>/dev/null |
    awk '{for (i = 1; i <= NF; i++) if ($i ~ /^u:object_r:/) {print $i; exit}}'
}

if [ "$(getprop sys.boot_completed)" != "1" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=SKIP_BOOT_INCOMPLETE"
    exit 0
fi

if [ ! -s "$SOURCE" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_SOURCE_MISSING path=$SOURCE"
    exit 2
fi

if [ ! -s "$TARGET" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_TARGET_MISSING path=$TARGET"
    exit 2
fi

restorecon -F "$SOURCE" 2>/dev/null || true

source_label="$(label_of "$SOURCE")"
target_label_before="$(label_of "$TARGET")"

if [ "$source_label" != "$EXPECTED_LABEL" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_SOURCE_LABEL label=$source_label"
    exit 2
fi

if [ "$target_label_before" != "$EXPECTED_LABEL" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_TARGET_LABEL label=$target_label_before"
    exit 2
fi

source_hash="$(sha256sum "$SOURCE" | awk '{print $1}')"
target_hash_before="$(sha256sum "$TARGET" | awk '{print $1}')"

if [ "$source_hash" = "$target_hash_before" ]; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=PASS_ALREADY_ACTIVE"
    exit 0
fi

codec_pid="$(getprop init.svc_debug_pid.vendor.media.c2.v4l2)"
if [ -n "$codec_pid" ] &&
   [ -r "/proc/$codec_pid/maps" ] &&
   grep -E      'libminigbm_gralloc_gbm_mesa|mapper\.minigbm_gbm_mesa'      "/proc/$codec_pid/maps" >/dev/null 2>&1; then
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_CODEC_MAPPER_ALREADY_MAPPED pid=$codec_pid"
    exit 2
fi

umount "$TARGET" 2>/dev/null || true
mount --bind "$SOURCE" "$TARGET"

target_hash_after="$(sha256sum "$TARGET" | awk '{print $1}')"
target_label_after="$(label_of "$TARGET")"

if [ "$target_hash_after" != "$source_hash" ]; then
    umount "$TARGET" 2>/dev/null || true
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_HASH_MISMATCH"
    exit 2
fi

if [ "$target_label_after" != "$EXPECTED_LABEL" ]; then
    umount "$TARGET" 2>/dev/null || true
    log_marker "ARDA_HW_ENCODER_LATE_BIND=FAIL_TARGET_LABEL_AFTER label=$target_label_after"
    exit 2
fi

sleep 3

if [ "$(getprop init.svc.surfaceflinger)" != "running" ] ||
   ! /system/bin/dumpsys SurfaceFlinger --list >/dev/null 2>&1; then
    umount "$TARGET" 2>/dev/null || true
    log_marker "ARDA_HW_ENCODER_LATE_BIND=ROLLBACK_SURFACEFLINGER_UNHEALTHY"
    exit 2
fi

log_marker "ARDA_HW_ENCODER_LATE_BIND=PASS source=$SOURCE target=$TARGET label=$target_label_after"
exit 0
