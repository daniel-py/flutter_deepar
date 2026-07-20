## 0.1.8

* Fix (Android): switching cameras mid-session no longer freezes the preview/stream on a single frame. DeepAR's offscreen pipeline keys itself to the input orientation of the first frame it receives; the plugin used to flip the `receiveFrame` rotation argument between 270 (front) and 90 (back) on switch, after which `frameAvailable` stopped firing for the switched-to camera on every Android device. The rotation/mirror arguments are now latched for the engine's lifetime, and frames from the non-initial lens are horizontally mirrored inside the NV21 buffer instead — which composes with the latched arguments to produce exactly the per-lens upright/mirroring output as before. iOS never had the bug (its capture connection is locked to portrait) and is unchanged.
* Fix (Android): `startCapture`/`switchCamera` now resolve when the camera capture session is actually configured (or fail with a real error code: `NO_PERMISSION`, `NO_CAMERA`, `CAMERA_FAILED`, `CONFIGURE_FAILED`, ...), instead of reporting success before the asynchronous open even ran. Transient open failures are retried once after 300ms; a 6s safety timeout still resolves optimistically so Dart can never hang.
* Fix (Android): late Camera2 callbacks from a superseded session (e.g. an evicted device's `onDisconnected` arriving after a switch) are now generation-guarded and can no longer null out the new session's state. Each close call is individually guarded so one failure can't leak the device or reader, and the camera fields are `@Volatile` for cross-thread visibility.
* Fix (Android): removed a double-close of the DeepAR output `Image` — the SDK closes it itself after `frameAvailable` returns (verified in 5.6.4 bytecode).
* Feature: `startCapture({bool? front})` explicitly selects the camera facing at capture start (`null` keeps the current facing, preserving it across app-lifecycle restarts). Previously the facing silently persisted across sessions, so a stream ended on the back camera made the next one start on the back camera. Destroying the engine now also resets facing to front.
* Feature: `switchCamera()` returns the resulting facing (`true` = front) on both platforms so apps can sync UI state from truth instead of blind-toggling. (Pre-0.1.8 natives always returned `true`; treat the value as authoritative only on >= 0.1.8.)

## 0.1.7

* Fix (iOS): frames are now delivered with tightly packed rows. `CVPixelBuffer` pads each row to a 64-byte alignment; the plugin previously shipped the padding while reporting the unpadded width, which sheared the image diagonally for any output width where `width * 4` is not 64-aligned (e.g. the 540x960 configurable resolution added in 0.1.6 — the 720x1280 default was 64-aligned by luck and unaffected). Android already stripped row padding and is unchanged.

## 0.1.6

* Feature: `initialize()` accepts optional `outputWidth`/`outputHeight` (default 720x1280) to configure the DeepAR offscreen render resolution on both platforms. Matching them to your video encoder's resolution avoids downstream rescaling and shrinks per-frame transfer cost (e.g. 540x960 cuts RGBA frame size ~45% vs the default). Fully backward compatible.

## 0.1.5

* Perf (Android): Frame dropping (backpressure) on both pipeline stages. At most one frame is now in flight for DeepAR ingest and one for EventChannel delivery; when the main thread falls behind, newer camera frames are dropped instead of queued. Previously, main-thread posts accumulated without bound under load, so latency and memory grew until the stream visibly lagged. This matches iOS, which always had `alwaysDiscardsLateVideoFrames` and never showed the problem.
* Perf (Android): All per-frame buffers (NV21 scratch array, DeepAR direct ByteBuffers, RGBA output array) are now allocated once and reused instead of freshly allocated per frame. At 30fps the old code churned ~200MB/s of garbage (≈6.5MB per frame), keeping ART's GC constantly running. The two rotating direct buffers for `receiveFrame` mirror DeepAR's own Android sample.
* Perf (Android): YUV→NV21 chroma conversion now uses a single bulk copy on the common interleaved layout (`pixelStride == 2`), guarded by an MLKit-style plane-aliasing check performed once per camera session, with a bulk-row fallback for planar/padded/NV12-ordered layouts. The old per-pixel loop performed ~460k bounds-checked `ByteBuffer.get()` calls per frame on the camera thread.
* No iOS or Dart API changes.

## 0.1.4

* Fix (Android): `receiveFrame` is called on the main thread again. DeepAR is single-threaded and requires every call on the thread it was initialized on; 0.1.3 moved `receiveFrame` to the background camera thread, which made DeepAR reject every frame ("Method called from the thread that DeepAR was not initialized in") and broke the preview in debug too. Only the heavy YUV→NV21 conversion now runs on the background thread; the converted buffer is handed back to the main thread for `receiveFrame`.

## 0.1.3

* Fix (Android): Camera capture and the YUV→NV21 conversion now run on a dedicated background thread instead of the main/UI thread. On the main thread they saturated it in release (AOT) builds, starving the host's preview surface compositor and producing a blank (white/black) preview that only reproduced in release. iOS already used a background queue.
* Fix (Android): Marked cross-thread state (`isCapturing`, `isSdkInitialized`, `frameEventSink`) as `@Volatile` so the release-mode ART optimizer cannot cache stale values on worker threads.
* Fix (Android): Guarded `ImageReader.acquireLatestImage()` against a reader-closed race now that the listener runs off the main thread.

## 0.1.2

* Fix: Resolved a race condition on iOS and Android where `startCapture` could crash in release mode due to asynchronous engine initialization.
* Fix: Added ProGuard rules to prevent DeepAR classes from being stripped by R8 during release builds on Android.

## 0.1.1

* Fix: DeepAR Maven repository now injects into root project so consuming apps can resolve `ai.deepar.ar:DeepAR` without manual repo setup

## 0.1.0

* Initial release
* Camera capture via Camera2 (Android) and AVFoundation (iOS)
* AR effect loading/switching
* Processed frame output via EventChannel stream
* Front/back camera switching
* Off-screen rendering support
