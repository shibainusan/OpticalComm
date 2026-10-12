# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview
Android app (Kotlin, Jetpack Compose, CameraX) that sends text between two phones by blinking the camera torch (sender) and reading brightness changes with the camera (receiver). One APK has both the send section (top) and receive section (bottom) on a single screen. Package / applicationId: `net.denpa.opticalcomm`.

## Commands
Run from the repo root (Gradle Kotlin DSL, version catalog in `gradle/libs.versions.toml`):
```bash
./gradlew test                                   # JVM unit tests (protocol + signal slicer)
./gradlew test --tests "*ProtocolTest.roundTripAsciiAndJapanese"   # single test
./gradlew assembleDebug                          # build APK
```
- Gradle wrapper (9.8.0) is committed; use `./gradlew`. Needs `JAVA_HOME` set (e.g. `C:\Program Files\Android\Android Studio\jbr`); `test assembleDebug` is verified to pass from a user terminal.
- `local.properties` (sdk.dir) is gitignored. From inside Claude Code sessions on this machine Gradle fails with "Unable to establish loopback connection"; ask the user to run Gradle in their own terminal or Android Studio.

## Architecture
Receive pipeline: `LightReceiver` (CameraX ImageAnalysis → `(timestampNs, luminance)`) → `SignalSlicer` (adaptive threshold + edge resync → bits) → `BitStreamDecoder` (→ `DecodeEvent`) → `ReceiverViewModel` StateFlow → Compose UI. Send pipeline: `SenderViewModel` → `Frame.encode` → `TorchSender`.

Key points spanning files:
- **Protocol** (`Protocol.kt`): OOK, `BIT_MS`=70 per bit, MSB first. Frame = `AA AA 7E LEN PAYLOAD CRC8` (CRC8 poly 0x07 over LEN+PAYLOAD), UTF-8 payload ≤ `MAX_PAYLOAD_BYTES`=64. The decoder syncs on the last 16 bits (`AA 7E`). An invalid LEN (>64) aborts with `PreambleFail`; a CRC mismatch still reports the received text via `CrcError(length, text)` so garbled text can be shown. Sender idles dark for `LEAD_IDLE_MS` first so the receiver can calibrate its threshold. Changing the format or `BIT_MS` must stay consistent on both ends (same code, so just keep tests passing). (8b/10b coding was tried and removed.)
- **Timing is timestamp-based, not frame-count-based**: `SignalSlicer` uses camera `imageInfo.timestamp` and re-aligns its bit grid on every edge (edge time interpolated from luminance between frames, needed at 70ms/bit with 30fps), sampling at bit centers. The sender uses absolute `elapsedRealtime` targets to avoid drift.
- **Receiver camera setup** (`LightReceiver`): fixed 30fps, AE/AWB locked 3s after start (`AE_LOCK_DELAY_MS`, time to aim the camera at the light; otherwise auto-exposure cancels the blinking), and toggleable from the receive section via `LightReceiver.setAeLocked`, exposure compensation of -2 EV (`EXPOSURE_COMP_EV`) to darken ambient light, brightness = mean luminance of the cell of a 32x32 downscale of the analysis image (the analysis resolution varies by device: 720x720 and 1088x1088 seen) whose peak-to-peak swing is largest (small cells because a distant torch covers only a few pixels; the receive section shows this 32x32 image instead of a camera preview, with the tracked cell boxed in red) (`RoiTracker`; starts on the whole-frame mean and switches with hysteresis, calling `SignalSlicer.rebase` on a switch so a bright room does not bury the torch) (a top-1%/peak metric saturated under bright ambient lamps and hid the blinking; `SignalSlicer.minContrast` is small accordingly). Point the receiver at the sender, not at room lights.
- Sending and receiving both use the camera, so they must not run at once; the receive camera is unbound while sending (`ReceiveSection(paused = sending)`, `DisposableEffect(front, paused)` in `ui/MainScreen.kt`) and rebound when sending ends.
- `Protocol.kt` and `SignalSlicer.kt` are pure Kotlin (no Android deps) so they are covered by JVM tests in `app/src/test/.../ProtocolTest.kt`, including a synthetic 30fps noisy waveform test. Torch/camera code is only verifiable on real devices.
