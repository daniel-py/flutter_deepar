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
