import 'dart:async';
import 'package:flutter/services.dart';
import 'deepar_frame.dart';

/// Controller for the DeepAR augmented reality engine.
///
/// Provides camera capture, AR effect loading/unloading, camera switching,
/// and a stream of AR-processed frames via [frameStream].
///
/// Usage:
/// ```dart
/// final deepar = DeepARController();
/// await deepar.initialize(licenseKey: 'YOUR_KEY');
/// await deepar.startCapture();
/// deepar.frameStream.listen((frame) {
///   // Use frame.data, frame.width, frame.height
/// });
/// await deepar.loadEffect('effects/my_filter.deepar');
/// ```
class DeepARController {
  static const _channel = MethodChannel('flutter_deepar');
  static const _frameChannel = EventChannel('flutter_deepar/frames');

  StreamSubscription? _frameSub;
  final _frameController = StreamController<DeepARFrame>.broadcast();
  bool _initialized = false;

  /// Stream of AR-processed frames.
  ///
  /// Frames are RGBA on Android and BGRA on iOS. Use [DeepARFrame.format]
  /// to determine the pixel format.
  Stream<DeepARFrame> get frameStream => _frameController.stream;

  /// Whether the DeepAR SDK has been initialized.
  bool get isInitialized => _initialized;

  /// Initialize the DeepAR engine with your license key.
  ///
  /// Must be called before any other method. Returns `true` if successful.
  /// Get your license key from https://developer.deepar.ai
  ///
  /// [outputWidth]/[outputHeight] set the offscreen render resolution of the
  /// processed frames delivered on [frameStream] (portrait, width < height).
  /// Match them to your video encoder's resolution to avoid downstream
  /// rescaling and to shrink per-frame transfer cost — e.g. 540x960 for a
  /// 540p pipeline. Defaults to 720x1280.
  Future<bool> initialize({
    required String licenseKey,
    int outputWidth = 720,
    int outputHeight = 1280,
  }) async {
    final result = await _channel.invokeMethod<bool>(
      'initializeDeepAR',
      {
        'licenseKey': licenseKey,
        'outputWidth': outputWidth,
        'outputHeight': outputHeight,
      },
    );
    _initialized = result ?? false;
    return _initialized;
  }

  /// Start camera capture and begin receiving processed frames via [frameStream].
  ///
  /// [front] explicitly selects the camera facing: `true` opens the front
  /// camera, `false` the back camera, and `null` (default) keeps whatever
  /// facing the plugin last used. Pass an explicit value at session starts
  /// that must not inherit a previous session's facing (e.g. `front: true`
  /// so every new live stream begins on the selfie camera), and leave it
  /// `null` for mid-session restarts like app-lifecycle resume.
  ///
  /// Requires flutter_deepar >= 0.1.8 native code for [front] to take
  /// effect; older natives ignore unknown arguments.
  Future<void> startCapture({bool? front}) async {
    _frameSub?.cancel();
    _frameSub = _frameChannel.receiveBroadcastStream().listen(
      (event) {
        if (event is Map) {
          _frameController.add(DeepARFrame.fromMap(event));
        }
      },
      onError: (e) => _frameController.addError(e),
    );
    await _channel.invokeMethod('startCapture', {'front': front});
  }

  /// Stop camera capture and frame delivery.
  Future<void> stopCapture() async {
    _frameSub?.cancel();
    _frameSub = null;
    await _channel.invokeMethod('stopCapture');
  }

  /// Load an AR effect file.
  ///
  /// [effectPath] should be relative to your app's assets folder,
  /// e.g. `'effects/burning_effect.deepar'`.
  ///
  /// Pass `null` or `'None'` to clear the current effect.
  Future<void> loadEffect(String? effectPath) async {
    await _channel.invokeMethod('loadEffect', {
      'effectPath': effectPath ?? 'None',
    });
  }

  /// Clear the current AR effect.
  Future<void> clearEffect() => loadEffect(null);

  /// Switch between front and back camera.
  ///
  /// Returns the resulting facing (`true` = front camera) so callers can
  /// mirror real state instead of blind-toggling their own flag. On Android
  /// (>= 0.1.8) the value is committed only after the new camera session
  /// actually configures; on iOS it reflects the requested facing (the
  /// session rebuild completes asynchronously). With pre-0.1.8 native code
  /// the returned value is always `true` regardless of facing — treat it as
  /// authoritative only on >= 0.1.8 natives.
  Future<bool?> switchCamera() async {
    final result = await _channel.invokeMethod('switchCamera');
    return result is bool ? result : null;
  }

  /// Fully release all DeepAR resources.
  ///
  /// After calling this, the controller cannot be used again without
  /// calling [initialize] first.
  Future<void> dispose() async {
    _frameSub?.cancel();
    _frameSub = null;
    await _frameController.close();
    try {
      await _channel.invokeMethod('destroyDeepAR');
    } catch (_) {}
    _initialized = false;
  }
}
