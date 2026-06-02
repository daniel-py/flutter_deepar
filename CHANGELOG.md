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
