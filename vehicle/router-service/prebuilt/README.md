# Staged router prebuilt

`arda-senderd` is a generated Android ARM64 build artifact and is intentionally not committed.

Run `scripts/stage-arda-vehicle-router-prebuilt.sh` after the Docker Android Rust build. The staged binary is then copied with `android/arda-vehicle` into the frozen AOSP tree for the Phase 2.2 system_ext integration build.
