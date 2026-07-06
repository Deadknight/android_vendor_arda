# ARDA platform input + Raspberry Pi V4L2 encoder compatibility

## Input

`ArdaInputBridge` is a platform-signed, privileged, persistent system_ext app.
It is installed on `/system_ext` so it can compile against platform hidden APIs, and owns `android.permission.INJECT_EVENTS` and listens only on
`127.0.0.1:5276`.

The existing sender-side AAP input decoder and coordinate mapper remain the
source of touch/key events. The bridge accepts the existing ARDA line protocol.

## V4L2 encoder

The Raspberry Pi product already includes `com.android.hardware.media.c2.v4l2`
and `media_codecs_v4l2_c2_video.xml`.

`V4L2EncodeComponent::mFramerate` starts at zero. The first frame therefore
calls `V4L2Encoder::setFramerate()`. The upstream implementation treats every
`VIDIOC_S_PARM` failure as fatal, which reports `C2_CORRUPTED` and can surface
through MediaCodec as `INVALID_OPERATION (-38)` before any NAL is produced.

This patch treats only `EINVAL` and `ENOTTY` from `VIDIOC_S_PARM` as
"unsupported ioctl" and continues using C2 frame timestamps for cadence. Other
errors stay fatal.
