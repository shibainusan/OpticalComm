# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview
Android app (Kotlin, Jetpack Compose, CameraX) that sends text between two phones by blinking the camera torch (sender) and reading brightness changes with the camera (receiver). One APK has both "送信" and "受信" tabs. Package: `com.example.opticalcomm`.

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
- **Protocol** (`Protocol.kt`, `Code8b10b.kt`): OOK, `BIT_MS`=100 per bit. Frame = K28.5 K28.5 (20-bit comma preamble; the decoder syncs on its last 18 bits) then LEN, UTF-8 PAYLOAD, CRC8 (poly 0x07 over LEN+PAYLOAD), each byte 8b/10b encoded (running disparity starts RD−, DC-balanced, run length ≤5, invalid symbols/disparity violations are reported as `SymbolError`). Payload ≤ `MAX_PAYLOAD_BYTES`=64. 8b/10b tables were written from memory (only D.x.y and K28.5), validated structurally by tests, not against the official standard; both ends use the same code. Sender idles dark for `LEAD_IDLE_MS` first so the receiver can calibrate its threshold. Changing the format or `BIT_MS` must stay consistent on both ends (same code, so just keep tests passing).
- **Timing is timestamp-based, not frame-count-based**: `SignalSlicer` uses camera `imageInfo.timestamp` and re-aligns its bit grid on every edge, sampling at bit centers. The sender uses absolute `elapsedRealtime` targets to avoid drift.
- **Receiver camera setup** (`LightReceiver`): fixed 30fps, AE/AWB locked 1.5s after start (otherwise auto-exposure cancels the blinking), brightness = whole-frame mean luminance (a top-1%/peak metric saturated under bright ambient lamps and hid the blinking; `SignalSlicer.minContrast` is small accordingly). Point the receiver at the sender, not at room lights.
- Sending and receiving both use the camera, so they must not run at once; the receive camera is only bound while the 受信 tab is composed (`DisposableEffect` in `ui/MainScreen.kt`).
- `Protocol.kt` and `SignalSlicer.kt` are pure Kotlin (no Android deps) so they are covered by JVM tests in `app/src/test/.../ProtocolTest.kt`, including a synthetic 30fps noisy waveform test. Torch/camera code is only verifiable on real devices.
