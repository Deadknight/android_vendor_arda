#!/system/bin/sh
# Legacy filename retained to avoid product/module churn.
# Old Option-A allocator bind is retired; this now applies H264 SF policy.
LOG=/data/local/tmp/arda-h264-sf-tune.log
exec >>"$LOG" 2>&1

echo "ARDA_H264_SF_TUNE_V1 start $(date 2>/dev/null || true)"
setprop vendor.hwc.drm.enable_virtual_display 1

i=0
while [ "$i" -lt 30 ]; do
    i=$((i + 1))
    MODE_ID="$(
        dumpsys SurfaceFlinger 2>/dev/null |
        sed -n 's/.*activeMode={id=\([0-9][0-9]*\),.*/\1/p' |
        head -n 1
    )"

    case "$MODE_ID" in
        ''|*[!0-9]*) sleep 1; continue ;;
    esac

    service call SurfaceFlinger 1021 i32 1 >/dev/null 2>&1 || true
    service call SurfaceFlinger 1035 i32 "$MODE_ID" >/dev/null 2>&1 || true
    sleep 1

    RATE="$(
        dumpsys SurfaceFlinger 2>/dev/null |
        sed -n 's/^[[:space:]]*renderRate=\([0-9.][0-9.]*\) Hz.*/\1/p' |
        head -n 1
    )"

    case "$RATE" in
        59.*|60.*|61.*)
            echo "ARDA_H264_SF_TUNE_V1 PASS mode_id=$MODE_ID render_rate=$RATE attempt=$i"
            exit 0
            ;;
    esac
done

echo "ARDA_H264_SF_TUNE_V1 FAIL no_60hz_policy"
exit 1
