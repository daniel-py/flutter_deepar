/// Flutter plugin for the DeepAR augmented reality SDK.
///
/// Provides camera capture, AR effect loading, and processed frame output.
///
/// ```dart
/// import 'package:flutter_deepar/flutter_deepar.dart';
///
/// final deepar = DeepARController();
/// await deepar.initialize(licenseKey: 'YOUR_KEY');
/// await deepar.startCapture();
/// await deepar.loadEffect('effects/my_filter.deepar');
/// ```
library flutter_deepar;

export 'src/deepar_controller.dart';
export 'src/deepar_frame.dart';
