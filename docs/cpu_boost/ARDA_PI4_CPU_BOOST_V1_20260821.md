# ARDA Pi 4 CPU Boost V1 — 2026-08-21

Validated hardware:
- Raspberry Pi 4 Model B Rev 1.2
- CPU revision c03112

Runtime policy:
- firmware hard ceiling: 1900 MHz
- default runtime maximum: 1500 MHz
- governor: ondemand
- offset range: -200..+400 MHz
- step: 50 MHz
- Settings.Global key: arda_cpu_boost_mhz
- >=78 C: clamp positive boost to 1500 MHz
- <=72 C: restore requested profile

Validation:
- 1900 MHz exposed by cpufreq-dt
- 180 s four-core stress PASS
- 80/80 samples at 1900 MHz
- max observed stress temperature 49.2 C
- critical kernel warnings: 0
- DHU real workload PASS
- UI +400 -> 1900 PASS
- UI 0 -> 1500 PASS

Supervisor:
- build staging: vendor/arda/ardaplatform/arda-connectivity-supervisor.sh
- canonical tracked snapshot: vendor/arda/docs/cpu_boost/arda-connectivity-supervisor.cpu-boost-v1.sh
- SHA256: 65ce4cdbad9272b8d15afc6f54a4fea0be948c4b7fa54c743749737a0e20eea3

Boot config:
- canonical AOSP source: device/brcm/rpi4/boot/config.txt
- arm_freq=1900
- force_turbo absent
- manual over_voltage absent
- SHA256: 6b4518021c94d21b7dddbd0957ebf4376acc8bdf84792745d84f1db912a1d004

Important:
- Firmware ceiling 1900 MHz is NOT the default runtime policy.
- Missing/null/0 request means 1500 MHz.
- No arda-connectivityd or arda-senderd Rust binary change is required.
- No H264 production source change is part of this feature.
