# Camera latency measurements

Record measured values here. Do not replace missing measurements with estimates.

## Test environment

- Date: 2026-09-10
- Android device: Standard_10inch_A2
- App path: custom RTSP/RTP over UDP to MediaCodec, rendered through GLSurfaceView
- Camera 1: `rtsp://192.168.144.25:8554/main.264`
- Camera 2: `rtsp://192.168.144.26:8554/main.264`
- App instrumentation: measurement overlays disabled in the current build

## Results

| Test | Transport | Resolution | Codec | FPS | DEC | Clock delta | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Tablet GL baseline | Radio to tablet | Pending | H.264 / AVC | 28-29 | CAM 1: 27 ms shown, 32 ms log average; CAM 2: 29 ms shown, 33 ms log average | 175-180 ms | User-observed physical stopwatch comparison |
| Current pan/zoom build | Radio to tablet | 1280x720 | H.264 / AVC | 28-29 | Hidden | 160-170 ms | User-observed physical stopwatch comparison; zoom level not recorded |
| Camera only | Direct Ethernet to laptop | Pending | Pending | Pending | N/A | Pending | Blocked: camera unreachable; GStreamer unavailable |
| Camera through radio | Radio to laptop | Pending | Pending | Pending | N/A | Pending | Blocked: camera unreachable; GStreamer unavailable |
| Tablet at 720p30 | Radio to tablet | 1280x720 target | Pending | 30 target | Pending | Pending | Not started |

## Required comparisons

- Radio cost = radio-to-laptop clock delta minus direct-Ethernet clock delta.
- GLSurfaceView result = current tablet clock delta compared with the last equivalent tablet build.
- Change only one camera setting before repeating a measurement.

## Evidence

- Tablet capture: `tablet-latency-test.png`, captured 2026-09-10.
- The capture showed CAM 1 at `DEC 27ms` and CAM 2 at `DEC 29ms`.
- Process logs reported H.264/AVC, 28-29 fps, 32-33 ms average decode latency, and 41-44 ms average submit-to-surface latency.
- The phone showed a stopwatch while the tablet overlay showed wall-clock seconds. Those different clocks cannot be subtracted, so no end-to-end number was recorded.
- The user subsequently performed the physical stopwatch comparison and observed approximately 175-180 ms end-to-end latency.
- With the current pan/zoom build, the user observed approximately 160-170 ms end-to-end latency.
