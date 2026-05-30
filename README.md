# flutter_deepar

A Flutter plugin for the [DeepAR](https://www.deepar.ai/) augmented reality SDK. Provides camera capture, AR effect loading/switching, and a stream of AR-processed frames ready for live streaming, recording, or preview.

## Features

- 📸 Camera capture via Camera2 (Android) and AVFoundation (iOS)
- 🎭 Load/unload AR effects (`.deepar` files)
- 🔄 Switch between front and back camera
- 📡 Stream AR-processed frames as byte arrays (RGBA on Android, BGRA on iOS)
- ⚡ Off-screen rendering — no visible view required
- 🎯 Production-tested YUV→NV21 conversion for correct colors across all Android devices

## Getting Started

### 1. Get a DeepAR License Key

Sign up at [developer.deepar.ai](https://developer.deepar.ai/) and create a project to get your license keys (one per platform).

### 2. Install

```yaml
dependencies:
  flutter_deepar: ^0.1.0
```

### 3. Platform Setup

#### Android

Add camera permission to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

The DeepAR SDK is pulled automatically via Maven. No `.aar` file needed.

#### iOS

Add camera permission to your `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera access is required for AR effects</string>
```

The DeepAR SDK is pulled automatically via CocoaPods. No `.xcframework` file needed.

### 4. Usage

```dart
import 'package:flutter_deepar/flutter_deepar.dart';

final deepar = DeepARController();

// Initialize with your platform-specific license key
await deepar.initialize(licenseKey: 'YOUR_DEEPAR_LICENSE_KEY');

// Start camera capture
await deepar.startCapture();

// Listen to processed frames
deepar.frameStream.listen((frame) {
  // frame.data — Uint8List of pixel data
  // frame.width, frame.height — dimensions
  // frame.format — 'rgba' or 'bgra'
  // frame.timestamp — milliseconds since epoch
});

// Load an AR effect
await deepar.loadEffect('effects/my_filter.deepar');

// Clear effect
await deepar.clearEffect();

// Switch camera
await deepar.switchCamera();

// Cleanup when done
await deepar.dispose();
```

## Agora Integration

For direct integration with Agora live streaming, see the companion package [`flutter_deepar_agora`](https://pub.dev/packages/flutter_deepar_agora), which provides a one-liner bridge to pipe AR-processed frames into Agora's external video source.

## Effect Files

Place `.deepar` effect files in your app's assets directory and declare them in `pubspec.yaml`:

```yaml
flutter:
  assets:
    - assets/effects/
```

You can get effects from:
- [DeepAR Free Filter Pack](https://docs.deepar.ai/deepar-sdk/filters#free-effects-pack-content)
- [DeepAR Asset Store](https://www.store.deepar.ai/)
- [DeepAR Studio](https://www.deepar.ai/creator-studio) (create your own)

## License

MIT License. See [LICENSE](LICENSE) for details.

DeepAR SDK is subject to [DeepAR's licensing terms](https://developer.deepar.ai/customer-agreement).
