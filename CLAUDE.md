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
- Gradle wrapper (8.9) is committed; use `./gradlew`.
- `local.properties` (sdk.dir) is gitignored. On this machine the JDK is Android Studio's `jbr`; no system Java/Gradle. Gradle failed here with "Unable to establish loopback connection", so builds have not been verified locally.

## Architecture
Receive pipeline: `LightReceiver` (CameraX ImageAnalysis → `(timestampNs, luminance)`) → `SignalSlicer` (adaptive threshold + edge resync → bits) → `BitStreamDecoder` (→ `DecodeEvent`) → `ReceiverViewModel` StateFlow → Compose UI. Send pipeline: `SenderViewModel` → `Frame.encode` → `TorchSender`.

Key points spanning files:
- **Protocol** (`Protocol.kt`): OOK, `BIT_MS`=200 per bit, MSB first. Frame = `AA AA 7E LEN PAYLOAD CRC8` (CRC8 poly 0x07 over LEN+PAYLOAD), UTF-8 payload ≤ `MAX_PAYLOAD_BYTES`=64. Sender idles dark for `LEAD_IDLE_MS` first so the receiver can calibrate its threshold. Changing the format or `BIT_MS` must stay consistent on both ends (same code, so just keep tests passing).
- **Timing is timestamp-based, not frame-count-based**: `SignalSlicer` uses camera `imageInfo.timestamp` and re-aligns its bit grid on every edge, sampling at bit centers. The sender uses absolute `elapsedRealtime` targets to avoid drift.
- **Receiver camera setup** (`LightReceiver`): fixed 30fps, AE/AWB locked 1.5s after start (otherwise auto-exposure cancels the blinking), brightness = mean of top 1% luminance in the center ROI.
- Sending and receiving both use the camera, so they must not run at once; the receive camera is only bound while the 受信 tab is composed (`DisposableEffect` in `ui/MainScreen.kt`).
- `Protocol.kt` and `SignalSlicer.kt` are pure Kotlin (no Android deps) so they are covered by JVM tests in `app/src/test/.../ProtocolTest.kt`, including a synthetic 30fps noisy waveform test. Torch/camera code is only verifiable on real devices.
